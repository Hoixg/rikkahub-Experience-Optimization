package me.rerere.rikkahub.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.worker.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.SystemPermissions
import kotlin.uuid.Uuid

class ScheduledTaskRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val startRunner: () -> Unit = { me.rerere.rikkahub.service.ScheduledTaskExecutionService.resume(context) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.scheduledTaskDao()
    private val mutation = Mutex()
    private val startup = Mutex()
    private val runs get() = database.scheduledTaskRunDao()
    private val segmentStarts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val pendingRunsFlow = runs.pendingFlow()
    fun historyFlow(id: String) = runs.historyFlow(id)
    suspend fun history(id: String) = runs.history(id)
    suspend fun waitingRuns() = runs.waiting()
    suspend fun remainingGenerationMs(task: ScheduledTaskEntity): Long =
        (600_000L - (task.activeRunId?.let { runs.get(it)?.generationMs } ?: 0L)).coerceAtLeast(1L)

    suspend fun requestDispatch() {
        if (runs.waiting().isEmpty()) return
        runCatching { startRunner() }
            .onFailure { android.util.Log.e("ScheduledTaskRepository", "Unable to start background service", it) }
    }

    private var initialized = false
    var stopConversation: suspend (String, String) -> Unit = { _, _ -> }
    val tasksFlow: Flow<List<ScheduledTaskEntity>> = dao.getAllFlow()

    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getTasksForAssistant(id: String) = dao.getByAssistant(id)
    suspend fun resumeConversation(id: String) = mutation.withLock {
        database.withTransaction {
            val task = dao.getByActiveConversation(id) ?: return@withTransaction
            val run = task.activeRunId?.let { runs.get(it) } ?: return@withTransaction
            if (run.status == ScheduledTaskRunStatus.WAITING_APPROVAL.name) {
                dao.resumeConversation(id)
                runs.upsert(run.copy(status = "RUNNING"))
                segmentStarts[run.id] = clock()
            }
        }
    }
    suspend fun attachConversation(task: ScheduledTaskEntity) = dao.attachConversation(task.id, task.activeRunId!!, task.activeConversationId!!)
    suspend fun getActiveByConversation(id: String) = dao.getByActiveConversation(id)
    fun hasExactAlarmPermission() = SystemPermissions.canScheduleExactAlarms(context)
    fun nextTriggerAt(task: ScheduledTaskEntity) = if (task.enabled && SystemPermissions.canScheduleExactAlarms(context)) task.nextRunAt else null

    /** Only RUNNING executions are interrupted by process death. Persisted approvals remain resumable. */
    suspend fun initialize() = startup.withLock {
        if (initialized) return@withLock
        mutation.withLock {
            database.withTransaction {
                dao.getAll().filter { it.activeRunId != null }.filter { it.lastRunStatus == ScheduledTaskRunStatus.RUNNING.name ||
                    (it.lastRunStatus != "WAITING_IDLE" && !hasPendingApproval(it)) }.forEach {
                    finishLocked(it, ScheduledTaskRunStatus.FAILED, "应用进程中断，未重复发送任务", "")
                }
                dao.getAll().forEach { task ->
                    val next = ScheduledTaskSchedule.nextOnStartup(task, clock())
                    val enabled = task.enabled && next != null
                    if (next != task.nextRunAt || enabled != task.enabled) {
                        val updated = task.copy(nextRunAt = next, enabled = enabled, revision = Uuid.random().toString())
                        dao.upsert(updated)
                    }
                }
            }
            val tasks = dao.getAll()
            ScheduledTaskScheduler.cancelLegacyWork(context, tasks.map { it.id })
            tasks.forEach {
                ScheduledTaskScheduler.cancelPending(context, it)
                // A cold alarm delivery still owns an overdue slot. Do not enqueue a second alarm for it.
                if (it.nextRunAt != null && it.nextRunAt > clock()) ScheduledTaskScheduler.enqueue(context, it)
            }
        }
        refreshResumeAlarm()
        initialized = true
    }

    private suspend fun hasPendingApproval(task: ScheduledTaskEntity): Boolean {
        val id = task.activeConversationId ?: return false
        if (database.conversationDao().getConversationById(id) == null) return false
        return database.messageNodeDao().getNodesOfConversation(id).any { node ->
            runCatching {
                me.rerere.rikkahub.utils.JsonInstant.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(node.messages)
                    .getOrNull(node.selectIndex)?.parts?.any { it is me.rerere.ai.ui.UIMessagePart.Tool && it.isPending } == true
            }.getOrDefault(false)
        }
    }

    suspend fun upsert(task: ScheduledTaskEntity) {
        initialize()
        mutation.withLock {
            val old = dao.getById(task.id)
            val now = clock()
            fun configuration(t: ScheduledTaskEntity) = listOf(t.name.trim(), t.prompt.trim(), t.assistantId, t.mode,
                t.targetConversationId, t.targetUserMessageId, t.modelOverrideId, t.notify, t.showPreview,
                t.scheduleType, t.triggerAt, t.intervalMinutes, t.timeOfDayMinutes, t.weekdaysMask, t.startDate, t.endDate)
            require(old?.activeRunId == null || configuration(task) == configuration(old)) { "请先取消当前执行，再修改任务" }
            val changedSchedule = old == null || old.scheduleType != task.scheduleType || old.triggerAt != task.triggerAt ||
                old.intervalMinutes != task.intervalMinutes || old.timeOfDayMinutes != task.timeOfDayMinutes || old.enabled != task.enabled ||
                old.weekdaysMask != task.weekdaysMask || old.startDate != task.startDate || old.endDate != task.endDate
            require(!task.enabled || old?.enabled == true || SystemPermissions.canScheduleExactAlarms(context)) {
                "请先在权限管理中允许精确闹钟，再启用任务"
            }
            ScheduledTaskSchedule.validate(task, now, old == null || (changedSchedule && task.enabled))
            require(dao.getByAssistant(task.assistantId).none { it.id != task.id && it.name == task.name.trim() }) { "该助手已有同名任务" }
            val base = (old ?: task).copy(name = task.name.trim(), prompt = task.prompt.trim(), assistantId = task.assistantId,
                scheduleType = task.scheduleType, triggerAt = task.triggerAt, intervalMinutes = task.intervalMinutes,
                timeOfDayMinutes = task.timeOfDayMinutes, weekdaysMask = task.weekdaysMask,
                startDate = task.startDate, endDate = task.endDate,
                mode = task.mode, targetConversationId = task.targetConversationId,
                targetUserMessageId = task.targetUserMessageId, modelOverrideId = task.modelOverrideId,
                notify = task.notify, showPreview = task.showPreview,
                enabled = task.enabled, updatedAt = now, revision = Uuid.random().toString())
            val updated = base.copy(nextRunAt = if (!base.enabled) null else if (changedSchedule) ScheduledTaskSchedule.next(base, now) else old?.nextRunAt)
            dao.upsert(updated)
            if (old != null) ScheduledTaskScheduler.cancelPending(context, old)
            ScheduledTaskScheduler.enqueue(context, updated)
        }
    }

    suspend fun delete(task: ScheduledTaskEntity) {
        initialize()
        mutation.withLock {
            require(dao.getById(task.id)?.activeRunId == null) { "请先取消当前执行，再删除任务" }
            dao.deleteById(task.id); ScheduledTaskScheduler.cancelAll(context, task.id)
            refreshResumeAlarm()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long = clock()) {
        val task = dao.getById(id) ?: return
        upsert(task.copy(enabled = enabled, updatedAt = updatedAt))
    }

    suspend fun runNow(id: String): ScheduledTaskEntity {
        initialize()
        val task = mutation.withLock {
            database.withTransaction {
                val current = dao.getById(id) ?: error("任务已删除")
                if (current.activeRunId != null) return@withTransaction current
                queueLocked(current, Uuid.random().toString(), clock(), true)
            }
        }
        refreshResumeAlarm()
        requestDispatch()
        return task
    }

    private suspend fun queueLocked(task: ScheduledTaskEntity, runId: String, dueAt: Long, manual: Boolean): ScheduledTaskEntity {
        val queued = task.copy(activeRunId = runId, activeConversationId = null, activeManual = manual,
            activeScheduledAt = dueAt, lastRunAt = clock(), lastRunId = runId,
            lastRunStatus = "WAITING_IDLE", lastConversationId = "", lastError = "")
        dao.upsert(queued)
        runs.upsert(ScheduledTaskRunEntity(runId, task.id, if (manual) "MANUAL" else "SCHEDULED", dueAt, status = "WAITING_IDLE"))
        return queued
    }

    suspend fun claim(id: String, revision: String, scheduledAt: Long, runId: String, conversationId: String): ScheduledTaskEntity? {
        initialize()
        return mutation.withLock {
            val result = database.withTransaction {
                val task = dao.getById(id) ?: return@withTransaction null
                val decision = ScheduledTaskSchedule.claim(task, revision, scheduledAt, runId, conversationId, clock())
                val updated = decision.updated ?: return@withTransaction null
                dao.upsert(updated)
                if (decision.run == null) {
                    runs.upsert(ScheduledTaskRunEntity(runId, id, "SCHEDULED", scheduledAt, endedAt = clock(),
                        status = "SKIPPED", error = "上一次执行尚未结束"))
                    runs.prune(id)
                    return@withTransaction null
                }
                queueLocked(updated, runId, scheduledAt, false)
            }
            dao.getById(id)?.let { ScheduledTaskScheduler.enqueue(context, it) }
            refreshResumeAlarm()
            result
        }
    }

    /** Called only after ChatService has atomically reserved an idle session. */
    suspend fun bindConversation(task: ScheduledTaskEntity, conversationId: String): Boolean = mutation.withLock {
        database.withTransaction {
            val current = dao.getById(task.id) ?: return@withTransaction false
            if (current.activeRunId != task.activeRunId || current.lastRunStatus != "WAITING_IDLE") return@withTransaction false
            if (dao.getByActiveConversation(conversationId) != null) return@withTransaction false
            val run = runs.get(current.activeRunId!!) ?: return@withTransaction false
            dao.upsert(current.copy(activeConversationId = conversationId, lastConversationId = conversationId, lastRunStatus = "RUNNING"))
            runs.upsert(run.copy(status = "RUNNING", startedAt = run.startedAt ?: clock(), conversationId = conversationId))
            segmentStarts[run.id] = clock()
            true
        }
    }

    suspend fun cancelRun(id: String): String? {
        val cancelled = mutation.withLock {
            database.withTransaction {
                val task = dao.getById(id) ?: return@withTransaction null
                if (task.activeRunId == null) return@withTransaction null
                // Keep ownership until the generator stops; a new manual run cannot replace it here.
                if (task.activeConversationId == null) {
                    finishLocked(task, ScheduledTaskRunStatus.CANCELLED, "已取消", "")
                }
                task
            }
        } ?: return null
        cancelled.activeConversationId?.let { stopConversation(it, cancelled.activeRunId!!) }
        finish(cancelled, ScheduledTaskRunStatus.CANCELLED, "已取消")
        refreshResumeAlarm()
        requestDispatch()
        return cancelled.activeConversationId
    }

    private suspend fun finishLocked(task: ScheduledTaskEntity, status: ScheduledTaskRunStatus, error: String, preview: String): Boolean {
        val runId = task.activeRunId ?: return false
        val run = runs.get(runId)
        val waiting = status == ScheduledTaskRunStatus.WAITING_APPROVAL
        if (dao.finish(task.id, runId, status.name, error.take(500), waiting) == 0) return false
        if (run != null) runs.upsert(run.copy(status = status.name, endedAt = if (waiting) null else clock(),
            generationMs = run.generationMs + (segmentStarts.remove(runId)?.let { (clock() - it).coerceAtLeast(0L) } ?: 0L),
            preview = preview.take(500), error = error.take(500)))
        runs.prune(task.id)
        return true
    }

    suspend fun finish(task: ScheduledTaskEntity, status: ScheduledTaskRunStatus, error: String = "", preview: String = ""): Boolean = mutation.withLock {
        val changed = database.withTransaction {
            if (!finishLocked(task, status, error, preview)) return@withTransaction false
            val current = dao.getById(task.id)!!
            if (status != ScheduledTaskRunStatus.WAITING_APPROVAL && !current.activeManual && !task.activeManual && current.revision == task.revision) {
                val next = ScheduledTaskSchedule.nextAfterExecution(current, clock())
                if (next != current.nextRunAt) {
                    ScheduledTaskScheduler.cancelPending(context, current)
                    val updated = current.copy(nextRunAt = next, enabled = current.enabled && next != null)
                    dao.upsert(updated)
                    ScheduledTaskScheduler.enqueue(context, updated)
                }
            }
            true
        }
        refreshResumeAlarm()
        changed
    }

    suspend fun refreshResumeAlarm() = ScheduledTaskScheduler.scheduleResume(context, runs.waiting().isNotEmpty())

    suspend fun reschedule(id: String) { dao.getById(id)?.let { ScheduledTaskScheduler.enqueue(context, it) } }

    suspend fun clockChanged() = reconcile(recalculate = true)

    /** Rebuild all pending alarms after boot, upgrade, restore, permission grant or clock changes. */
    suspend fun reconcile(recalculate: Boolean = false) {
        initialize()
        mutation.withLock {
            dao.getAll().forEach { old ->
                val next = if (recalculate) {
                    if (old.enabled) ScheduledTaskSchedule.next(old, clock()) else null
                } else old.nextRunAt
                val enabled = old.enabled && next != null
                val updated = if (next != old.nextRunAt || enabled != old.enabled) old.copy(
                    revision = Uuid.random().toString(), nextRunAt = next, enabled = enabled,
                ).also { dao.upsert(it) } else old
                ScheduledTaskScheduler.cancelPending(context, old)
                ScheduledTaskScheduler.enqueue(context, updated)
            }
            refreshResumeAlarm()
        }
    }

    suspend fun systemEvent(action: String?) {
        reconcile(recalculate = action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED ||
            (Build.VERSION.SDK_INT >= 31 && action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
    }
}
