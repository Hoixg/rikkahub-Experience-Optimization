package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceTool
import me.rerere.rikkahub.data.repository.WorkspaceToolManifest
import me.rerere.rikkahub.data.repository.WorkspaceToolRegistry
import me.rerere.rikkahub.data.repository.WorkspaceToolRun
import me.rerere.rikkahub.data.repository.WorkspaceToolRunStatus
import me.rerere.rikkahub.data.repository.WorkspaceToolWebServer
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Runs registered workspace tools in their own terminal tab. The tab stays alive after the
 * Compose page disappears, which gives command tools a real stdin channel and durable output.
 */
class WorkspaceToolRunManager(
    private val context: Context,
    private val appScope: AppScope,
    private val repository: WorkspaceRepository,
    private val registry: WorkspaceToolRegistry,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val webServer: WorkspaceToolWebServer,
) {
    private val state = MutableStateFlow<Map<String, WorkspaceToolRun>>(emptyMap())
    private val toolsVersion = MutableStateFlow(0)
    private val pollJobs = mutableMapOf<String, Job>()
    private val runJobs = mutableMapOf<String, Job>()

    fun observeWorkspace(workspaceId: String): Flow<List<WorkspaceToolRun>> =
        state.map { runs ->
            runs.values.filter { it.workspaceId == workspaceId }.sortedByDescending { it.id }
        }.distinctUntilChanged()

    fun observe(runId: String): Flow<WorkspaceToolRun?> =
        state.map { it[runId] }.distinctUntilChanged()

    fun current(runId: String): WorkspaceToolRun? = state.value[runId]

    fun observeToolsVersion(): Flow<Int> = toolsVersion

    suspend fun start(
        workspaceId: String,
        toolId: String,
        input: Map<String, String> = emptyMap(),
    ): WorkspaceToolRun {
        val tool = registry.get(workspaceId, toolId) ?: error("Tool not found: $toolId")
        validateInput(tool.manifest, input)
        val initial = WorkspaceToolRun(
            id = Uuid.random().toString(),
            workspaceId = workspaceId,
            tool = tool.manifest,
            status = WorkspaceToolRunStatus.PREPARING,
        )
        update(initial)
        runJobs[initial.id] = appScope.launch {
            try {
                startInternal(workspaceId, tool, input, initial)
            } finally {
                runJobs.remove(initial.id)
            }
        }
        return initial
    }

    private suspend fun startInternal(
        workspaceId: String,
        tool: WorkspaceTool,
        input: Map<String, String> = emptyMap(),
        initial: WorkspaceToolRun,
    ): WorkspaceToolRun {
        val runId = initial.id

       if (tool.manifest.entry.mode == "static_html") {
            return try {
                val webUrl = webServer.openStatic(workspaceId, tool, runId)
                initial.copy(status = WorkspaceToolRunStatus.FINISHED, webUrl = webUrl).also(::update)
            } catch (error: Throwable) {
                webServer.close(runId)
                val current = state.value[runId] ?: initial
                if (current.status == WorkspaceToolRunStatus.STOPPED || error is CancellationException) {
                    current
                } else {
                    current.copy(status = WorkspaceToolRunStatus.FAILED, error = error.message).also(::update)
                }
            }
        }

        val workspace = repository.getById(workspaceId)
        if (workspace == null) {
            val failed = initial.copy(
                status = WorkspaceToolRunStatus.FAILED,
                error = "Workspace not found: $workspaceId",
            )
            update(failed)
            return failed
        }
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            val failed = initial.copy(
                status = WorkspaceToolRunStatus.FAILED,
                error = "Install the workspace Linux environment before running tools",
            )
            update(failed)
            return failed
        }

        var sessionId: Long? = null
        return try {
            prepareIfNeeded(workspaceId, tool, initial)
            val prepared = state.value[runId] ?: initial
            if (prepared.status != WorkspaceToolRunStatus.PREPARING) return prepared
            val webPort = if (tool.manifest.entry.mode == "web_server") {
                webServer.reserve(runId, workspaceId)
                webServer.reserveToolPort(runId)
            } else null
            val session = terminalSessionManager.ensureAgentSession(
                root = workspace.root,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
                createNewTab = true,
            )
            sessionId = session.id
            if (state.value[runId]?.status != WorkspaceToolRunStatus.PREPARING) {
                terminalSessionManager.killAgentSession(workspace.root, session.id)
                return state.value[runId] ?: initial
            }
            val command = buildLaunchCommand(tool, input, runId, webPort)
            terminalSessionManager.sendAgentInput(
                root = workspace.root,
                tabId = session.id,
                input = command,
                keys = emptyList(),
                pressEnter = true,
            )
            val webUrl = if (webPort != null) {
                webServer.awaitPort(runId, webPort)
                webServer.openServer(runId, webPort)
            } else {
                null
            }
            if (state.value[runId]?.status != WorkspaceToolRunStatus.PREPARING) {
                terminalSessionManager.killAgentSession(workspace.root, session.id)
                return state.value[runId] ?: initial
            }
            val running = initial.copy(
                status = WorkspaceToolRunStatus.RUNNING,
                sessionId = session.id,
                webUrl = webUrl,
                log = state.value[runId]?.log.orEmpty(),
            )
            update(running)
            ChatGenerationForegroundService.acquireTool(context, runId)
            startPolling(workspace.root, running)
            running
        } catch (error: Throwable) {
            sessionId?.let { terminalSessionManager.killAgentSession(workspace.root, it) }
            webServer.close(runId)
            ChatGenerationForegroundService.releaseTool(context, runId)
            val current = state.value[runId] ?: initial
            if (current.status == WorkspaceToolRunStatus.STOPPED || error is CancellationException) return current
            val failed = current.copy(status = WorkspaceToolRunStatus.FAILED, error = error.message.orEmpty())
            update(failed)
            failed
        }
    }

    suspend fun sendInput(runId: String, value: String, submit: Boolean = true): WorkspaceToolRun {
        val run = state.value[runId] ?: error("Tool run not found")
        require(run.status == WorkspaceToolRunStatus.RUNNING) { "Tool is not running" }
        val workspace = repository.getById(run.workspaceId) ?: error("Workspace not found")
        val sessionId = run.sessionId ?: error("Tool session is unavailable")
        terminalSessionManager.sendAgentInput(
            root = workspace.root,
            tabId = sessionId,
            input = value,
            keys = emptyList(),
            pressEnter = submit,
        )
        return state.value[runId] ?: run
    }

    suspend fun stop(runId: String): Boolean {
        val run = state.value[runId] ?: return false
        if (run.status in setOf(
                WorkspaceToolRunStatus.FINISHED,
                WorkspaceToolRunStatus.FAILED,
                WorkspaceToolRunStatus.STOPPED,
            )
        ) return false

        // Mark the run first so a cancelled preparation coroutine cannot turn a user stop into a
        // failure while its shell command is unwinding.
        update(run.copy(status = WorkspaceToolRunStatus.STOPPED))
        runJobs.remove(runId)?.cancel()
        pollJobs.remove(runId)?.cancel()
        webServer.close(runId)
        ChatGenerationForegroundService.releaseTool(context, runId)

        val sessionId = run.sessionId ?: return true
        val workspace = repository.getById(run.workspaceId) ?: return true
        terminalSessionManager.killAgentSession(workspace.root, sessionId)
        return true
    }

    suspend fun listTools(workspaceId: String): List<WorkspaceTool> = registry.list(workspaceId)

    fun closeWebServer(runId: String) {
        webServer.closeStatic(runId)
        val run = state.value[runId] ?: return
        if (run.tool.entry.mode == "static_html" && run.webUrl != null) {
            update(run.copy(webUrl = null))
        }
    }

    suspend fun ensureStaticWebServer(runId: String) {
        val run = state.value[runId] ?: return
        if (run.status != WorkspaceToolRunStatus.FINISHED ||
            run.tool.entry.mode != "static_html" ||
            run.webUrl != null
        ) return
        val tool = registry.get(run.workspaceId, run.tool.id) ?: return
        val url = webServer.openStatic(run.workspaceId, tool, runId)
        update(run.copy(webUrl = url))
    }

    suspend fun register(workspaceId: String, manifestJson: String): WorkspaceTool =
        registry.register(workspaceId, manifestJson).also { toolsVersion.value++ }

    private suspend fun prepareIfNeeded(
        workspaceId: String,
        tool: WorkspaceTool,
        initial: WorkspaceToolRun,
    ) {
        val prepare = tool.manifest.prepare ?: return
        val markerPath = "${tool.rootPath}/.rikkahub-prepared"
        val ignoredResultPath = tool.manifest.result.path
            ?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            ?.let { joinToolPath(tool.rootPath, it) }
        val fingerprint = fingerprint(workspaceId, tool.rootPath, ignoredResultPath)
        val alreadyPrepared = runCatching { repository.readText(workspaceId, markerPath).trim() == fingerprint }.getOrDefault(false)
        if (prepare.once && alreadyPrepared) return
        val preparing = state.value[initial.id] ?: initial
        if (preparing.status != WorkspaceToolRunStatus.PREPARING) return
        update(preparing.copy(log = "Preparing tool dependencies...\n"))
        val cwd = joinToolPath(tool.rootPath, prepare.working_directory)
        val result = repository.executeCommand(
            id = workspaceId,
            command = prepare.command,
            cwd = cwd,
            timeoutMillis = PREPARE_TIMEOUT_MS,
        )
        val log = buildString { append(result.stdout); append(result.stderr) }
        val current = state.value[initial.id] ?: initial
        if (current.status != WorkspaceToolRunStatus.PREPARING) return
        update(current.copy(log = log))
        if (result.timedOut) error("Tool dependency setup timed out")
        if (result.exitCode != 0) error(log.trim().ifBlank { "Tool dependency setup failed" })
        repository.writeText(workspaceId, markerPath, fingerprint, overwrite = true)
    }

    private suspend fun fingerprint(workspaceId: String, path: String, ignoredPath: String? = null): String {
        suspend fun collect(directory: String): List<String> = repository.listFiles(
            workspaceId, me.rerere.workspace.WorkspaceStorageArea.FILES, directory,
        ).flatMap { entry ->
            if (ignoredPath != null && (entry.path == ignoredPath || entry.path.startsWith("${ignoredPath}/"))) {
                emptyList()
            } else if (entry.isDirectory) {
                collect(entry.path)
            } else {
                listOf("${entry.path}:${entry.sizeBytes}:${entry.updatedAt}")
            }
        }
        val material = collect(path)
            .filterNot { it.substringBefore(':').endsWith("/.rikkahub-prepared") }
            .sorted()
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun buildLaunchCommand(tool: WorkspaceTool, input: Map<String, String>, runId: String, webPort: Int?): String {
        val root = "/workspace/${tool.rootPath}"
        val output = "$root/output"
        val values = tool.manifest.inputs.associate { field -> field.name to (input[field.name] ?: field.default.orEmpty()) }
        val json = JsonInstant.encodeToString(
            buildJsonObject { values.forEach { (name, value) -> put(name, JsonPrimitive(value)) } },
        )
        val exports = buildList {
            add("RHK_WORKSPACE=/workspace")
            add("RHK_TOOL_DIR=$root")
            add("RHK_OUTPUT_DIR=$output")
            add("RHK_INPUT_JSON=$json")
            add("RHK_RUN_ID=$runId")
            if (tool.manifest.entry.mode == "web_server") {
                add("RHK_WORKSPACE_API_URL=${webServer.apiUrl(runId)}")
                add("PORT=" + (webPort ?: tool.manifest.entry.port ?: 0))
            }
            values.forEach { (name, value) -> add("RHK_INPUT_${name.uppercase()}=$value") }
        }.joinToString(" ") { "export ${it.substringBefore('=')}=${it.substringAfter('=').shellQuote()};" }
        val cwd = joinToolPath(tool.rootPath, tool.manifest.entry.working_directory)
        val command = tool.manifest.entry.command.orEmpty()
        val shellDollar = '$'
        val wrappedCommand = "cd -- " + ("/workspace/" + cwd).shellQuote() +
            " || exit 1; /bin/bash -lc " + command.shellQuote() +
            "; __rhk_exit=" + shellDollar + "?; printf '\n[RHK_TOOL_EXIT:" + runId + ":%s]\n' " + shellDollar + "__rhk_exit; exit " + shellDollar + "__rhk_exit"
        return "$exports mkdir -p ${output.shellQuote()}; $wrappedCommand"
    }

    private fun startPolling(root: String, run: WorkspaceToolRun) {
        val sessionId = run.sessionId ?: return
        pollJobs.remove(run.id)?.cancel()
        pollJobs[run.id] = appScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val screenResult = runCatching { terminalSessionManager.readAgentScreen(root, sessionId) }
                if (screenResult.isFailure) {
                    val current = state.value[run.id] ?: return@launch
                    update(
                        current.copy(
                            status = WorkspaceToolRunStatus.FAILED,
                            error = screenResult.exceptionOrNull()?.message ?: "Tool terminal ended unexpectedly",
                            resultFiles = collectResultFiles(current),
                        )
                    )
                    webServer.close(run.id)
                    ChatGenerationForegroundService.releaseTool(context, run.id)
                    pollJobs.remove(run.id)
                    return@launch
                }
                val screen = screenResult.getOrThrow()
                val current = state.value[run.id] ?: return@launch
                val preparationLog = run.log.trimEnd()
                val combinedLog = if (preparationLog.isBlank()) {
                    screen.screen
                } else {
                    preparationLog + "\n\n" + screen.screen
                }
                if (screen.session.finished || !screen.session.running) {
                    val exit = parseExit(screen.screen, run.id)
                    val files = collectResultFiles(current)
                    val failed = exit == null || exit != 0
                    update(
                        current.copy(
                            status = if (failed) WorkspaceToolRunStatus.FAILED else WorkspaceToolRunStatus.FINISHED,
                            log = cleanExitMarker(combinedLog, run.id),
                            exitCode = exit,
                            error = when {
                                exit == null -> "Tool ended without an exit status"
                                failed -> "Tool exited with code $exit"
                                else -> current.error
                            },
                            resultFiles = files,
                        )
                    )
                    webServer.close(run.id)
                    ChatGenerationForegroundService.releaseTool(context, run.id)
                    pollJobs.remove(run.id)
                    return@launch
                }
                update(current.copy(log = combinedLog))
            }
        }
    }

    private fun update(run: WorkspaceToolRun) {
        state.update { it + (run.id to run) }
    }

    private fun parseExit(screen: String, runId: String): Int? {
        val pattern = """\[RHK_TOOL_EXIT:${Regex.escape(runId)}:(\d+)\]"""
        return Regex(pattern).find(screen)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun cleanExitMarker(screen: String, runId: String): String {
        val pattern = """\n?\[RHK_TOOL_EXIT:${Regex.escape(runId)}:\d+\]\n?"""
        return screen.replace(Regex(pattern), "").trimEnd()
    }

   private suspend fun collectResultFiles(run: WorkspaceToolRun): List<me.rerere.rikkahub.data.repository.WorkspaceToolFile> {
       val path = run.tool.result.path?.trim()?.trim('/') ?: return emptyList()
       if (path.isBlank()) return emptyList()
        val resultRoot = joinToolPath("tools/" + run.tool.id, path)
       return runCatching {
           suspend fun collect(directory: String): List<me.rerere.rikkahub.data.repository.WorkspaceToolFile> =
               repository.listFiles(run.workspaceId, WorkspaceStorageArea.FILES, directory).flatMap { entry ->
                   if (entry.isDirectory) collect(entry.path) else listOf(
                       me.rerere.rikkahub.data.repository.WorkspaceToolFile(entry.path, entry.name, false, entry.sizeBytes),
                   )
               }
            collect(resultRoot)
       }.getOrDefault(emptyList())
   }

    private fun validateInput(manifest: WorkspaceToolManifest, input: Map<String, String>) {
        val known = manifest.inputs.map { it.name }.toSet()
        require(input.keys.all { it in known }) { "Unknown tool input" }
        manifest.inputs.forEach { field ->
            val value = input[field.name] ?: field.default
            if (field.required) require(!value.isNullOrBlank()) { "${field.label.ifBlank { field.name }} is required" }
            if (field.type == "number" && !value.isNullOrBlank()) require(value.toDoubleOrNull() != null) { "${field.name} must be a number" }
            if (field.type == "boolean" && !value.isNullOrBlank()) require(value == "true" || value == "false") { "${field.name} must be true or false" }
            if (field.type == "select" && !value.isNullOrBlank()) require(value in field.options) { "Invalid value for ${field.name}" }
        }
    }

    private fun joinToolPath(root: String, child: String): String {
        val normalized = child.trim().replace('\\', '/').trim('/')
        require(normalized.isBlank() || normalized.split('/').none { it == ".." }) { "Tool path escapes its directory" }
        return if (normalized.isBlank() || normalized == ".") root else "$root/$normalized"
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"

    private companion object {
        const val PREPARE_TIMEOUT_MS = 10 * 60 * 1000L
        const val POLL_INTERVAL_MS = 300L
    }
}
