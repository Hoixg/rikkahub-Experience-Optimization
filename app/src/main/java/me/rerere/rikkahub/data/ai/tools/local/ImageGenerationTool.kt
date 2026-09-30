package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findRequestProvider
import kotlin.uuid.Uuid

const val IMAGE_GENERATION_TOOL_NAME = "generate_image"
private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class ImageToolException(val resourceId: Int, message: String) : IllegalArgumentException(message)

@Serializable
data class ChatImageReference(val id: String, val url: String)

@Serializable
data class ImageGenerationTarget(
    val modelId: Uuid,
    val modelApiId: String,
    val modelName: String,
    val providerId: Uuid,
    val providerName: String,
    val baseUrl: String,
)

@Serializable
data class ImageToolRequest(
    val prompt: String,
    val count: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    @SerialName("reference_image_ids") val referenceImageIds: List<String> = emptyList(),
    @SerialName("_target") val target: ImageGenerationTarget,
    @SerialName("_references") val references: List<ChatImageReference> = emptyList(),
) {
    fun toArguments(): JsonElement = requestJson.encodeToJsonElement(serializer(), this)

    fun validate() {
        require(prompt.isNotBlank()) { "Image prompt cannot be empty" }
        require(count in 1..4) { "Image count must be between 1 and 4" }
        require(ImageGenSize.entries.any { it.value == size }) { "Unsupported image size: $size" }
        require(referenceImageIds.size <= 16 && referenceImageIds.distinct().size == referenceImageIds.size) {
            "Use at most 16 distinct reference images"
        }
        require(referenceImageIds == references.map { it.id }) { "Invalid reference image snapshot" }
    }

    companion object {
        fun fromArguments(arguments: JsonElement): ImageToolRequest =
            requestJson.decodeFromJsonElement(serializer(), arguments).also { it.validate() }
    }
}

/** IDs remain stable across reloads and include uploaded images and nested tool results. */
fun collectChatImageReferences(messages: List<UIMessage>): List<ChatImageReference> = buildList {
    fun collect(parts: List<UIMessagePart>, prefix: String) {
        parts.forEachIndexed { index, part ->
            val id = "${prefix}_$index"
            when (part) {
                is UIMessagePart.Image -> if (part.url.isNotBlank()) add(ChatImageReference(id, part.url))
                is UIMessagePart.Tool -> collect(part.output, "${id}_${part.toolCallId}")
                else -> Unit
            }
        }
    }
    messages.forEach { collect(it.parts, "image_${it.id}") }
}

internal fun prepareImageToolRequest(
    arguments: JsonElement,
    settings: Settings,
    availableImages: List<ChatImageReference>,
): ImageToolRequest {
    val obj = arguments.jsonObject
    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull ?: error("prompt is required")
    val count = obj["count"]?.jsonPrimitive?.intOrNull ?: if ("count" !in obj) 1 else error("Invalid image count")
    val size = obj["size"]?.jsonPrimitive?.contentOrNull ?: ImageGenSize.AUTO.value
    val referenceIds = when (val references = obj["reference_image_ids"]) {
        null -> emptyList()
        is JsonArray -> references.map { it.jsonPrimitive.contentOrNull ?: error("Invalid reference image ID") }
        else -> error("reference_image_ids must be an array")
    }
    val model = settings.findModelById(settings.imageGenerationModelId)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Select an image model first")
    val provider = model.findRequestProvider(settings.providers)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Image provider is unavailable")
    if (!provider.enabled) throw ImageToolException(R.string.chat_image_generation_model_missing, "Image provider is disabled")
    if (provider !is ProviderSetting.OpenAI) throw ImageToolException(
        R.string.chat_image_generation_unsupported, "This provider does not support image generation or editing"
    )
    val imagesById = availableImages.associateBy { it.id }
    return ImageToolRequest(
        prompt = prompt,
        count = count,
        size = size,
        referenceImageIds = referenceIds,
        target = ImageGenerationTarget(model.id, model.modelId, model.displayName, provider.id, provider.name, provider.baseUrl),
        references = referenceIds.map { imagesById[it] ?: throw ImageToolException(
            R.string.chat_image_generation_reference_missing, "Reference image is unavailable: $it"
        ) },
    ).also { it.validate() }
}

internal fun validateImageToolTarget(request: ImageToolRequest, settings: Settings): Model {
    request.validate()
    val model = settings.findModelById(request.target.modelId)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Approved image model was removed")
    val provider = model.findRequestProvider(settings.providers)
        ?: throw ImageToolException(R.string.chat_image_generation_model_missing, "Approved image provider was removed")
    if (!(provider is ProviderSetting.OpenAI && provider.enabled &&
        provider.id == request.target.providerId && provider.baseUrl == request.target.baseUrl &&
        model.modelId == request.target.modelApiId)) throw ImageToolException(
        R.string.chat_image_generation_configuration_changed, "Image model configuration changed; request approval again"
    )
    return model
}

internal fun validateImageToolReferences(request: ImageToolRequest, images: List<ChatImageReference>) {
    val current = images.associateBy { it.id }
    if (!request.references.all { current[it.id]?.url == it.url }) throw ImageToolException(
        R.string.chat_image_generation_reference_missing, "A reference image was removed or changed"
    )
}

internal fun imageToolChatModel(model: Model, enabled: Boolean): Model =
    if (enabled) model.copy(tools = model.tools - BuiltInTools.ImageGeneration) else model

internal fun buildImageGenerationTool(
    getSettings: () -> Settings,
    getMessages: () -> List<UIMessage>,
    generate: suspend (ImageToolRequest) -> List<UIMessagePart>,
    errorMessage: (ImageToolException) -> String = { it.message.orEmpty() },
): Tool = Tool(
    name = IMAGE_GENERATION_TOOL_NAME,
    description = "Generate or edit images using the user's configured image model after user approval. " +
        "Discuss requirements first, then submit a complete prompt. Use reference_image_ids for images from this chat. " +
        "Every invocation requires approval. Do not automatically repeat a denied or failed request.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject { put("type", "string"); put("description", "Complete image generation or editing instructions") })
                put("count", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 4); put("description", "Number of images, default 1") })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(ImageGenSize.entries.map { JsonPrimitive(it.value) }))
                    put("description", "Image dimensions, default auto")
                })
                put("reference_image_ids", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("maxItems", 16)
                    put("description", "Optional IDs from the available chat image list. Empty for text-to-image. Never supply file paths or URLs.")
                })
            },
            required = listOf("prompt"),
        )
    },
    systemPrompt = { _, _ ->
        val images = collectChatImageReferences(getMessages())
        "Image generation requires explicit approval of each request. Available reference image IDs in chat order: " +
            JsonArray(images.map { JsonPrimitive(it.id) }).toString()
    },
    needsApproval = { true },
    prepareArguments = {
        try {
            prepareImageToolRequest(it, getSettings(), collectChatImageReferences(getMessages())).toArguments()
        } catch (e: ImageToolException) { error(errorMessage(e)) }
    },
    execute = {
        try {
            val request = ImageToolRequest.fromArguments(it)
            validateImageToolTarget(request, getSettings())
            validateImageToolReferences(request, collectChatImageReferences(getMessages()))
            generate(request)
        } catch (e: ImageToolException) { error(errorMessage(e)) }
    },
)

fun editApprovedImagePrompt(arguments: JsonElement, prompt: String): String =
    ImageToolRequest.fromArguments(arguments).copy(prompt = prompt).also { it.validate() }.toArguments().toString()
