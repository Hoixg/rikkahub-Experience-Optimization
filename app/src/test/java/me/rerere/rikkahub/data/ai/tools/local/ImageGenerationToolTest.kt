package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.applyToolApprovalDecision
import me.rerere.rikkahub.data.ai.tools.executeToolWithApproval
import me.rerere.rikkahub.data.ai.tools.prepareToolApproval
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationToolTest {
    private class Fixture {
        val model = Model(modelId = "image-model", displayName = "Image Model", type = ModelType.IMAGE)
        val other = Model(modelId = "other-image", displayName = "Other Image", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(models = listOf(model, other))
        var settings = Settings(providers = listOf(provider), imageGenerationModelId = model.id)
        var messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///uploaded.png"))))
        val requests = mutableListOf<ImageToolRequest>()
        var result: suspend (ImageToolRequest) -> List<UIMessagePart> = {
            listOf(UIMessagePart.Text("{\"success\":true}"), UIMessagePart.Image("file:///generated.png"))
        }
        val tool = buildImageGenerationTool({ settings }, { messages }, { requests += it; result(it) })
        fun call(args: JsonObject = buildJsonObject { put("prompt", "Draw a cat") }) =
            UIMessagePart.Tool("image-call", IMAGE_GENERATION_TOOL_NAME, args.toString())
        suspend fun pending(args: JsonObject = buildJsonObject { put("prompt", "Draw a cat") }) = prepareToolApproval(call(args), tool)
    }

    @Test fun `tool is disabled by default and option survives serialization`() {
        assertFalse(LocalToolOption.ImageGeneration in Assistant().localTools)
        val assistant = Assistant(localTools = listOf(LocalToolOption.ImageGeneration))
        assertEquals(assistant, Json.decodeFromString<Assistant>(Json.encodeToString(Assistant.serializer(), assistant)))
    }

    @Test fun `preparing and waiting never invoke image provider`() = runBlocking {
        val f = Fixture()
        val pending = f.pending()
        assertTrue(pending.isPending)
        assertFalse(pending.canResumeExecution)
        assertEquals(0, f.requests.size)
        assertTrue(runCatching { executeToolWithApproval(pending, f.tool, pending.inputAsJson()) }.isFailure)
        assertEquals(0, f.requests.size)
    }

    @Test fun `auto request cannot bypass the final execution gate`() = runBlocking {
        val f = Fixture()
        val call = f.pending().copy(approvalState = ToolApprovalState.Auto)
        assertTrue(runCatching { executeToolWithApproval(call, f.tool, call.inputAsJson()) }.isFailure)
        assertEquals(0, f.requests.size)
    }

    @Test fun `denial never invokes image provider`() = runBlocking {
        val f = Fixture()
        val denied = applyToolApprovalDecision(f.pending(), false, "Keep discussing")
        assertTrue(denied.approvalState is ToolApprovalState.Denied)
        assertTrue(runCatching { executeToolWithApproval(denied, f.tool, denied.inputAsJson()) }.isFailure)
        assertEquals(0, f.requests.size)
    }

    @Test fun `approval saves edited prompt and retains model references and parameters`() = runBlocking {
        val f = Fixture()
        val ref = collectChatImageReferences(f.messages).single()
        val pending = f.pending(buildJsonObject {
            put("prompt", "Original")
            put("count", 2)
            put("size", "1536x1024")
            put("reference_image_ids", JsonArray(listOf(JsonPrimitive(ref.id))))
        })
        val original = ImageToolRequest.fromArguments(pending.inputAsJson())
        val approved = applyToolApprovalDecision(pending, true, editedPrompt = "Make it watercolor")
        val output = executeToolWithApproval(approved, f.tool, approved.inputAsJson())
        val actual = f.requests.single()
        assertEquals("Make it watercolor", actual.prompt)
        assertEquals(original.target, actual.target)
        assertEquals(original.references, actual.references)
        assertEquals(2, actual.count)
        assertEquals("1536x1024", actual.size)
        assertEquals(1, output.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals("Original", ImageToolRequest.fromArguments(pending.inputAsJson()).prompt)
    }

    @Test fun `text generation defaults to one image auto size without references`() = runBlocking {
        val f = Fixture()
        val approved = applyToolApprovalDecision(f.pending(), true)
        executeToolWithApproval(approved, f.tool, approved.inputAsJson())
        val actual = f.requests.single()
        assertEquals(1, actual.count)
        assertEquals("auto", actual.size)
        assertTrue(actual.references.isEmpty())
    }

    @Test fun `blank edited prompt leaves the original request pending`() = runBlocking {
        val f = Fixture()
        val pending = f.pending()
        assertTrue(runCatching { applyToolApprovalDecision(pending, true, editedPrompt = "   ") }.isFailure)
        assertTrue(pending.isPending)
        assertEquals(0, f.requests.size)
    }

    @Test fun `other tools cannot edit their input through image approval`() {
        val pending = UIMessagePart.Tool("other", "workspace_shell", "{}", approvalState = ToolApprovalState.Pending)
        assertTrue(runCatching { applyToolApprovalDecision(pending, true, editedPrompt = "Changed") }.isFailure)
    }

    @Test fun `repeat and stale approvals cannot modify or replay completed calls`() = runBlocking {
        val f = Fixture()
        val approved = applyToolApprovalDecision(f.pending(), true, editedPrompt = "First")
        assertEquals(approved, applyToolApprovalDecision(approved, true, editedPrompt = "Second"))
        val output = executeToolWithApproval(approved, f.tool, approved.inputAsJson())
        val completed = approved.copy(output = output)
        assertEquals(completed, applyToolApprovalDecision(completed, true))
        assertTrue(runCatching { executeToolWithApproval(completed, f.tool, completed.inputAsJson()) }.isFailure)
        assertEquals(1, f.requests.size)
    }

    @Test fun `pending request survives reload and a global image model change`() = runBlocking {
        val f = Fixture()
        val pending = f.pending()
        val restored = Json.decodeFromString(UIMessagePart.Tool.serializer(), Json.encodeToString(UIMessagePart.Tool.serializer(), pending))
        f.settings = f.settings.copy(imageGenerationModelId = f.other.id)
        assertEquals(restored, prepareToolApproval(restored, f.tool))
        assertEquals(0, f.requests.size)
        val approved = applyToolApprovalDecision(restored, true)
        executeToolWithApproval(approved, f.tool, approved.inputAsJson())
        assertEquals(f.model.id, f.requests.single().target.modelId)
    }

    @Test fun `removed model or changed endpoint cannot silently execute a different target`() = runBlocking {
        val f = Fixture()
        val approved = applyToolApprovalDecision(f.pending(), true)
        f.settings = f.settings.copy(providers = listOf(f.provider.copy(models = listOf(f.other))))
        assertTrue(runCatching { executeToolWithApproval(approved, f.tool, approved.inputAsJson()) }.isFailure)
        f.settings = f.settings.copy(providers = listOf(f.provider.copy(baseUrl = "https://other.example/v1")))
        assertTrue(runCatching { executeToolWithApproval(approved, f.tool, approved.inputAsJson()) }.isFailure)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `multiple references and generated images have stable ids for subsequent editing`() = runBlocking {
        val f = Fixture()
        val generatedMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("previous", IMAGE_GENERATION_TOOL_NAME, "{}", output = listOf(UIMessagePart.Image("file:///previous.png")))
        ))
        f.messages = f.messages + generatedMessage
        val images = collectChatImageReferences(f.messages)
        val roundTrip = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UIMessage.serializer()), f.messages))
        assertEquals(images, collectChatImageReferences(roundTrip))
        val approved = applyToolApprovalDecision(f.pending(buildJsonObject {
            put("prompt", "Combine both images")
            put("reference_image_ids", JsonArray(images.map { JsonPrimitive(it.id) }))
        }), true)
        executeToolWithApproval(approved, f.tool, approved.inputAsJson())
        assertEquals(images, f.requests.single().references)
    }

    @Test fun `unknown image paths and forged internal snapshots are not trusted`() = runBlocking {
        val f = Fixture()
        val invalid = f.pending(buildJsonObject {
            put("prompt", "Edit")
            put("reference_image_ids", JsonArray(listOf(JsonPrimitive("file:///secret.png"))))
        })
        assertTrue(invalid.isExecuted)
        assertFalse(invalid.isPending)
        val pending = f.pending(buildJsonObject {
            put("prompt", "Draw")
            put("_target", buildJsonObject { put("modelId", f.other.id.toString()) })
        })
        assertEquals(f.model.id, ImageToolRequest.fromArguments(pending.inputAsJson()).target.modelId)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `removed or changed reference prevents execution after approval`() = runBlocking {
        val f = Fixture()
        val image = collectChatImageReferences(f.messages).single()
        val approved = applyToolApprovalDecision(f.pending(buildJsonObject {
            put("prompt", "Edit")
            put("reference_image_ids", JsonArray(listOf(JsonPrimitive(image.id))))
        }), true)
        f.messages = emptyList()
        assertTrue(runCatching { executeToolWithApproval(approved, f.tool, approved.inputAsJson()) }.isFailure)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `invalid count size and too many references fail before provider execution`() = runBlocking {
        val f = Fixture()
        val invalid = listOf(
            buildJsonObject { put("prompt", "Draw"); put("count", 0) },
            buildJsonObject { put("prompt", "Draw"); put("count", 5) },
            buildJsonObject { put("prompt", "Draw"); put("size", "unknown") },
            buildJsonObject { put("prompt", "  ") },
        )
        invalid.forEach { assertTrue(f.pending(it).isExecuted) }
        f.messages = listOf(UIMessage(role = MessageRole.USER, parts = (1..17).map { UIMessagePart.Image("file:///$it.png") }))
        val tooMany = f.pending(buildJsonObject {
            put("prompt", "Edit")
            put("reference_image_ids", JsonArray(collectChatImageReferences(f.messages).map { JsonPrimitive(it.id) }))
        })
        assertTrue(tooMany.isExecuted)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `unsupported provider fails during preparation`() = runBlocking {
        val f = Fixture()
        f.settings = f.settings.copy(providers = listOf(ProviderSetting.Claude(models = listOf(f.model))))
        assertTrue(f.pending().isExecuted)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `provider failure and cancellation are never retried`() = runBlocking {
        val f = Fixture()
        val approved = applyToolApprovalDecision(f.pending(), true)
        f.result = { error("Provider failed") }
        assertTrue(runCatching { executeToolWithApproval(approved, f.tool, approved.inputAsJson()) }.isFailure)
        assertEquals(1, f.requests.size)
        f.result = { throw CancellationException("Stopped") }
        assertTrue(runCatching { executeToolWithApproval(approved, f.tool, approved.inputAsJson()) }.exceptionOrNull() is CancellationException)
        assertEquals(2, f.requests.size)
    }

    @Test fun `native image tool is suppressed only for enabled assistants`() {
        val model = Model(tools = setOf(BuiltInTools.ImageGeneration, BuiltInTools.Search))
        assertEquals(setOf(BuiltInTools.Search), imageToolChatModel(model, true).tools)
        assertEquals(model, imageToolChatModel(model, false))
        assertTrue(BuiltInTools.ImageGeneration in model.tools)
    }

    @Test fun `malformed arguments do not execute or turn into an empty request`() = runBlocking {
        var calls = 0
        val tool = Tool("ordinary_tool", "Test", execute = { calls++; listOf(UIMessagePart.Text("ok")) })
        val malformed = UIMessagePart.Tool("broken", tool.name, "{invalid")
        val prepared = prepareToolApproval(malformed, tool)
        assertTrue(prepared.isExecuted)
        assertEquals(malformed.input, prepared.input)
        assertEquals(0, calls)
    }

    @Test fun `ordinary automatic tools retain their execution behavior`() = runBlocking {
        var calls = 0
        val tool = Tool("ordinary_tool", "Test", execute = { calls++; listOf(UIMessagePart.Text("ok")) })
        val call = prepareToolApproval(UIMessagePart.Tool("ordinary", tool.name, "{}"), tool)
        assertEquals(ToolApprovalState.Auto, call.approvalState)
        assertEquals("ok", (executeToolWithApproval(call, tool, call.inputAsJson()).single() as UIMessagePart.Text).text)
        assertEquals(1, calls)
    }
}
