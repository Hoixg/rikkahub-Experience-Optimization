package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration

private val Context.termuxDataStore by preferencesDataStore(name = "termux_prefs")

/**
 * DataStore-backed settings store for Termux-specific knobs, plus the app-wide per-turn
 * wall-clock budget (surfaced here because GitHub issue #5 requested it alongside the
 * Termux timeouts). Mirrors [me.rerere.rikkahub.browser.BrowserPreferences] in shape.
 *
 * The [init] block pushes persisted values into the runtime holders ([TermuxRuntime] and
 * [ToolRuntimeLimits]) immediately on construction so all non-suspend callers (TermuxTool,
 * GenerationHandler) read live values without needing a coroutine context.
 *
 * All read paths clamp on read; all write paths clamp on write. A value stored from an
 * older build that exceeds a tightened ceiling is silently clamped on the next read.
 */
class TermuxPreferences(private val context: Context) {

    private val store = context.termuxDataStore

    private val commandTimeoutKey = longPreferencesKey("command_timeout_ms")
    private val turnBudgetKey     = longPreferencesKey("turn_budget_ms")
    private val maxToolStepsKey   = intPreferencesKey("max_tool_steps")
    private val verifyTimeoutKey  = longPreferencesKey("verify_timeout_ms")
    private val workingDirKey     = stringPreferencesKey("working_dir")
    private val maxStdoutKey      = intPreferencesKey("max_stdout_bytes")
    private val maxStderrKey      = intPreferencesKey("max_stderr_bytes")
    private val aptWrapKey        = booleanPreferencesKey("apt_wrap_enabled")
    private val approvalRequiredKey = booleanPreferencesKey("approval_required")
    private val lastVerifiedMsKey = longPreferencesKey("last_verified_ms")

    init {
        // Runtime holders start with safe defaults and are restored asynchronously. The app
        // creates this singleton during startup; keeping the first DataStore read off the main
        // thread is more important than a tiny window before persisted settings are applied.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        TermuxIntegration.persister = { ms ->
            scope.launch { setLastVerifiedMs(ms) }
        }
        scope.launch {
            val initial = snapshot()
            applySnapshot(initial)
            TermuxIntegration.restoreVerifiedAt(initial.lastVerifiedMs)
        }

        listOf(
            commandTimeoutFlow() to { value: Long -> TermuxRuntime.commandTimeoutMs = value },
            turnBudgetFlow() to { value: Long -> ToolRuntimeLimits.turnBudgetMs = value },
            maxToolStepsFlow() to { value: Int -> ToolRuntimeLimits.maxToolSteps = value },
            verifyTimeoutFlow() to { value: Long -> TermuxRuntime.verifyTimeoutMs = value },
            defaultWorkingDirFlow() to { value: String -> TermuxRuntime.defaultWorkingDir = value },
            maxStdoutFlow() to { value: Int -> TermuxRuntime.maxStdoutBytes = value },
            maxStderrFlow() to { value: Int -> TermuxRuntime.maxStderrBytes = value },
            aptWrapEnabledFlow() to { value: Boolean -> TermuxRuntime.aptWrapEnabled = value },
            approvalRequiredFlow() to { value: Boolean -> TermuxRuntime.approvalRequired = value },
        ).forEach { (flow, apply) ->
            scope.launch {
                flow.distinctUntilChanged().collect { value ->
                    @Suppress("UNCHECKED_CAST")
                    (apply as (Any?) -> Unit)(value)
                }
            }
        }
    }

    private fun applySnapshot(config: TermuxRuntimeConfig) {
        TermuxRuntime.commandTimeoutMs = config.commandTimeoutMs
        TermuxRuntime.verifyTimeoutMs = config.verifyTimeoutMs
        TermuxRuntime.defaultWorkingDir = config.defaultWorkingDir
        TermuxRuntime.maxStdoutBytes = config.maxStdoutBytes
        TermuxRuntime.maxStderrBytes = config.maxStderrBytes
        TermuxRuntime.aptWrapEnabled = config.aptWrapEnabled
        TermuxRuntime.approvalRequired = config.approvalRequired
        ToolRuntimeLimits.turnBudgetMs = config.turnBudgetMs
        ToolRuntimeLimits.maxToolSteps = config.maxToolSteps
    }

    // --- Flow accessors -------------------------------------------------------------------

    fun commandTimeoutFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampCommandTimeoutMs(
            prefs[commandTimeoutKey] ?: TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS
        )
    }

    fun turnBudgetFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampTurnBudgetMs(
            prefs[turnBudgetKey] ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS
        )
    }

    fun maxToolStepsFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxToolSteps(
            prefs[maxToolStepsKey] ?: TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
        )
    }

    fun verifyTimeoutFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampVerifyTimeoutMs(
            prefs[verifyTimeoutKey] ?: TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS
        )
    }

    fun defaultWorkingDirFlow(): Flow<String> = store.data.map { prefs ->
        TermuxDefaults.clampWorkingDir(
            prefs[workingDirKey] ?: TermuxDefaults.DEFAULT_WORKING_DIR
        )
    }

    fun maxStdoutFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxStdout(
            prefs[maxStdoutKey] ?: TermuxDefaults.DEFAULT_MAX_STDOUT
        )
    }

    fun maxStderrFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxStderr(
            prefs[maxStderrKey] ?: TermuxDefaults.DEFAULT_MAX_STDERR
        )
    }

    fun aptWrapEnabledFlow(): Flow<Boolean> = store.data.map { prefs ->
        prefs[aptWrapKey] ?: TermuxDefaults.DEFAULT_APT_WRAP_ENABLED
    }

    fun approvalRequiredFlow(): Flow<Boolean> = store.data.map { prefs ->
        prefs[approvalRequiredKey] ?: TermuxDefaults.DEFAULT_APPROVAL_REQUIRED
    }

    // --- Suspend writers (clamped before persist) -----------------------------------------

    suspend fun setCommandTimeoutMs(ms: Long) {
        store.edit { it[commandTimeoutKey] = TermuxDefaults.clampCommandTimeoutMs(ms) }
    }

    suspend fun setTurnBudgetMs(ms: Long) {
        store.edit { it[turnBudgetKey] = TermuxDefaults.clampTurnBudgetMs(ms) }
    }

    suspend fun setMaxToolSteps(steps: Int) {
        store.edit { it[maxToolStepsKey] = TermuxDefaults.clampMaxToolSteps(steps) }
    }

    suspend fun setVerifyTimeoutMs(ms: Long) {
        store.edit { it[verifyTimeoutKey] = TermuxDefaults.clampVerifyTimeoutMs(ms) }
    }

    suspend fun setDefaultWorkingDir(dir: String) {
        store.edit { it[workingDirKey] = TermuxDefaults.clampWorkingDir(dir) }
    }

    suspend fun setMaxStdoutBytes(bytes: Int) {
        store.edit { it[maxStdoutKey] = TermuxDefaults.clampMaxStdout(bytes) }
    }

    suspend fun setMaxStderrBytes(bytes: Int) {
        store.edit { it[maxStderrKey] = TermuxDefaults.clampMaxStderr(bytes) }
    }

    suspend fun setAptWrapEnabled(enabled: Boolean) {
        store.edit { it[aptWrapKey] = enabled }
    }

    suspend fun setApprovalRequired(required: Boolean) {
        store.edit { it[approvalRequiredKey] = required }
    }

    suspend fun setLastVerifiedMs(ms: Long) {
        store.edit { it[lastVerifiedMsKey] = ms }
    }

    /**
     * One-shot suspend snapshot for callers that need all fields at once (e.g. the VM's
     * combined state flow). Fields are clamped on read, same as the individual flow accessors.
     */
    suspend fun snapshot(): TermuxRuntimeConfig {
        val prefs = store.data.first()
        return TermuxRuntimeConfig(
            commandTimeoutMs   = TermuxDefaults.clampCommandTimeoutMs(prefs[commandTimeoutKey] ?: TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS),
            turnBudgetMs       = TermuxDefaults.clampTurnBudgetMs(prefs[turnBudgetKey]         ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS),
            maxToolSteps       = TermuxDefaults.clampMaxToolSteps(prefs[maxToolStepsKey]        ?: TermuxDefaults.DEFAULT_MAX_TOOL_STEPS),
            verifyTimeoutMs    = TermuxDefaults.clampVerifyTimeoutMs(prefs[verifyTimeoutKey]    ?: TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS),
            defaultWorkingDir  = TermuxDefaults.clampWorkingDir(prefs[workingDirKey]            ?: TermuxDefaults.DEFAULT_WORKING_DIR),
            maxStdoutBytes     = TermuxDefaults.clampMaxStdout(prefs[maxStdoutKey]              ?: TermuxDefaults.DEFAULT_MAX_STDOUT),
            maxStderrBytes     = TermuxDefaults.clampMaxStderr(prefs[maxStderrKey]              ?: TermuxDefaults.DEFAULT_MAX_STDERR),
            aptWrapEnabled     = prefs[aptWrapKey]                                              ?: TermuxDefaults.DEFAULT_APT_WRAP_ENABLED,
            approvalRequired  = prefs[approvalRequiredKey]                                    ?: TermuxDefaults.DEFAULT_APPROVAL_REQUIRED,
            lastVerifiedMs     = prefs[lastVerifiedMsKey]                                        ?: 0L,
        )
    }

    fun snapshotBlocking(): TermuxRuntimeConfig = runBlocking { snapshot() }
}

/**
 * Immutable snapshot of all Termux preferences, used by the ViewModel to expose a single
 * combined state flow instead of separate ones.
 */
data class TermuxRuntimeConfig(
    val commandTimeoutMs: Long,
    val turnBudgetMs: Long,
    val maxToolSteps: Int,
    val verifyTimeoutMs: Long,
    val defaultWorkingDir: String,
    val maxStdoutBytes: Int,
    val maxStderrBytes: Int,
    val aptWrapEnabled: Boolean,
    val approvalRequired: Boolean = TermuxDefaults.DEFAULT_APPROVAL_REQUIRED,
    val lastVerifiedMs: Long = 0L,
)
