package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UpdateCheckerTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val userAgent = "RikkaPlus 2.5.56 #194"

    @After
    fun cleanUp() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun `loads own latest release using GitHub headers and existing update types`() = runBlocking {
        val request = AtomicReference<Request>()
        val client = client { chain ->
            request.set(chain.request())
            response(chain, release())
        }
        val states = states(client)
        assertEquals(UiState.Loading, states.first())
        val info = (states.last() as UiState.Success).data
        assertEquals("https://api.github.com/repos/Hoixg/rikkaplus/releases/latest", request.get().url.toString())
        assertEquals(userAgent, request.get().header("User-Agent"))
        assertEquals("application/vnd.github+json", request.get().header("Accept"))
        assertEquals("2.5.57", info.version)
        assertEquals("2026-10-01T10:00:00Z", info.publishedAt)
        assertEquals("Release notes", info.changelog)
        assertEquals("app-arm64-v8a-release.apk", info.downloads.single().name)
        assertEquals("10.0 MiB", info.downloads.single().size)
        assertEquals("https://github.com/Hoixg/rikkaplus/releases/download/2.5.57/app-arm64-v8a-release.apk", info.downloads.single().url)
    }

    @Test
    fun `filters checksums unfinished empty and invalid APK assets`() = runBlocking {
        val assets = listOf(
            asset("app-arm64-v8a-release.apk"), asset("app-universal-release.apk"),
            asset("app-x86_64-release.apk"), asset("SHA256SUMS.txt"),
            asset("pending.apk", state = "starter"), asset("empty.apk", size = 0),
            asset("invalid.apk", url = "not a URL"),
        )
        val info = (states(client { response(it, release(assets = assets)) }).last() as UiState.Success).data
        assertEquals(listOf("app-arm64-v8a-release.apk", "app-universal-release.apk", "app-x86_64-release.apk"), info.downloads.map { it.name })
    }

    @Test
    fun `null and empty release notes are accepted`() = runBlocking {
        listOf(JsonNull, JsonPrimitive("")).forEach { notes ->
            val info = (states(client { response(it, release(notes = notes)) }).last() as UiState.Success).data
            assertEquals("", info.changelog)
        }
    }

    @Test
    fun `version tags map to existing comparison behavior`() = runBlocking {
        listOf("2.5.55", "2.5.56", "v2.5.57").forEach { tag ->
            val info = (states(client { response(it, release(tag = tag)) }).last() as UiState.Success).data
            assertEquals(tag == "v2.5.57", Version(info.version) > Version("2.5.56"))
        }
    }

    @Test
    fun `HTTP failures emit existing error state without fallback requests`() = runBlocking {
        listOf(403, 404, 429, 503).forEach { code ->
            val requests = AtomicInteger()
            val client = client {
                requests.incrementAndGet()
                assertEquals("api.github.com", it.request().url.host)
                response(it, "{}", code)
            }
            val error = (states(client).last() as UiState.Error).error
            assertTrue(error.message.orEmpty().contains("HTTP $code"))
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `invalid response data emits error state`() = runBlocking {
        listOf("not JSON", "{}", "[]", release(tag = "not-a-version"), release(publishedAt = "invalid-date")).forEach { body ->
            assertTrue(states(client { response(it, body) }).last() is UiState.Error)
        }
    }

    @Test
    fun `draft prerelease and missing APK releases emit error state`() = runBlocking {
        listOf(release(draft = true), release(prerelease = true), release(assets = emptyList()), release(assets = listOf(asset("SHA256SUMS.txt")))).forEach { body ->
            assertTrue(states(client { response(it, body) }).last() is UiState.Error)
        }
    }

    @Test
    fun `network failures and timeouts emit error state`() = runBlocking {
        listOf(IOException("offline"), SocketTimeoutException("timed out")).forEach { failure ->
            val error = (states(client { throw failure }).last() as UiState.Error).error
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
        }
    }

    @Test
    fun `cancelling before headers cancels the call without emitting error`() = runBlocking {
        verifyCancellation(blockBody = false)
    }

    @Test
    fun `cancelling during body read cancels call and closes response without emitting error`() = runBlocking {
        verifyCancellation(blockBody = true)
    }

    private suspend fun verifyCancellation(blockBody: Boolean) = withTimeout(5_000) {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val observed = CopyOnWriteArrayList<UiState<UpdateInfo>>()
        val client = client { chain ->
            fun waitUntilCancelled(): Nothing {
                started.countDown()
                while (!chain.call().isCanceled()) Thread.sleep(5)
                stopped.countDown()
                throw IOException("Cancelled")
            }
            if (!blockBody) waitUntilCancelled()
            val body = object : ResponseBody() {
                private val source = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = waitUntilCancelled()
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() { closed.countDown() }
                }.buffer()
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = source
            }
            response(chain, "").newBuilder().body(body).build()
        }
        val job = launch { GitHubReleaseSource.updates(client, userAgent).collect { observed.add(it) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            job.cancel(CancellationException("Manual stop"))
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(withContext(Dispatchers.IO) { stopped.await(3, TimeUnit.SECONDS) })
            if (blockBody) assertTrue(withContext(Dispatchers.IO) { closed.await(3, TimeUnit.SECONDS) })
            assertFalse(observed.any { it is UiState.Error || it is UiState.Success })
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun client(intercept: (Interceptor.Chain) -> Response): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(Interceptor(intercept))
        .build().also { clients.add(it) }

    private suspend fun states(client: OkHttpClient): List<UiState<UpdateInfo>> = withTimeout(5_000) {
        GitHubReleaseSource.updates(client, userAgent).toList()
    }

    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun release(
        tag: String = "2.5.57",
        notes: JsonElement = JsonPrimitive("Release notes"),
        publishedAt: String = "2026-10-01T10:00:00Z",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<JsonElement> = listOf(asset("app-arm64-v8a-release.apk")),
    ): String = buildJsonObject {
        put("tag_name", tag)
        put("published_at", publishedAt)
        put("body", notes)
        put("draft", draft)
        put("prerelease", prerelease)
        put("assets", JsonArray(assets))
        put("ignored_field", "GitHub metadata")
    }.toString()

    private fun asset(
        name: String,
        state: String = "uploaded",
        size: Long = 10_485_760,
        url: String = "https://github.com/Hoixg/rikkaplus/releases/download/2.5.57/$name",
    ): JsonElement = buildJsonObject {
        put("name", name)
        put("state", state)
        put("size", size)
        put("browser_download_url", url)
    }
}
