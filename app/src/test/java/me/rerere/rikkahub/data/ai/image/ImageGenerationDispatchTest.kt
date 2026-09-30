package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationDispatchTest {
    private class FakeProvider : Provider<ProviderSetting> {
        var generation: ImageGenerationParams? = null
        var editing: ImageEditParams? = null
        var calls = 0
        var failure: Exception? = null
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult = error("unused")
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("unused")
        private fun images() = flow {
            calls++
            failure?.let { throw it }
            emit(ImageGenerationItem("aW1hZ2U=", "image/png"))
        }
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> {
            generation = params
            return images()
        }
        override suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams): Flow<ImageGenerationItem> {
            editing = params
            return images()
        }
    }

    private val model = Model(modelId = "images", customHeaders = listOf(CustomHeader("X-Test", "value")),
        customBodies = listOf(CustomBody("quality", JsonPrimitive("high")), CustomBody("prompt", JsonPrimitive("override"))))
    private val provider = ProviderSetting.OpenAI(models = listOf(model))

    @Test fun `text request calls generation with approved parameters and model options`() = runBlocking {
        val implementation = FakeProvider()
        val results = requestProviderImages(implementation, provider, model, "Approved", 3, "1024x1024", emptyList(), true).toList()
        assertEquals(1, results.size)
        assertNull(implementation.editing)
        assertEquals("Approved", implementation.generation!!.prompt)
        assertEquals(3, implementation.generation!!.numOfImages)
        assertEquals("1024x1024", implementation.generation!!.size)
        assertEquals(model.customHeaders, implementation.generation!!.customHeaders)
        assertEquals(listOf("quality"), implementation.generation!!.customBody.map { it.key })
    }

    @Test fun `multiple materialized references call editing and retain reference order`() = runBlocking {
        val implementation = FakeProvider()
        val references = listOf("/upload/first.png", "/upload/second.webp")
        requestProviderImages(implementation, provider, model, "Combine", 1, "auto", references, true).toList()
        assertNull(implementation.generation)
        assertEquals(references, implementation.editing!!.images)
        assertEquals("Combine", implementation.editing!!.prompt)
        assertEquals(model.customHeaders, implementation.editing!!.customHeaders)
    }

    @Test fun `instant image generation retains its existing custom request fields`() = runBlocking {
        val implementation = FakeProvider()
        requestProviderImages(implementation, provider, model, "Instant", 1, "auto", emptyList(), false).toList()
        assertEquals(model.customBodies, implementation.generation!!.customBody)
    }

    @Test fun `failure and cancellation flow through without retrying provider`() = runBlocking {
        for (failure in listOf(IllegalStateException("Unavailable"), CancellationException("Stopped"))) {
            val implementation = FakeProvider().apply { this.failure = failure }
            val actual = runCatching {
                requestProviderImages(implementation, provider, model, "Generate", 1, "auto", emptyList(), true).toList()
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertEquals(1, implementation.calls)
        }
    }
}
