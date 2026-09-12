package me.rerere.rikkahub.data.repository

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** A small loopback-only API used by tool pages loaded in the WebView. */
class WorkspaceToolWebServer(
    private val appScope: AppScope,
    private val repository: WorkspaceRepository,
) {
    private data class Binding(
        val workspaceId: String,
        val server: ServerSocket,
        val job: Job,
        val staticRoot: String? = null,
        val staticEntry: String? = null,
        val toolPort: Int? = null,
    )

    private val bindings = ConcurrentHashMap<String, Binding>()

    fun reserve(runId: String, workspaceId: String): Int {
        bindings[runId]?.let { return it.server.localPort }
        val server = ServerSocket(0, 32, java.net.InetAddress.getByName("127.0.0.1"))
        val job = appScope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                runCatching { server.accept() }.onSuccess { socket ->
                    launch(Dispatchers.IO) { socket.use { handle(runId, it) } }
                }.onFailure { if (!server.isClosed) return@launch }
            }
        }
        bindings[runId] = Binding(workspaceId, server, job)
        return server.localPort
    }

    fun apiUrl(runId: String): String =
        bindings[runId]?.let { "http://127.0.0.1:${it.server.localPort}/api/workspace/$runId" }
            ?: ""

    fun reserveToolPort(runId: String): Int {
        val binding = bindings[runId] ?: error("Tool workspace API is unavailable")
        repeat(8) {
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (port != binding.server.localPort) return port
        }
        error("Unable to reserve a tool web port")
    }

    suspend fun awaitPort(runId: String, port: Int, timeoutMillis: Long = 15_000L) {
        require(bindings.containsKey(runId)) { "Tool workspace API is unavailable" }
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
            while (System.currentTimeMillis() < deadline) {
                val binding = bindings[runId]
                if (binding == null || binding.server.isClosed) error("Tool workspace API is closed")
                val connected = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    }
                }.isSuccess
                if (connected) return@withContext
                delay(50L)
            }
            error("Tool web server did not start on port $port")
        }
    }

    suspend fun openStatic(workspaceId: String, tool: WorkspaceTool, runId: String): String {
        val html = tool.manifest.entry.html ?: error("HTML entry is required")
        reserve(runId, workspaceId)
        repository.resolveFile(workspaceId, WorkspaceStorageArea.FILES, "${tool.rootPath}/$html")
        bindings.computeIfPresent(runId) { _, binding -> binding.copy(staticRoot = tool.rootPath, staticEntry = html) }
        val port = bindings[runId]?.server?.localPort ?: error("Tool web server is unavailable")
        return "http://127.0.0.1:$port/static/$runId/" + encodeRelativePath(html)
    }

    fun openServer(runId: String, port: Int): String {
        val binding = bindings[runId] ?: error("Tool web server is unavailable")
        bindings[runId] = binding.copy(toolPort = port)
        return "http://127.0.0.1:" + binding.server.localPort + "/tool/" + runId + "/"
    }

    fun close(runId: String) {
        bindings.remove(runId)?.let {
            it.server.close()
            it.job.cancel()
        }
    }

    fun closeStatic(runId: String) {
        val binding = bindings[runId] ?: return
        if (binding.staticRoot != null) close(runId)
    }

    private suspend fun handle(runId: String, socket: java.net.Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val binding = bindings[runId] ?: return
        val request = try {
            readRequest(socket.getInputStream())
        } catch (error: Throwable) {
            writeResponse(
                socket.getOutputStream(),
                buildJsonObject { put("ok", false); put("error", error.message.orEmpty()) }.toString(),
                400,
            )
            return
        }
        val method = request.method
        val rawTarget = request.target
        val body = String(request.body, StandardCharsets.UTF_8)
        val target = runCatching { Uri.decode(rawTarget.substringBefore('?')) }.getOrElse {
            writeResponse(socket.getOutputStream(), errorJson("Invalid request path"), 400)
            return
        }
        val query = runCatching {
            rawTarget.substringAfter('?', "").split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                val value = pair.substringAfter('=', "")
                if (key.isBlank()) null else URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }.toMap()
        }.getOrElse {
            writeResponse(socket.getOutputStream(), errorJson("Invalid request query"), 400)
            return
        }
        val base = "/api/workspace/$runId"
        val staticPrefix = "/static/$runId"
        if (method == "GET" && binding.staticRoot != null && (target == staticPrefix || target.startsWith("$staticPrefix/"))) {
            val response = runCatching {
                val requested = target.removePrefix(staticPrefix).trimStart('/').ifBlank { binding.staticEntry.orEmpty() }
                val path = safeRelativePath(requested)
                val file = repository.resolveFile(binding.workspaceId, WorkspaceStorageArea.FILES, "${binding.staticRoot}/$path")
                val contentType = mimeType(path)
                StaticResponse(injectWorkspaceApi(file.readBytes(), contentType, runId), contentType, 200)
            }.getOrElse { StaticResponse(it.message.orEmpty().toByteArray(StandardCharsets.UTF_8), "text/plain; charset=utf-8", 400) }
            writeStaticResponse(socket.getOutputStream(), response)
            return
        }
        val toolPrefix = "/tool/$runId"
        if (binding.toolPort != null && (target == toolPrefix || target.startsWith("$toolPrefix/"))) {
            val response = proxyToTool(request, toolPrefix, binding.toolPort, runId)
            writeProxyResponse(socket.getOutputStream(), response)
            return
        }
        val result = runCatching {
            when {
                target == "$base/files" && method == "GET" -> listFiles(binding.workspaceId, query["path"].orEmpty())
                target == "$base/file" && method == "GET" -> readFile(binding.workspaceId, query["path"].orEmpty())
                target == "$base/file" && method == "POST" -> writeFile(binding.workspaceId, body)
                target == "$base/directory" && method == "POST" -> createDirectory(binding.workspaceId, body)
                method == "OPTIONS" -> buildJsonObject { put("ok", true) }
                else -> error("Not found")
            }
        }.getOrElse { buildJsonObject { put("ok", false); put("error", it.message.orEmpty()) } }
        writeResponse(socket.getOutputStream(), result.toString(), if (result["ok"]?.jsonPrimitive?.contentOrNull == "false") 400 else 200)
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: InputStream): HttpRequest {
        val headerBytes = ByteArrayOutputStream()
        var previous = -1
        var previousPrevious = -1
        var previousPreviousPrevious = -1
        while (headerBytes.size() <= MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) error("Incomplete HTTP request")
            headerBytes.write(next)
            if (previousPreviousPrevious == '\r'.code && previousPrevious == '\n'.code && previous == '\r'.code && next == '\n'.code) {
                break
            }
            previousPreviousPrevious = previousPrevious
            previousPrevious = previous
            previous = next
        }
        require(headerBytes.size() <= MAX_HEADER_BYTES) { "HTTP headers are too large" }

        val headerText = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ', limit = 3)
        require(requestLine?.size == 3) { "Invalid HTTP request line" }
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        require(headers["transfer-encoding"].isNullOrBlank()) { "Chunked requests are not supported" }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength >= 0 && contentLength <= MAX_BODY_BYTES) { "HTTP body is too large" }
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            if (count < 0) error("Incomplete HTTP request body")
            offset += count
        }
        return HttpRequest(
            method = requestLine[0],
            target = requestLine[1],
            body = bodyBytes,
            headers = headers,
        )
    }

    private data class ProxyResponse(
        val code: Int,
        val contentType: String?,
        val location: String?,
        val body: ByteArray,
    )

    private fun proxyToTool(request: HttpRequest, prefix: String, port: Int, runId: String): ProxyResponse {
        val pathAndQuery = request.target.removePrefix(prefix).ifBlank { "/" }
            .let { if (it.startsWith('/')) it else "/$it" }
        val connection = (URL("http://127.0.0.1:$port$pathAndQuery").openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            instanceFollowRedirects = false
            connectTimeout = SOCKET_TIMEOUT_MILLIS
            readTimeout = SOCKET_TIMEOUT_MILLIS
            request.headers.forEach { (name, value) ->
                if (name !in setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding")) {
                    setRequestProperty(name, value)
                }
            }
            setRequestProperty("Accept-Encoding", "identity")
            if (request.body.isNotEmpty()) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        val code = runCatching { connection.responseCode }.getOrElse { 502 }
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val body = stream?.use(InputStream::readBytes) ?: ByteArray(0)
        val contentType = connection.contentType
        return ProxyResponse(
            code = code,
            contentType = contentType,
            location = rewriteToolLocation(connection.getHeaderField("Location"), prefix),
            body = injectWorkspaceApi(body, contentType, runId),
        ).also { connection.disconnect() }
    }

    private fun injectWorkspaceApi(body: ByteArray, contentType: String?, runId: String): ByteArray {
        if (contentType?.substringBefore(";")?.trim()?.lowercase() != "text/html") return body
        val html = String(body, StandardCharsets.UTF_8)
        val script = "<script>window.RHK_WORKSPACE_API_URL=\'/api/workspace/$runId\';window.RHK_WORKSPACE_API_BASE=\'/api/workspace/$runId\';</script>"
        val headEnd = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.range?.last?.plus(1)
        val injected = if (headEnd != null) {
            html.substring(0, headEnd) + script + html.substring(headEnd)
        } else {
            script + html
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
    }

    private fun rewriteToolLocation(location: String?, prefix: String): String? {
        if (location.isNullOrBlank() || !location.startsWith("/")) return location
        return prefix + location
    }

    private suspend fun listFiles(workspaceId: String, path: String) = buildJsonObject {
        val safePath = if (path.isBlank()) "" else safeRelativePath(path)
        put("ok", true)
        put("files", buildJsonArray {
            repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, safePath).forEach { entry ->
                add(buildJsonObject {
                    put("path", entry.path); put("name", entry.name);
                    put("directory", entry.isDirectory); put("size", entry.sizeBytes)
                })
            }
        })
    }

    private suspend fun readFile(workspaceId: String, path: String) = buildJsonObject {
        val safePath = safeRelativePath(path)
        put("ok", true); put("path", safePath); put("text", repository.readText(workspaceId, safePath))
    }

    private suspend fun writeFile(workspaceId: String, body: String) = buildJsonObject {
        val json = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(body).jsonObject
        val path = safeRelativePath(json["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required"))
        val text = json["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
        repository.writeText(workspaceId, path, text, overwrite = json["overwrite"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true)
        put("ok", true); put("path", path)
    }

    private suspend fun createDirectory(workspaceId: String, body: String) = buildJsonObject {
        val json = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(body).jsonObject
        val path = safeRelativePath(json["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required"))
        repository.createDirectory(workspaceId, path)
        put("ok", true); put("path", path)
    }

    private fun writeResponse(output: OutputStream, body: String, code: Int) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $code ${statusText(code)}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(bytes); output.flush()
    }

    private data class StaticResponse(val body: ByteArray, val contentType: String, val code: Int)

    private fun writeStaticResponse(output: OutputStream, response: StaticResponse) {
        output.write("HTTP/1.1 ${response.code} ${statusText(response.code)}\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${response.body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(response.body)
        output.flush()
    }

    private fun writeProxyResponse(output: OutputStream, response: ProxyResponse) {
        val headers = buildString {
            append("HTTP/1.1 ${response.code} ${statusText(response.code)}\r\n")
            response.contentType?.let { append("Content-Type: $it\r\n") }
            response.location?.let { append("Location: $it\r\n") }
            append("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(response.body)
        output.flush()
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message) }.toString()

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun safeRelativePath(path: String): String {
        val normalized = path.replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "Path is required" }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) {
            "Invalid workspace path"
        }
        return normalized
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun encodeRelativePath(path: String): String = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    companion object {
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
