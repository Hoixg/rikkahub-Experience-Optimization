package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val RUN_PATH = "com.termux.RUN_COMMAND_PATH"
private const val RUN_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val RUN_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val RUN_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val RUN_RESULT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val RESULT_BUNDLE = "result"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT = "exitCode"
private const val RESULT_ERROR = "err"
private const val RESULT_MESSAGE = "errmsg"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_TIMEOUT_SECONDS = 600
private const val DEFAULT_ROWS = 32
private const val DEFAULT_COLS = 120
private const val MAX_OUTPUT_CHARS = 32_000
private const val MAX_SESSIONS = 8
private const val DEFAULT_SESSION_LINES = 200
private const val SESSION_SETTLE_MS = 600L
private const val SESSION_POLL_INTERVAL_MS = 200L
private const val SESSION_PREFIX = "rikkahub-"
private const val TMUX_OPERATION_TIMEOUT_MS = 8_000L
private const val TMUX_INSTALL_TIMEOUT_MS = 180_000L

private fun commandTimeoutMs(seconds: Int?): Long = when {
    seconds == null || seconds == 0 -> DEFAULT_TIMEOUT_MS
    else -> seconds.toLong().coerceIn(1L, MAX_TIMEOUT_SECONDS.toLong()) * 1_000L
}

internal fun buildMarkerWrappedArgv(
    bashPath: String,
    executable: String,
    arguments: Array<String>,
    marker: String,
): Pair<String, Array<String>> {
    // Keep the original executable and arguments as literal argv entries while adding a
    // completion marker after the child exits. This distinguishes Termux's early ACK callback
    // from the real result callback.
    val script = "\"\$0\" \"\$@\"; ec=\$?; printf '\\n%s' '$marker'; exit \$ec"
    return bashPath to arrayOf("-c", script, executable, *arguments)
}

internal fun isTerminalResult(err: Int, exitCode: Int, stdout: String, marker: String): Boolean {
    if (err != -1 || exitCode != 0) return true
    return stdout.trimEnd().endsWith(marker)
}

internal fun stripTerminationMarker(stdout: String, marker: String): String {
    val trimmed = stdout.trimEnd()
    val suffix = "\n$marker"
    return if (trimmed.endsWith(suffix)) trimmed.removeSuffix(suffix) else stdout
}

private fun termuxResult(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

private fun termuxError(code: String, detail: String, recovery: String? = null) = termuxResult(buildJsonObject {
    put("error", code)
    put("detail", detail)
    recovery?.let { put("recovery", it) }
})

private fun termuxPreflight(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            context, "com.termux.permission.RUN_COMMAND"
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) "Termux RUN_COMMAND permission is not granted." else null
}.getOrElse {
    "Termux is not installed or is hidden by Android package visibility."
}

private suspend fun termuxRun(
    context: Context,
    executable: String,
    arguments: Array<String>,
    workdir: String = TERMUX_HOME,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
): TermuxCapture {
    val deferred = CompletableDeferred<Bundle>()
    val action = "${context.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    val marker = UUID.randomUUID().toString()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val result = intent.getBundleExtra(RESULT_BUNDLE) ?: intent.extras ?: return
            val hasResultFields = RESULT_ERROR in result.keySet() ||
                RESULT_EXIT in result.keySet() || RESULT_STDOUT in result.keySet() ||
                RESULT_STDERR in result.keySet() || RESULT_MESSAGE in result.keySet()
            if (!hasResultFields) return
            if (!isTerminalResult(
                    err = result.getInt(RESULT_ERROR, -1),
                    exitCode = result.getInt(RESULT_EXIT, 0),
                    stdout = result.getString(RESULT_STDOUT).orEmpty(),
                    marker = marker,
                )
            ) return
            if (deferred.isActive) deferred.complete(result)
        }
    }
    val filter = IntentFilter(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
    val pendingIntent = runCatching {
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }.getOrElse {
        runCatching { context.unregisterReceiver(receiver) }
        return TermuxCapture.Error(it.message ?: "Unable to create result callback.")
    }
    val (wrappedExecutable, wrappedArguments) = buildMarkerWrappedArgv(
        bashPath = TERMUX_BASH,
        executable = executable,
        arguments = arguments,
        marker = marker,
    )
    val command = Intent().apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        this.action = RUN_COMMAND_ACTION
        putExtra(RUN_PATH, wrappedExecutable)
        putExtra(RUN_ARGS, wrappedArguments)
        putExtra(RUN_WORKDIR, workdir)
        putExtra(RUN_BACKGROUND, true)
        putExtra(RUN_RESULT, pendingIntent)
    }
    return try {
        context.startService(command)
        val bundle = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 600_000L)) { deferred.await() }
            ?: return TermuxCapture.Timeout
        val errorCode = bundle.getInt(RESULT_ERROR, -1)
        if (errorCode != -1) {
            TermuxCapture.Error(bundle.getString(RESULT_MESSAGE).orEmpty().ifBlank { "Termux rejected the command ($errorCode)." })
        } else {
            TermuxCapture.Success(
                stdout = stripTerminationMarker(bundle.getString(RESULT_STDOUT).orEmpty(), marker),
                stderr = bundle.getString(RESULT_STDERR).orEmpty(),
                exitCode = bundle.getInt(RESULT_EXIT, 0),
            )
        }
    } catch (error: SecurityException) {
        TermuxCapture.Error("Termux denied RUN_COMMAND: ${error.message.orEmpty()}")
    } catch (error: Throwable) {
        TermuxCapture.Error(error.message ?: error::class.simpleName.orEmpty())
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { pendingIntent.cancel() }
    }
}

private sealed class TermuxCapture {
    data class Success(val stdout: String, val stderr: String, val exitCode: Int) : TermuxCapture()
    data object Timeout : TermuxCapture()
    data class Error(val message: String) : TermuxCapture()
}

private fun captureJson(capture: TermuxCapture, command: String? = null) = when (capture) {
    is TermuxCapture.Success -> termuxResult(buildJsonObject {
        put("success", capture.exitCode == 0)
        put("exit_code", capture.exitCode)
        put("stdout", capture.stdout.takeLast(MAX_OUTPUT_CHARS))
        put("stderr", capture.stderr.takeLast(MAX_OUTPUT_CHARS))
        command?.let { put("command", it) }
    })
    TermuxCapture.Timeout -> termuxError("timeout", "Termux command timed out.")
    is TermuxCapture.Error -> termuxError("termux_error", capture.message)
}

fun termuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = "Execute a shell command in the installed Termux app and return stdout, stderr, and exit_code. Requires Termux allow-external-apps=true and user approval.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("command", buildJsonObject { put("type", "string") })
        put("executable", buildJsonObject { put("type", "string") })
        put("arguments", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("working_dir", buildJsonObject { put("type", "string") })
        put("timeout_seconds", buildJsonObject { put("type", "integer") })
        put("background", buildJsonObject { put("type", "boolean") })
        put("interactive", buildJsonObject { put("type", "boolean") })
    }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val command = obj["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val executable = obj["executable"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (command != null && executable != null) {
            return@Tool termuxError("invalid_arguments", "command and executable are mutually exclusive.")
        }
        if (command == null && executable == null) {
            return@Tool termuxError("missing_command", "command or executable is required.")
        }
        val arguments = obj["arguments"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toTypedArray() ?: emptyArray()
        val workingDir = obj["working_dir"]?.jsonPrimitive?.contentOrNull ?: TERMUX_HOME
        val timeout = commandTimeoutMs(obj["timeout_seconds"]?.jsonPrimitive?.intOrNull)
        val background = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false
        val interactive = obj["interactive"]?.jsonPrimitive?.booleanOrNull ?: false
        val executablePath = executable ?: TERMUX_BASH
        val executableArgs = if (command != null) arrayOf("-lc", command) else arguments
        if (interactive) {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                this.action = RUN_COMMAND_ACTION
                putExtra(RUN_PATH, executablePath)
                putExtra(RUN_ARGS, executableArgs)
                putExtra(RUN_WORKDIR, workingDir)
                putExtra(RUN_BACKGROUND, false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            return@Tool try {
                context.startService(intent)
                termuxResult(buildJsonObject { put("success", true); put("mode", "interactive"); put("output_captured", false) })
            } catch (error: Throwable) {
                termuxError("dispatch_failed", error.message ?: "Unable to open Termux session.")
            }
        }
        val resolvedArgs = if (background && command != null) {
            arrayOf("-lc", "nohup $TERMUX_BASH -lc ${shellQuote(command)} >/dev/null 2>&1 </dev/null & echo \$!")
        } else if (background) {
            arrayOf("-lc", "nohup ${shellQuote(executablePath)} ${arguments.joinToString(" ") { shellQuote(it) }} >/dev/null 2>&1 </dev/null & echo \$!")
        } else executableArgs
        val dispatchExecutable = if (background) TERMUX_BASH else executablePath
        captureJson(termuxRun(context, dispatchExecutable, resolvedArgs, workingDir, timeout), command)
    },
)

private fun sessionName(raw: String?): String {
    val safe = raw.orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').take(24)
    val suffix = UUID.randomUUID().toString().take(8)
    return "${SESSION_PREFIX}${safe.ifBlank { "agent" }}-$suffix"
}

private fun sessionIdSchema(properties: kotlinx.serialization.json.JsonObjectBuilder) {
    properties.put("session_id", buildJsonObject { put("type", "string") })
}

private suspend fun tmuxRun(context: Context, command: String): TermuxCapture =
    termuxRun(context, TERMUX_BASH, arrayOf("-lc", command), TERMUX_HOME, TMUX_OPERATION_TIMEOUT_MS)

private suspend fun ensureTmux(context: Context): Boolean {
    val installed = tmuxRun(context, "command -v tmux")
    if (installed is TermuxCapture.Success && installed.exitCode == 0 && installed.stdout.isNotBlank()) return true
    val install = termuxRun(
        context,
        TERMUX_BASH,
        arrayOf("-lc", "export DEBIAN_FRONTEND=noninteractive; pkg install -y tmux"),
        TERMUX_HOME,
        TMUX_INSTALL_TIMEOUT_MS,
    )
    return install is TermuxCapture.Success && install.exitCode == 0
}

private fun waitForMatches(screen: String, pattern: String): Boolean =
    runCatching { Regex(pattern).containsMatchIn(screen) }.getOrElse { screen.contains(pattern) }

private suspend fun readSessionUntilSettled(
    context: Context,
    session: String,
    lines: Int,
    waitFor: String?,
    timeoutMs: Long,
): TermuxCapture {
    val started = android.os.SystemClock.elapsedRealtime()
    var previous: String? = null
    var stableSince = started
    while (true) {
        val quotedSession = shellQuote(session)
        val screen = tmuxRun(context, "tmux capture-pane -p -t $quotedSession -S -${lines.coerceIn(1, 2_000)}")
        if (screen !is TermuxCapture.Success) return screen
        val now = android.os.SystemClock.elapsedRealtime()
        if (!waitFor.isNullOrBlank() && waitForMatches(screen.stdout, waitFor)) return screen.copy(stderr = "MATCHED")
        if (screen.stdout != previous) {
            previous = screen.stdout
            stableSince = now
        }
        if (now - stableSince >= SESSION_SETTLE_MS) return screen.copy(stderr = "SETTLED")
        if (now - started >= timeoutMs) return screen.copy(stderr = "TIMEOUT")
        delay(SESSION_POLL_INTERVAL_MS)
    }
}


fun termuxSessionStartTool(context: Context): Tool = Tool(
    name = "termux_session_start",
    description = "Start a persistent tmux-backed interactive Termux session. Use termux_session_send/read to control it. Auto-installs tmux on first use.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("name", buildJsonObject { put("type", "string") })
        put("command", buildJsonObject { put("type", "string") })
        put("cols", buildJsonObject { put("type", "integer") })
        put("rows", buildJsonObject { put("type", "integer") })
    }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val name = sessionName(obj["name"]?.jsonPrimitive?.contentOrNull)
        if (!ensureTmux(context)) {
            return@Tool termuxError("tmux_unavailable", "tmux is not installed and could not be installed.", "Open Termux and run: pkg install -y tmux")
        }
        val existing = tmuxRun(context, "tmux list-sessions -F '#{session_name}'")
            .let { capture -> (capture as? TermuxCapture.Success)?.stdout?.lineSequence()?.count { it.startsWith(SESSION_PREFIX) } ?: 0 }
        if (existing >= MAX_SESSIONS) {
            return@Tool termuxError("too_many_sessions", "Maximum $MAX_SESSIONS agent sessions reached. Kill an old session first.")
        }
        val cols = (obj["cols"]?.jsonPrimitive?.intOrNull ?: DEFAULT_COLS).coerceIn(40, 240)
        val rows = (obj["rows"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROWS).coerceIn(8, 120)
        val startCommand = "tmux new-session -d -s ${shellQuote(name)} -x $cols -y $rows"
        val started = tmuxRun(context, startCommand)
        if (started !is TermuxCapture.Success || started.exitCode != 0) return@Tool captureJson(started, startCommand)
        val initial = obj["command"]?.jsonPrimitive?.contentOrNull
        if (!initial.isNullOrBlank()) {
            val sent = tmuxRun(context, "tmux send-keys -t ${shellQuote(name)} -l ${shellQuote(initial)} && tmux send-keys -t ${shellQuote(name)} Enter")
            if (isMissingSession(sent)) return@Tool sessionGoneError(context, name)
            if (sent !is TermuxCapture.Success || sent.exitCode != 0) return@Tool captureJson(sent)
        }
        val screen = readSessionUntilSettled(
            context, name, DEFAULT_SESSION_LINES,
            null,
            DEFAULT_TIMEOUT_MS,
        )
        if (isMissingSession(screen)) return@Tool sessionGoneError(context, name)
        when (screen) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject {
                put("success", screen.exitCode == 0); put("session_id", name)
                put("screen", screen.stdout.trimEnd().takeLast(MAX_OUTPUT_CHARS))
            })
            else -> termuxResult(buildJsonObject {
                put("success", true); put("session_id", name); put("screen", "")
                put("note", "Session created, but the initial screen read failed. Use termux_session_read to try again.")
            })
        }
    },
)

fun termuxSessionSendTool(context: Context): Tool = Tool(
    name = "termux_session_send",
    description = "Send text and control keys to a persistent Termux tmux session and return its screen. Supports wait_for and timeout_seconds.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        sessionIdSchema(this)
        put("input", buildJsonObject { put("type", "string") })
        put("enter", buildJsonObject { put("type", "boolean") })
        put("keys", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
        put("wait_for", buildJsonObject { put("type", "string") })
        put("timeout_seconds", buildJsonObject { put("type", "integer") })
    }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val session = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        val text = obj["input"]?.jsonPrimitive?.contentOrNull
        val keys = obj["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val waitFor = obj["wait_for"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = commandTimeoutMs(obj["timeout_seconds"]?.jsonPrimitive?.intOrNull)
        val sends = buildList {
            text?.takeIf { it.isNotEmpty() }?.let { add("tmux send-keys -t ${shellQuote(session)} -l ${shellQuote(it)}") }
            keys.forEach { add("tmux send-keys -t ${shellQuote(session)} ${shellQuote(tmuxKey(it))}") }
            if (obj["enter"]?.jsonPrimitive?.booleanOrNull ?: true) add("tmux send-keys -t ${shellQuote(session)} Enter")
        }
        if (sends.isEmpty()) return@Tool termuxError("missing_input", "Provide input or keys.")
        val sent = tmuxRun(context, sends.joinToString(" && "))
        if (isMissingSession(sent)) return@Tool sessionGoneError(context, session)
        if (sent !is TermuxCapture.Success || sent.exitCode != 0) return@Tool captureJson(sent)
        val screen = readSessionUntilSettled(context, session, DEFAULT_SESSION_LINES, waitFor, timeoutMs)
        if (isMissingSession(screen)) return@Tool sessionGoneError(context, session)
        when (screen) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject {
                put("success", screen.exitCode == 0); put("screen", screen.stdout.trimEnd().takeLast(MAX_OUTPUT_CHARS))
                put("matched_wait_for", !waitFor.isNullOrBlank() && screen.stderr == "MATCHED")
                put("timed_out", !waitFor.isNullOrBlank() && screen.stderr == "TIMEOUT")
            })
            else -> captureJson(screen)
        }
    },
)

fun termuxSessionReadTool(context: Context): Tool = Tool(
    name = "termux_session_read",
    description = "Read a persistent Termux tmux session. Optional wait_for, timeout_seconds, and lines control polling and scrollback.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        sessionIdSchema(this)
        put("wait_for", buildJsonObject { put("type", "string") })
        put("timeout_seconds", buildJsonObject { put("type", "integer") })
        put("lines", buildJsonObject { put("type", "integer") })
    }) },
    needsApproval = { false },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val session = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        val waitFor = obj["wait_for"]?.jsonPrimitive?.contentOrNull
        val lines = (obj["lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SESSION_LINES).coerceIn(1, 2_000)
        val read = if (waitFor.isNullOrBlank()) tmuxRun(context, "tmux capture-pane -p -t ${shellQuote(session)} -S -$lines")
        else readSessionUntilSettled(context, session, lines, waitFor, commandTimeoutMs(obj["timeout_seconds"]?.jsonPrimitive?.intOrNull))
        if (isMissingSession(read)) return@Tool sessionGoneError(context, session)
        when (read) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject {
                put("success", read.exitCode == 0); put("screen", read.stdout.trimEnd().takeLast(MAX_OUTPUT_CHARS))
                put("matched_wait_for", !waitFor.isNullOrBlank() && read.stderr == "MATCHED")
                put("timed_out", !waitFor.isNullOrBlank() && read.stderr == "TIMEOUT")
            })
            else -> captureJson(read)
        }
    },
)

fun termuxSessionKillTool(context: Context): Tool = Tool(
    name = "termux_session_kill",
    description = "Terminate a persistent Termux tmux session opened by the agent.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { sessionIdSchema(this) }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        val killed = tmuxRun(context, "tmux kill-session -t ${shellQuote(session)}")
        if (isMissingSession(killed)) return@Tool sessionGoneError(context, session)
        if (killed !is TermuxCapture.Success || killed.exitCode != 0) return@Tool captureJson(killed)
        termuxResult(buildJsonObject { put("success", true); put("killed", session) })
    },
)

fun termuxSessionListTool(context: Context): Tool = Tool(
    name = "termux_session_list",
    description = "List persistent Termux tmux sessions opened by the agent.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    needsApproval = { false },
    execute = {
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val result = tmuxRun(context, "tmux list-sessions -F '#{session_name}|#{session_created}|#{session_activity}'")
        when (result) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject {
                put("success", true)
                put("sessions", buildJsonArray {
                    result.stdout.lineSequence().filter { it.startsWith(SESSION_PREFIX) && it.isNotBlank() }.forEach { line ->
                        val parts = line.split('|', limit = 3)
                        add(buildJsonObject { put("session_id", parts.getOrNull(0).orEmpty()); put("created", parts.getOrNull(1).orEmpty()); put("last_activity", parts.getOrNull(2).orEmpty()) })
                    }
                })
            })
            else -> if (isNoTmuxServer(result)) termuxResult(buildJsonObject { put("success", true); put("sessions", buildJsonArray {}) }) else captureJson(result)
        }
    },
)

private fun isMissingSession(capture: TermuxCapture): Boolean = when (capture) {
    is TermuxCapture.Success -> capture.exitCode != 0 && (capture.stderr.contains("can't find session", true) || capture.stderr.contains("no server running", true) || capture.stderr.contains("session not found", true) || capture.stderr.contains("no current session", true))
    is TermuxCapture.Error -> capture.message.contains("session", true) || capture.message.contains("server", true)
    TermuxCapture.Timeout -> false
}

private fun isNoTmuxServer(capture: TermuxCapture): Boolean = capture is TermuxCapture.Success && capture.exitCode != 0 && capture.stderr.contains("no server running", true)

private suspend fun sessionGoneError(context: Context, session: String): List<UIMessagePart> {
    val live = tmuxRun(context, "tmux list-sessions -F '#{session_name}'")
    val names = (live as? TermuxCapture.Success)?.stdout?.lineSequence()?.filter { it.startsWith(SESSION_PREFIX) && it.isNotBlank() }?.joinToString().orEmpty().ifBlank { "none" }
    return termuxError("session_not_found", "Session '$session' is gone. Live agent sessions: $names. Start a new session with termux_session_start.")
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun tmuxKey(value: String): String = when (value.trim().lowercase()) {
    "enter", "return" -> "Enter"
    "tab" -> "Tab"
    "esc", "escape" -> "Escape"
    "up" -> "Up"
    "down" -> "Down"
    "left" -> "Left"
    "right" -> "Right"
    "c-c" -> "C-c"
    "c-d" -> "C-d"
    "c-z" -> "C-z"
    else -> value
}
