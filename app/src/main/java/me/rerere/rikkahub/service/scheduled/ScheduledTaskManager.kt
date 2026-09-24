package me.rerere.rikkahub.service.scheduled

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDao
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity
import me.rerere.rikkahub.data.db.entity.ScheduledExecutionMode
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

const val SCHEDULED_TASK_RESULT_CHANNEL_ID = "scheduled_task_result"
const val SCHEDULED_TASK_PROGRESS_CHANNEL_ID = "scheduled_task_progress"

object ScheduledRunStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
    const val SKIPPED = "SKIPPED"
}

class ScheduledTaskManager(
    private val context: Application,
    private val appScope: AppScope,
    private val dao: ScheduledTaskDao,
    private val chatService: ChatService,
    eventBus: AppEventBus,
) {
    private val mutex = Mutex()
    private val processStartedAt = System.currentTimeMillis()
    private val startupReconciled = CompletableDeferred<Unit>()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val workManager by lazy { WorkManager.getInstance(context) }

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
                reconcile(recoverInterrupted = true)
                dao.getUnfinishedRuns()
                    .filter { it.status == ScheduledRunStatus.WAITING_APPROVAL }
                    .forEach { run ->
                        run.conversationId?.let { refreshRunForConversation(it) }
                    }
            } finally {
                startupReconciled.complete(Unit)
            }
        }
    }

    fun observeTasks(): Flow<List<ScheduledTaskEntity>> = dao.observeTasks()
    fun observeRuns(taskId: String): Flow<List<ScheduledTaskRunEntity>> = dao.observeRuns(taskId)
    suspend fun getTask(id: String): ScheduledTaskEntity? = dao.getTask(id)
    suspend fun getLatestRun(taskId: String): ScheduledTaskRunEntity? = dao.getLatestRun(taskId)

    fun canScheduleExactly(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun exactAlarmSettingsIntent(): Intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
    }

    suspend fun save(task: ScheduledTaskEntity) = mutex.withLock {
        require(task.name.isNotBlank())
        require(task.executionMode in listOf(ScheduledExecutionMode.AI, ScheduledExecutionMode.REMINDER))
        if (task.executionMode == ScheduledExecutionMode.AI) {
            require(task.prompt.isNotBlank() && task.assistantId.isNotBlank())
        } else {
            require(task.notificationBody.isNotBlank())
        }
        require(task.hour in 0..23 && task.minute in 0..59)
        require(task.scheduleType != ScheduleType.WEEKLY || task.weekdays != 0)
        val now = System.currentTimeMillis()
        val next = if (task.enabled) nextOccurrence(
            task, Instant.ofEpochMilli(now), ZoneId.systemDefault()
        )?.toEpochMilli() else null
        require(!task.enabled || next != null) { "The scheduled time must be in the future" }
        val existing = dao.getTask(task.id)
        val saved = task.copy(
            nextRunAt = next,
            scheduledZoneId = ZoneId.systemDefault().id,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsertTask(saved)
        cancelAlarm(task.id)
        if (existing != null && existing.executionMode != saved.executionMode) {
            dao.getActiveRun(task.id)?.takeIf { it.status == ScheduledRunStatus.QUEUED }?.let {
                dao.updateRun(it.copy(status = ScheduledRunStatus.SKIPPED, finishedAt = now))
                workManager.cancelUniqueWork(workName(task.id))
            }
        }
        if (saved.enabled && next != null) {
            scheduleAlarm(saved, next)
        } else {
            val active = dao.getActiveRun(task.id)
            if (active?.status == ScheduledRunStatus.QUEUED) {
                dao.updateRun(active.copy(status = ScheduledRunStatus.SKIPPED, finishedAt = now))
            }
            if (active == null || active.status == ScheduledRunStatus.QUEUED) {
                workManager.cancelUniqueWork(workName(task.id))
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val task = dao.getTask(id) ?: return
        save(task.copy(enabled = enabled))
    }

    suspend fun delete(id: String) {
        dao.getActiveRun(id)?.takeIf { it.status == ScheduledRunStatus.RUNNING }
            ?.conversationId?.let { chatService.stopGeneration(Uuid.parse(it)) }
        mutex.withLock {
            val task = dao.getTask(id) ?: return@withLock
            cancelAlarm(id)
            workManager.cancelUniqueWork(workName(id))
            dao.deleteRuns(id)
            dao.deleteTask(task)
        }
    }

    suspend fun runNow(id: String): Boolean = mutex.withLock {
        val task = dao.getTask(id) ?: return@withLock false
        queueRun(task, System.currentTimeMillis(), manual = true)
    }

    suspend fun onAlarm(id: String, expectedAt: Long) = mutex.withLock {
        val task = dao.getTask(id) ?: return@withLock
        if (!task.enabled || task.nextRunAt != expectedAt) return@withLock
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        if (task.scheduledZoneId != zone.id) {
            if (task.scheduleType == ScheduleType.ONCE && dao.getLatestRun(task.id) == null) {
                latestOccurrence(task, Instant.ofEpochMilli(now), zone)?.let {
                    queueRun(task, it.toEpochMilli())
                }
            }
            val current = dao.getTask(task.id) ?: return@withLock
            val next = if (current.enabled) nextOccurrence(current, Instant.ofEpochMilli(now), zone)?.toEpochMilli() else null
            val updated = current.copy(nextRunAt = next, scheduledZoneId = zone.id)
            dao.upsertTask(updated)
            if (next != null) scheduleAlarm(updated, next)
            return@withLock
        }
        if (expectedAt > now + 1_000L) return@withLock
        val latest = latestOccurrence(task, Instant.ofEpochMilli(now), zone)
        if (latest != null) queueRun(task, latest.toEpochMilli())
        val current = dao.getTask(task.id) ?: return@withLock
        val next = if (current.enabled) nextOccurrence(current, Instant.ofEpochMilli(now), zone)?.toEpochMilli() else null
        val updated = current.copy(nextRunAt = next)
        dao.upsertTask(updated)
        if (next != null) scheduleAlarm(updated, next)
    }

    suspend fun reconcile(
        recoverInterrupted: Boolean = false,
        recalculateWallClock: Boolean = false,
    ) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (recoverInterrupted) {
            dao.getUnfinishedRuns().filter {
                it.status == ScheduledRunStatus.RUNNING && (it.startedAt ?: 0L) < processStartedAt
            }.forEach { run ->
                val interrupted = run.copy(
                    status = ScheduledRunStatus.INTERRUPTED,
                    error = context.getString(R.string.scheduled_task_interrupted),
                    finishedAt = now,
                )
                dao.updateRun(interrupted)
                dao.getTask(run.taskId)?.takeIf { it.notificationEnabled && run.conversationId != null }?.let {
                    notifyRun(it, interrupted)
                }
            }
        }
        dao.getTasks().forEach { task ->
            cancelAlarm(task.id)
            if (!task.enabled) return@forEach
            val zone = ZoneId.systemDefault()
            val zoneChanged = recalculateWallClock || task.scheduledZoneId != zone.id
            val latestRun = dao.getLatestRun(task.id)
            val due = task.nextRunAt?.takeIf { !zoneChanged && it <= now }
                ?: if (task.scheduleType == ScheduleType.ONCE && latestRun == null) {
                    latestOccurrence(task, Instant.ofEpochMilli(now), zone)?.toEpochMilli()
                } else null
            if (due != null) {
                val latest = latestOccurrence(task, Instant.ofEpochMilli(now), zone)
                if (latest != null) queueRun(task, latest.toEpochMilli())
            }
            val current = dao.getTask(task.id) ?: return@forEach
            val next = if (current.enabled) nextOccurrence(current, Instant.ofEpochMilli(now), zone)?.toEpochMilli() else null
            val updated = current.copy(nextRunAt = next, scheduledZoneId = zone.id)
            if (updated != task) dao.upsertTask(updated)
            if (next != null) scheduleAlarm(updated, next)
            dao.getActiveRun(task.id)?.takeIf { it.status == ScheduledRunStatus.QUEUED }?.let { queued ->
                if (current.executionMode == ScheduledExecutionMode.AI) {
                    enqueueWork(task.id)
                } else {
                    dao.updateRun(queued.copy(
                        status = ScheduledRunStatus.INTERRUPTED,
                        error = context.getString(R.string.scheduled_task_interrupted),
                        finishedAt = now,
                    ))
                }
            }
        }
    }

    private suspend fun queueRun(task: ScheduledTaskEntity, scheduledAt: Long, manual: Boolean = false): Boolean {
        val active = dao.getActiveRun(task.id)
        if (active != null) {
            if (active.status == ScheduledRunStatus.QUEUED && scheduledAt > active.scheduledAt) {
                dao.updateRun(active.copy(scheduledAt = scheduledAt))
                if (task.executionMode == ScheduledExecutionMode.AI) enqueueWork(task.id)
            }
            return false
        }
        if (!manual && task.scheduleType == ScheduleType.ONCE && dao.getLatestRun(task.id) != null) return false
        val run = ScheduledTaskRunEntity(
            id = Uuid.random().toString(),
            taskId = task.id,
            scheduledAt = scheduledAt,
            startedAt = null,
            finishedAt = null,
            status = ScheduledRunStatus.QUEUED,
            error = null,
            resultPreview = null,
            conversationId = null,
        )
        dao.insertRun(run)
        if (task.executionMode == ScheduledExecutionMode.REMINDER) {
            val started = run.copy(status = ScheduledRunStatus.RUNNING, startedAt = System.currentTimeMillis())
            dao.updateRun(started)
            val delivered = showReminder(task, run)
            dao.updateRun(started.copy(
                status = if (delivered) ScheduledRunStatus.SUCCEEDED else ScheduledRunStatus.FAILED,
                finishedAt = System.currentTimeMillis(),
                error = if (delivered) null else context.getString(R.string.scheduled_task_notification_undelivered),
            ))
            if (task.scheduleType == ScheduleType.ONCE) {
                dao.upsertTask(task.copy(enabled = false, nextRunAt = null))
                cancelAlarm(task.id)
            }
        } else {
            enqueueWork(task.id)
        }
        return true
    }

    private fun showReminder(task: ScheduledTaskEntity, run: ScheduledTaskRunEntity): Boolean = runCatching {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("openScheduledTasks", true)
        }
        context.sendNotification(SCHEDULED_TASK_RESULT_CHANNEL_ID, run.id.hashCode()) {
            title = task.notificationTitle.ifBlank { task.name }
            content = task.notificationBody
            useBigTextStyle = true
            autoCancel = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = PendingIntent.getActivity(
                context, run.id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }.onFailure { Log.w("ScheduledTaskManager", "Unable to show reminder", it) }.getOrDefault(false)

    private fun enqueueWork(taskId: String) {
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(androidx.work.workDataOf(ScheduledTaskWorker.TASK_ID to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun executeQueued(taskId: String) {
        startupReconciled.await()
        val (task, run) = mutex.withLock {
            val task = dao.getTask(taskId) ?: return
            val queued = dao.getActiveRun(taskId)?.takeIf { it.status == ScheduledRunStatus.QUEUED }
                ?: return
            if (task.executionMode != ScheduledExecutionMode.AI) {
                dao.updateRun(queued.copy(
                    status = ScheduledRunStatus.INTERRUPTED,
                    error = context.getString(R.string.scheduled_task_interrupted),
                    finishedAt = System.currentTimeMillis(),
                ))
                return
            }
            val running = queued.copy(
                status = ScheduledRunStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
                conversationId = Uuid.random().toString(),
            )
            dao.updateRun(running)
            task to running
        }
        val conversationId = Uuid.parse(run.conversationId!!)
        try {
            chatService.runScheduledPrompt(
                conversationId = conversationId,
                assistantId = Uuid.parse(task.assistantId),
                modelId = task.modelId?.let(Uuid::parse),
                title = task.name,
                prompt = task.prompt,
            )
            refreshRunForConversation(run.conversationId)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                chatService.stopGeneration(conversationId)
                finishRun(run, ScheduledRunStatus.INTERRUPTED, e.message, null)
            }
            throw e
        } catch (e: Exception) {
            if (!chatService.hasSavedConversation(conversationId)) {
                mutex.withLock {
                    dao.getRun(run.id)?.let { dao.updateRun(it.copy(conversationId = null)) }
                }
            }
            finishRun(run, ScheduledRunStatus.FAILED, e.message, null)
        }
    }

    suspend fun failQueued(taskId: String, reason: String?) = mutex.withLock {
        val run = dao.getActiveRun(taskId)?.takeIf { it.status == ScheduledRunStatus.QUEUED }
            ?: return@withLock
        val failed = run.copy(
            status = ScheduledRunStatus.FAILED,
            error = reason ?: context.getString(R.string.scheduled_task_failed),
            finishedAt = System.currentTimeMillis(),
        )
        dao.updateRun(failed)
        dao.getTask(taskId)?.let { task ->
            if (task.scheduleType == ScheduleType.ONCE) {
                dao.upsertTask(task.copy(enabled = false, nextRunAt = null))
                cancelAlarm(task.id)
            }
            if (task.notificationEnabled) notifyRun(task, failed)
        }
    }

    suspend fun refreshRunForConversation(conversationId: String) {
        val run = dao.getRunByConversation(conversationId) ?: return
        if (run.status !in listOf(ScheduledRunStatus.RUNNING, ScheduledRunStatus.WAITING_APPROVAL)) return
        val outcome = chatService.scheduledConversationOutcome(Uuid.parse(conversationId))
        val status = when {
            outcome.awaitingApproval -> ScheduledRunStatus.WAITING_APPROVAL
            outcome.error != null -> ScheduledRunStatus.FAILED
            outcome.answer != null -> ScheduledRunStatus.SUCCEEDED
            else -> ScheduledRunStatus.FAILED
        }
        finishRun(
            run, status,
            outcome.error ?: if (status == ScheduledRunStatus.FAILED) "No assistant reply" else null,
            outcome.answer?.take(500),
        )
    }

    private suspend fun finishRun(
        run: ScheduledTaskRunEntity,
        status: String,
        error: String?,
        preview: String?,
    ) = mutex.withLock {
        val current = dao.getRun(run.id) ?: return@withLock
        if (current.status !in listOf(ScheduledRunStatus.RUNNING, ScheduledRunStatus.WAITING_APPROVAL)) return@withLock
        if (current.status == status && current.error == error) return@withLock
        val finished = status != ScheduledRunStatus.WAITING_APPROVAL
        val updated = current.copy(
            status = status,
            error = error,
            resultPreview = preview,
            finishedAt = if (finished) System.currentTimeMillis() else null,
        )
        dao.updateRun(updated)
        val task = dao.getTask(current.taskId) ?: return@withLock
        if (finished && task.scheduleType == ScheduleType.ONCE) {
            dao.upsertTask(task.copy(enabled = false, nextRunAt = null))
            cancelAlarm(task.id)
        }
        if (current.conversationId != null && task.notificationEnabled) notifyRun(task, updated)
    }

    private fun notifyRun(task: ScheduledTaskEntity, run: ScheduledTaskRunEntity) {
        val statusText = when (run.status) {
            ScheduledRunStatus.SUCCEEDED -> context.getString(R.string.scheduled_task_complete)
            ScheduledRunStatus.WAITING_APPROVAL -> context.getString(R.string.scheduled_task_approval_needed)
            ScheduledRunStatus.INTERRUPTED -> context.getString(R.string.scheduled_task_interrupted)
            else -> context.getString(R.string.scheduled_task_failed)
        }
        val body = when (run.status) {
            ScheduledRunStatus.SUCCEEDED -> listOf(task.notificationBody, run.resultPreview.orEmpty())
                .filter(String::isNotBlank).joinToString("\n")
            ScheduledRunStatus.WAITING_APPROVAL -> statusText
            else -> "$statusText: ${run.error.orEmpty()}"
        }
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (run.conversationId != null) putExtra("conversationId", run.conversationId)
            else putExtra("openScheduledTasks", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, run.id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            context.sendNotification(SCHEDULED_TASK_RESULT_CHANNEL_ID, run.id.hashCode()) {
                title = task.notificationTitle.ifBlank { task.name }
                content = body.ifBlank { statusText }
                useBigTextStyle = true
                autoCancel = true
                category = NotificationCompat.CATEGORY_REMINDER
                contentIntent = pendingIntent
            }
        }.onFailure { Log.w("ScheduledTaskManager", "Unable to show task notification", it) }
    }

    private fun scheduleAlarm(task: ScheduledTaskEntity, at: Long) {
        val pendingIntent = alarmIntent(task.id, at)
        if (canScheduleExactly()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
                return
            } catch (_: SecurityException) {
                // Exact-alarm access may have been revoked between the check and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
    }

    private fun cancelAlarm(taskId: String) {
        alarms.cancel(alarmIntent(taskId, 0L))
    }

    private fun alarmIntent(taskId: String, at: Long): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ScheduledTaskReceiver.ACTION_RUN
            data = Uri.parse("rikkahub://scheduled-task/$taskId")
            putExtra(ScheduledTaskReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ScheduledTaskReceiver.EXTRA_EXPECTED_AT, at)
        }
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun workName(taskId: String) = "scheduled-task-$taskId"
}
