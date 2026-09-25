package me.rerere.rikkahub.service.scheduled

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelType
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.ScheduledJobDao
import me.rerere.rikkahub.data.db.entity.ScheduledJobCatchup
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobMode
import me.rerere.rikkahub.data.db.entity.ScheduledJobOutcome
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

const val SCHEDULED_JOB_RESULT_CHANNEL_ID = "scheduled_task_result"
const val SCHEDULED_JOB_PROGRESS_CHANNEL_ID = "scheduled_task_progress"

data class ScheduledToolDescriptor(
    val name: String,
    val description: String,
    val parametersJson: String?,
)

class ScheduledJobManager(
    private val context: Application,
    private val appScope: AppScope,
    private val dao: ScheduledJobDao,
    private val chatService: ChatService,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val json: Json,
) {
    private val lock = Mutex()
    private val runningJobs = ConcurrentHashMap.newKeySet<String>()
    private val scheduler = ScheduledJobScheduler(context)
    private val directRunner = DirectModeActionRunner(json)
    private val startupReconciled = CompletableDeferred<Unit>()

    init {
        appScope.launch(Dispatchers.IO) {
            eventBus.events.collect { event ->
                if (event is AppEvent.ChatTurnFinished) {
                    refreshRunForConversation(event.conversationId.toString())
                }
            }
        }
        appScope.launch(Dispatchers.IO) {
            try {
                reconcile(recoverInterrupted = true, applyCatchup = true)
                dao.getWaitingApprovalRuns().forEach { run ->
                    run.conversationId?.let { refreshRunForConversation(it) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Startup reconciliation failed", error)
            } finally {
                startupReconciled.complete(Unit)
            }
        }
    }

    fun observeJobs(): Flow<List<ScheduledJobEntity>> = dao.observeJobs()
    fun observeRuns(jobId: String): Flow<List<ScheduledJobRunEntity>> = dao.observeRuns(jobId)
    suspend fun getJob(id: String): ScheduledJobEntity? = dao.getJob(id)
    suspend fun getLatestRun(jobId: String): ScheduledJobRunEntity? = dao.getLatestRun(jobId)

    suspend fun getDirectTools(assistantId: String): List<ScheduledToolDescriptor> {
        val assistant = runCatching { settingsStore.settingsFlowRaw.first().getAssistantById(Uuid.parse(assistantId)) }
            .getOrNull() ?: return emptyList()
        return directTools(assistant.localTools).map { tool ->
            ScheduledToolDescriptor(
                name = tool.name,
                description = tool.description,
                parametersJson = tool.parameters()?.let { json.encodeToString(it) },
            )
        }
    }

    suspend fun save(job: ScheduledJobEntity) = lock.withLock {
        validate(job)
        val now = System.currentTimeMillis()
        val old = dao.getJob(job.id)
        val zone = job.timezone?.takeIf(String::isNotBlank)?.also { ZoneId.of(it) }
        val saved = job.copy(
            timezone = zone,
            runsSoFar = old?.runsSoFar ?: job.runsSoFar,
            lastRunAtMs = old?.lastRunAtMs ?: job.lastRunAtMs,
            createdAtMs = old?.createdAtMs ?: now,
            updatedAtMs = now,
            nextRunAtMs = null,
        )
        val next = if (saved.enabled) ScheduledJobTime.nextRunMs(saved, now) else null
        require(!saved.enabled || next != null) { "This schedule has no future execution" }
        dao.upsertJob(saved.copy(nextRunAtMs = next))
        scheduler.cancel(saved.id)
        if (next != null) scheduler.schedule(saved, next, now)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val job = dao.getJob(id) ?: return
        save(job.copy(enabled = enabled))
    }

    suspend fun delete(id: String) = lock.withLock {
        val job = dao.getJob(id) ?: return@withLock
        scheduler.cancel(id)
        dao.getActiveRun(id)?.conversationId?.let { rawId ->
            runCatching { chatService.stopGeneration(Uuid.parse(rawId)) }
        }
        dao.deleteRuns(id)
        dao.deleteJob(job)
    }

    suspend fun runNow(id: String): Boolean {
        val job = dao.getJob(id) ?: return false
        if (dao.getActiveRun(id) != null) return false
        scheduler.triggerNow(job.id)
        return true
    }

    suspend fun reconcile(recoverInterrupted: Boolean = false, applyCatchup: Boolean = false) {
        val now = System.currentTimeMillis()
        if (recoverInterrupted) {
            dao.getUnfinishedRuns().filterNot { runningJobs.contains(it.jobId) }.forEach { run ->
                finishRun(run.id, ScheduledJobOutcome.INTERRUPTED, "Worker process stopped before completing this run", null)
            }
        }
        dao.getEnabledJobs().forEach { original ->
            val job = dao.getJob(original.id) ?: return@forEach
            val onceHandledByCatchup = applyCatchup && applyCatchupPlan(job, now)
            val latest = dao.getJob(job.id) ?: return@forEach
            if (onceHandledByCatchup) {
                if (latest.enabled) dao.upsertJob(latest.copy(nextRunAtMs = null))
                return@forEach
            }
            // A regular worker may already be inside a long AI request. Replacing its
            // unique work here would cancel that request during an app resume/restart.
            val active = dao.getActiveRun(latest.id)
            if (runningJobs.contains(latest.id) ||
                (active != null && !active.manual && active.outcome == "running")
            ) return@forEach
            val next = ScheduledJobTime.nextRunMs(latest, now)
            if (next == null) {
                val terminal = latest.copy(enabled = false, nextRunAtMs = null, updatedAtMs = now)
                dao.upsertJob(terminal)
                scheduler.cancel(terminal.id)
            } else {
                val scheduled = latest.copy(nextRunAtMs = next)
                dao.upsertJob(scheduled)
                scheduler.schedule(scheduled, next, now)
            }
        }
    }

    private suspend fun applyCatchupPlan(job: ScheduledJobEntity, now: Long): Boolean {
        val plan = CatchupPlanner.plan(job, job.lastRunAtMs, now)
        plan.skippedSlotsMs.forEach { slot ->
            if (dao.getRunForSchedule(job.id, slot) == null) {
                val skipped = newRun(job, slot, now, ScheduledJobOutcome.SKIPPED_CATCHUP, manual = false)
                dao.insertScheduledRunIfMissing(skipped.copy(finishedAtMs = now))
            }
        }
        plan.fireSlotsMs.forEachIndexed { index, slot ->
            if (dao.getRunForSchedule(job.id, slot) == null) {
                scheduler.enqueueCatchup(job, slot, index * CatchupPlanner.FIRE_ALL_STAGGER_MS)
            }
        }
        val missedOnce = job.scheduleType == ScheduledJobType.ONCE &&
            job.lastRunAtMs == null && job.atUnixMs?.let { it < now } == true
        if (missedOnce) {
            val existing = job.atUnixMs?.let { dao.getRunForSchedule(job.id, it) }
            if (plan.fireSlotsMs.isEmpty() || existing?.finishedAtMs != null) {
                dao.upsertJob(job.copy(enabled = false, lastRunAtMs = now, nextRunAtMs = null, updatedAtMs = now))
            }
        }
        return missedOnce
    }

    suspend fun execute(
        jobId: String,
        scheduledAtMs: Long,
        manual: Boolean,
        catchup: Boolean,
    ) {
        startupReconciled.await()
        val now = System.currentTimeMillis()
        if (!runningJobs.add(jobId)) {
            recordConcurrentSkip(jobId, scheduledAtMs, manual, now)
            return
        }
        var run: ScheduledJobRunEntity? = null
        var naturalRunStarted = false
        try {
            val job = dao.getJob(jobId) ?: return
            if (!manual && !job.enabled) return
            if (!manual && !catchup && job.nextRunAtMs != null && job.nextRunAtMs != scheduledAtMs) return
            if (!manual && job.startAtUnixMs != null && now < job.startAtUnixMs) {
                val next = ScheduledJobTime.nextRunMs(job, now)
                if (next != null) scheduler.schedule(job.copy(nextRunAtMs = next), next, now)
                return
            }
            if (!manual && (job.endAtUnixMs?.let { now > it } == true ||
                    job.maxRuns?.let { job.runsSoFar >= it } == true)) {
                dao.upsertJob(job.copy(enabled = false, nextRunAtMs = null, updatedAtMs = now))
                return
            }
            if (!manual && dao.getRunForSchedule(jobId, scheduledAtMs) != null) return
            if (dao.getActiveRun(jobId) != null) {
                recordConcurrentSkip(jobId, scheduledAtMs, manual, now)
                return
            }
            val slot = if (manual) System.currentTimeMillis() else scheduledAtMs
            run = newRun(job, slot, now, "running", manual)
            if (dao.insertScheduledRunIfMissing(run!!) == -1L) return
            naturalRunStarted = !manual
            when (job.mode) {
                ScheduledJobMode.REMINDER -> executeReminder(job, run!!)
                ScheduledJobMode.DIRECT -> executeDirect(job, run!!)
                ScheduledJobMode.LLM -> executeLlm(job, run!!)
                else -> finishRun(run!!.id, ScheduledJobOutcome.FAILED, "Unknown scheduled job mode", null)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                run?.conversationId?.let { runCatching { chatService.stopGeneration(Uuid.parse(it)) } }
                run?.let { finishRun(it.id, ScheduledJobOutcome.INTERRUPTED, "Run cancelled", null) }
            }
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Scheduled job $jobId failed", error)
            run?.let { finishRun(it.id, ScheduledJobOutcome.FAILED, error.message ?: error::class.simpleName, null) }
        } finally {
            if (naturalRunStarted) rescheduleAfterNaturalRun(jobId)
            else if (!manual && !catchup) restoreRegularSchedule(jobId)
            runningJobs.remove(jobId)
        }
    }

    private suspend fun executeReminder(job: ScheduledJobEntity, run: ScheduledJobRunEntity) {
        val shown = showReminder(job, run)
        finishRun(
            run.id,
            if (shown) ScheduledJobOutcome.SUCCESS else ScheduledJobOutcome.FAILED,
            if (shown) null else "Notification could not be delivered",
            job.notificationBody,
            sendNotification = false,
        )
    }

    private suspend fun executeDirect(job: ScheduledJobEntity, initialRun: ScheduledJobRunEntity) {
        val assistantId = runCatching { Uuid.parse(job.assistantId) }.getOrElse {
            finishRun(initialRun.id, ScheduledJobOutcome.FAILED, "Invalid assistant id", null)
            return
        }
        val assistant = settingsStore.settingsFlowRaw.first().getAssistantById(assistantId)
        if (assistant == null) {
            finishRun(initialRun.id, ScheduledJobOutcome.FAILED, "The selected assistant no longer exists", null)
            return
        }
        val availableTools = directTools(assistant.localTools)
        val actions = directRunner.validate(job.actionsJson.orEmpty(), availableTools).getOrElse {
            finishRun(initialRun.id, ScheduledJobOutcome.FAILED, it.message ?: "Invalid direct actions", null)
            return
        }
        val result = directRunner.run(actions, availableTools)
        val details = json.encodeToString(result.steps)
        finishRun(initialRun.id, result.outcome, result.errorMessage, result.preview, details)
    }

    private suspend fun executeLlm(job: ScheduledJobEntity, initialRun: ScheduledJobRunEntity) {
        val assistantId = runCatching { Uuid.parse(job.assistantId) }.getOrElse {
            finishRun(initialRun.id, ScheduledJobOutcome.FAILED, "Invalid assistant id", null)
            return
        }
        val conversationId = Uuid.random()
        val run = initialRun.copy(conversationId = conversationId.toString())
        dao.updateRun(run)
        val completed = withTimeoutOrNull(LLM_RUN_TIMEOUT_MS) {
            chatService.runScheduledPrompt(
                conversationId = conversationId,
                assistantId = assistantId,
                modelId = job.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                title = job.name,
                prompt = job.prompt.orEmpty(),
            )
            true
        } ?: false
        if (!completed) {
            runCatching { chatService.stopGeneration(conversationId) }
            finishRun(run.id, ScheduledJobOutcome.TIMED_OUT, "AI execution exceeded 15 minutes", null)
        } else {
            refreshRunForConversation(conversationId.toString())
        }
    }

    suspend fun refreshRunForConversation(conversationId: String) {
        val run = dao.getRunByConversation(conversationId) ?: return
        if (run.finishedAtMs != null || run.outcome !in setOf("running", ScheduledJobOutcome.WAITING_APPROVAL)) return
        val outcome = runCatching { chatService.scheduledConversationOutcome(Uuid.parse(conversationId)) }
            .getOrElse { result ->
                finishRun(run.id, ScheduledJobOutcome.FAILED, result.message, null)
                return
            }
        val status = when {
            outcome.awaitingApproval -> ScheduledJobOutcome.WAITING_APPROVAL
            outcome.error != null -> ScheduledJobOutcome.FAILED
            outcome.answer != null -> ScheduledJobOutcome.SUCCESS
            else -> ScheduledJobOutcome.FAILED
        }
        finishRun(run.id, status, outcome.error ?: if (status == ScheduledJobOutcome.FAILED) "No assistant reply" else null, outcome.answer?.take(500))
    }

    private suspend fun finishRun(
        runId: String,
        outcome: String,
        error: String?,
        preview: String?,
        actionResultsJson: String? = null,
        sendNotification: Boolean = true,
    ) = lock.withLock {
        val current = dao.getRun(runId) ?: return@withLock
        if (current.finishedAtMs != null) return@withLock
        val now = System.currentTimeMillis()
        val updated = current.copy(
            outcome = outcome,
            errorMessage = error?.take(500),
            resultPreview = preview?.take(500),
            actionResultsJson = actionResultsJson?.take(32_000) ?: current.actionResultsJson,
            finishedAtMs = if (outcome == ScheduledJobOutcome.WAITING_APPROVAL) null else now,
        )
        if (current.outcome == updated.outcome &&
            current.errorMessage == updated.errorMessage &&
            current.resultPreview == updated.resultPreview &&
            current.actionResultsJson == updated.actionResultsJson
        ) return@withLock
        dao.updateRun(updated)
        if (updated.finishedAtMs != null) dao.trimRuns(updated.jobId, RUN_HISTORY_LIMIT)
        val job = dao.getJob(updated.jobId) ?: return@withLock
        if (sendNotification && job.notificationEnabled && outcome != "running") notifyRun(job, updated)
    }

    private suspend fun rescheduleAfterNaturalRun(jobId: String) = lock.withLock {
        val current = dao.getJob(jobId) ?: return@withLock
        val now = System.currentTimeMillis()
        val successCount = dao.countSuccessfulRuns(jobId)
        val stillEnabled = current.enabled && current.scheduleType != ScheduledJobType.ONCE &&
            (current.maxRuns == null || successCount < current.maxRuns) &&
            (current.endAtUnixMs == null || now <= current.endAtUnixMs)
        val advanced = current.copy(
            enabled = stillEnabled,
            lastRunAtMs = now,
            runsSoFar = successCount,
            nextRunAtMs = null,
            updatedAtMs = now,
        )
        val next = if (stillEnabled) ScheduledJobTime.nextRunMs(advanced, now) else null
        val saved = advanced.copy(enabled = stillEnabled && next != null, nextRunAtMs = next)
        dao.upsertJob(saved)
        if (saved.enabled && next != null) scheduler.scheduleAfterCurrent(saved, next, now)
    }

    /** Re-arm a regular slot consumed while another run/manual execution was active. */
    private suspend fun restoreRegularSchedule(jobId: String) = lock.withLock {
        val current = dao.getJob(jobId) ?: return@withLock
        if (!current.enabled) return@withLock
        val active = dao.getActiveRun(jobId)
        if (active != null && !active.manual && active.outcome == "running") return@withLock
        val now = System.currentTimeMillis()
        val next = ScheduledJobTime.nextRunMs(current, now)
        if (next == null) {
            dao.upsertJob(current.copy(enabled = false, nextRunAtMs = null, updatedAtMs = now))
        } else {
            val saved = current.copy(nextRunAtMs = next, updatedAtMs = now)
            dao.upsertJob(saved)
            scheduler.scheduleAfterCurrent(saved, next, now)
        }
    }

    private suspend fun validate(job: ScheduledJobEntity) {
        require(job.name.isNotBlank()) { "Task name is required" }
        require(job.description.orEmpty().length <= 500) { "Description must be at most 500 characters" }
        require(job.scheduleType in setOf(ScheduledJobType.ONCE, ScheduledJobType.CRON)) { "Unsupported schedule type" }
        require(job.catchup in setOf(ScheduledJobCatchup.SKIP, ScheduledJobCatchup.FIRE_ONCE, ScheduledJobCatchup.FIRE_ALL)) {
            "Unsupported catch-up policy"
        }
        job.timezone?.takeIf(String::isNotBlank)?.let(ZoneId::of)
        if (job.startAtUnixMs != null && job.endAtUnixMs != null) {
            require(job.startAtUnixMs <= job.endAtUnixMs) { "Start time must be before end time" }
        }
        require(job.maxRuns == null || job.maxRuns > 0) { "Maximum runs must be greater than zero" }
        val settings = settingsStore.settingsFlowRaw.first()
        when (job.mode) {
            ScheduledJobMode.LLM -> {
                require(job.prompt?.isNotBlank() == true) { "Prompt is required" }
                val assistantId = Uuid.parse(job.assistantId)
                val assistant = settings.getAssistantById(assistantId) ?: error("The selected assistant no longer exists")
                val modelId = job.modelId?.let { Uuid.parse(it) } ?: assistant.chatModelId ?: settings.chatModelId
                require(settings.findModelById(modelId)?.type == ModelType.CHAT) { "The selected model is unavailable" }
            }
            ScheduledJobMode.DIRECT -> {
                val assistant = settings.getAssistantById(Uuid.parse(job.assistantId))
                    ?: error("The selected assistant no longer exists")
                directRunner.validate(job.actionsJson.orEmpty(), directTools(assistant.localTools)).getOrThrow()
            }
            ScheduledJobMode.REMINDER -> require(job.notificationBody.isNotBlank()) { "Reminder text is required" }
            else -> error("Unsupported execution mode")
        }
        if (job.scheduleType == ScheduledJobType.ONCE) {
            require(job.atUnixMs != null && (!job.enabled || job.atUnixMs > System.currentTimeMillis())) {
                "Enabled one-time runs must be in the future"
            }
        } else {
            val expression = job.cronExpression?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Cron expression is required")
            CronExpressionParser.parse(expression).getOrThrow()
        }
    }

    private fun directTools(options: List<me.rerere.rikkahub.data.ai.tools.local.LocalToolOption>): List<Tool> =
        localTools.getTools(options).filter { DirectModeActionRunner.isHeadlessSafeTool(it.name) }

    private fun newRun(
        job: ScheduledJobEntity,
        slotMs: Long,
        nowMs: Long,
        outcome: String,
        manual: Boolean,
    ) = ScheduledJobRunEntity(
        id = Uuid.random().toString(),
        jobId = job.id,
        mode = job.mode,
        scheduledAtMs = slotMs,
        startedAtMs = nowMs,
        finishedAtMs = null,
        outcome = outcome,
        conversationId = null,
        errorMessage = null,
        resultPreview = null,
        actionResultsJson = null,
        manual = manual,
    )

    private suspend fun recordConcurrentSkip(jobId: String, slot: Long, manual: Boolean, now: Long) {
        val job = dao.getJob(jobId) ?: return
        val run = newRun(job, if (manual) now else slot, now, ScheduledJobOutcome.CONCURRENT_SKIP, manual)
            .copy(finishedAtMs = now)
        if (manual) dao.insertRun(run) else dao.insertScheduledRunIfMissing(run)
    }

    private suspend fun showReminder(job: ScheduledJobEntity, run: ScheduledJobRunEntity): Boolean = runCatching {
        val intent = taskPendingIntent(run)
        context.sendNotification(SCHEDULED_JOB_RESULT_CHANNEL_ID, run.id.hashCode()) {
            title = job.notificationTitle.ifBlank { job.name }
            content = job.notificationBody
            useBigTextStyle = true
            autoCancel = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = intent
        }
    }.onFailure { Log.w(TAG, "Reminder notification failed", it) }.getOrDefault(false)

    private fun notifyRun(job: ScheduledJobEntity, run: ScheduledJobRunEntity) {
        val statusText = when (run.outcome) {
            ScheduledJobOutcome.SUCCESS -> context.getString(R.string.scheduled_task_complete)
            ScheduledJobOutcome.WAITING_APPROVAL -> context.getString(R.string.scheduled_task_approval_needed)
            ScheduledJobOutcome.TIMED_OUT -> "Timed out"
            ScheduledJobOutcome.INTERRUPTED -> context.getString(R.string.scheduled_task_interrupted)
            else -> context.getString(R.string.scheduled_task_failed)
        }
        val body = when (run.outcome) {
            ScheduledJobOutcome.SUCCESS -> if (job.mode == ScheduledJobMode.LLM) {
                val message = job.notificationBody.takeIf(String::isNotBlank)
                    ?: run.resultPreview?.takeIf(String::isNotBlank)
                    ?: statusText
                message.compactNotificationText()
            } else {
                listOf(run.resultPreview, job.notificationBody).filterNotNull()
                    .filter(String::isNotBlank).joinToString("\n").take(800)
            }
            ScheduledJobOutcome.WAITING_APPROVAL -> statusText
            else -> "$statusText: ${run.errorMessage.orEmpty()}".take(800)
        }
        runCatching {
            context.sendNotification(SCHEDULED_JOB_RESULT_CHANNEL_ID, run.id.hashCode()) {
                title = job.notificationTitle.ifBlank { job.name }
                content = body
                useBigTextStyle = true
                autoCancel = true
                category = if (run.outcome == ScheduledJobOutcome.SUCCESS) NotificationCompat.CATEGORY_MESSAGE
                    else NotificationCompat.CATEGORY_ERROR
                contentIntent = taskPendingIntent(run)
            }
        }.onFailure { Log.w(TAG, "Run notification failed", it) }
    }

    private fun String.compactNotificationText(maxCodePoints: Int = MAX_NOTIFICATION_MESSAGE_CODE_POINTS): String {
        val count = codePointCount(0, length)
        if (count <= maxCodePoints) return this
        val end = offsetByCodePoints(0, maxCodePoints - 1)
        return substring(0, end) + "…"
    }

    private fun taskPendingIntent(run: ScheduledJobRunEntity): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (run.conversationId != null) putExtra("conversationId", run.conversationId)
            else putExtra("openScheduledTasks", true)
        }
        return PendingIntent.getActivity(
            context, run.id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "ScheduledJobManager"
        const val LLM_RUN_TIMEOUT_MS = 15 * 60_000L
        const val RUN_HISTORY_LIMIT = 200
        const val MAX_NOTIFICATION_MESSAGE_CODE_POINTS = 60
    }
}
