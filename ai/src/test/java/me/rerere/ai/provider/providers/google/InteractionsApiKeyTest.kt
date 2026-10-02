package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
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
        assertEquals("selected-key", request(provider).header("x-goog-api-key"))
        assertEquals(1, provider.selectedApiKeyIndex)
    }

    @Test
    fun `interactions honors model request key without changing provider selection`() {
        val requestProvider = provider.withRequestApiKey("model-key") as ProviderSetting.Google

        assertEquals("model-key", request(requestProvider).header("x-goog-api-key"))
        assertEquals("selected-key", provider.selectedApiKey())
        assertEquals(1, provider.selectedApiKeyIndex)
    }

    @Test
    fun `interactions merges provider headers and request overrides while keeping selected key`() {
        val requestProvider = provider.copy(
            customHeaders = listOf(
                CustomHeader("X-Provider", "provider-value"),
                CustomHeader("X-Shared", "provider-default"),
            ),
        )

        val captured = request(
            requestProvider,
            headers = listOf(CustomHeader("x-shared", "request-value")),
        )

        assertEquals("provider-value", captured.header("X-Provider"))
        assertEquals(listOf("request-value"), captured.headers.values("X-Shared"))
        assertEquals("selected-key", captured.header("x-goog-api-key"))
    }

    private fun request(
        providerSetting: ProviderSetting.Google,
        headers: List<CustomHeader> = emptyList(),
    ): Request = runBlocking {
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
                params = TextGenerationParams(
                    model = Model(modelId = "gemini-3.8-flash"),
                    customHeaders = headers,
                ),
            )
            assertEquals("/v1beta/interactions", captured.get().url.encodedPath)
            captured.get()
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
