package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.selectedApiKey
import me.rerere.ai.provider.withRequestApiKey
import me.rerere.ai.ui.UIMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class InteractionsApiKeyTest {
    private val provider = ProviderSetting.Google(
        apiKey = "legacy-key",
        apiKeys = listOf("first-key", "selected-key"),
        selectedApiKeyIndex = 1,
        baseUrl = "https://example.test/v1beta",
        useInteractionsApi = true,
    )

    @Test
    fun `interactions uses selected saved key instead of legacy key`() {
        assertEquals("selected-key", requestKey(provider))
        assertEquals(1, provider.selectedApiKeyIndex)
    }

    @Test
    fun `interactions honors model request key without changing provider selection`() {
        val requestProvider = provider.withRequestApiKey("model-key") as ProviderSetting.Google

        assertEquals("model-key", requestKey(requestProvider))
        assertEquals("selected-key", provider.selectedApiKey())
        assertEquals(1, provider.selectedApiKeyIndex)
    }

    private fun requestKey(providerSetting: ProviderSetting.Google): String? = runBlocking {
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"id":"int-key-test","status":"completed","steps":[]}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        try {
            GoogleProvider(client).generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-3.8-flash")),
            )
            assertEquals("/v1beta/interactions", captured.get().url.encodedPath)
            captured.get().header("x-goog-api-key")
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
