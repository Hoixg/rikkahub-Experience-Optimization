package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.worker.ScheduledTaskScheduler
import kotlin.uuid.Uuid

class ScheduledTaskRepository(private val context: Context, private val database: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = database.scheduledTaskDao()
    private val mutation = Mutex()
    private val startup = Mutex()
    private var initialized = false
    val tasksFlow: Flow<List<ScheduledTaskEntity>> = dao.getAllFlow()

    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getTasksForAssistant(id: String) = dao.getByAssistant(id)
    suspend fun resumeConversation(id: String) = dao.resumeConversation(id)
    suspend fun attachConversation(task: ScheduledTaskEntity) = dao.attachConversation(task.id, task.activeRunId!!, task.activeConversationId!!)
    suspend fun getActiveByConversation(id: String) = dao.getByActiveConversation(id)
    fun nextTriggerAt(task: ScheduledTaskEntity) = if (task.enabled) task.nextRunAt else null

    /** Only RUNNING executions are interrupted by process death. Persisted approvals remain resumable. */
    suspend fun initialize() = startup.withLock {
        if (initialized) return@withLock
        mutation.withLock {
            val recalibrated = database.withTransaction {
                dao.getAll().filter { it.activeRunId != null }.filter { it.lastRunStatus == ScheduledTaskRunStatus.RUNNING.name ||
                    !hasPendingApproval(it) }.forEach {
                    dao.finish(it.id, it.activeRunId!!, ScheduledTaskRunStatus.FAILED.name, "应用进程中断，未重复发送任务", false)
                }
                dao.getAll().mapNotNull { task ->
                    val next = ScheduledTaskSchedule.nextOnStartup(task, clock())
                    if (next == task.nextRunAt) null else {
                        val updated = task.copy(nextRunAt = next, revision = Uuid.random().toString())
                        dao.upsert(updated)
                        task to updated
                    }
                }
            }
            recalibrated.forEach { (old, _) -> ScheduledTaskScheduler.cancelPending(context, old) }
            dao.getAll().forEach {
                ScheduledTaskScheduler.cancelLegacyManualRequest(context, it.id)
                ScheduledTaskScheduler.enqueue(context, it)
            }
        }
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
            val changedSchedule = old == null || old.scheduleType != task.scheduleType || old.triggerAt != task.triggerAt ||
                old.intervalMinutes != task.intervalMinutes || old.timeOfDayMinutes != task.timeOfDayMinutes || old.enabled != task.enabled
            ScheduledTaskSchedule.validate(task, now, old == null || (changedSchedule && task.enabled))
            require(dao.getByAssistant(task.assistantId).none { it.id != task.id && it.name == task.name.trim() }) { "该助手已有同名任务" }
            val base = (old ?: task).copy(name = task.name.trim(), prompt = task.prompt.trim(), assistantId = task.assistantId,
                scheduleType = task.scheduleType, triggerAt = task.triggerAt, intervalMinutes = task.intervalMinutes,
                timeOfDayMinutes = task.timeOfDayMinutes, enabled = task.enabled, updatedAt = now, revision = Uuid.random().toString())
            val updated = base.copy(nextRunAt = if (!base.enabled) null else if (changedSchedule) ScheduledTaskSchedule.next(base, now) else old?.nextRunAt)
            dao.upsert(updated)
            if (old != null) ScheduledTaskScheduler.cancelPending(context, old)
            ScheduledTaskScheduler.enqueue(context, updated)
        }
    }

    suspend fun delete(task: ScheduledTaskEntity) {
        initialize()
        mutation.withLock { dao.deleteById(task.id); ScheduledTaskScheduler.cancelAll(context, task.id) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long = clock()) {
        val task = dao.getById(id) ?: return
        upsert(task.copy(enabled = enabled, updatedAt = updatedAt))
    }

    suspend fun claim(id: String, revision: String, scheduledAt: Long, runId: String, conversationId: String): ScheduledTaskEntity? {
        initialize()
        return mutation.withLock {
            database.withTransaction {
                val task = dao.getById(id) ?: return@withTransaction null
                val claim = ScheduledTaskSchedule.claim(task, revision, scheduledAt, runId, conversationId, clock())
                claim.updated?.let { dao.upsert(it) }
                claim.run
            }
        }
    }

    suspend fun finish(task: ScheduledTaskEntity, status: ScheduledTaskRunStatus, error: String = ""): Boolean = mutation.withLock {
        var previous: ScheduledTaskEntity? = null
        var updated: ScheduledTaskEntity? = null
        val changed = database.withTransaction {
            val runId = task.activeRunId ?: return@withTransaction false
            if (dao.finish(task.id, runId, status.name, error.take(500), status == ScheduledTaskRunStatus.WAITING_APPROVAL) == 0) {
                return@withTransaction false
            }
            val current = dao.getById(task.id)!!
            if (status != ScheduledTaskRunStatus.WAITING_APPROVAL && current.revision == task.revision) {
                val next = ScheduledTaskSchedule.nextAfterExecution(current, clock())
                if (next != current.nextRunAt) {
                    previous = current
                    updated = current.copy(nextRunAt = next)
                    dao.upsert(updated!!)
                }
            }
            true
        }
        previous?.let { ScheduledTaskScheduler.cancelPending(context, it) }
        updated?.let { ScheduledTaskScheduler.enqueue(context, it) }
        changed
    }

    suspend fun reschedule(id: String) { dao.getById(id)?.let { ScheduledTaskScheduler.enqueue(context, it) } }

    suspend fun clockChanged() {
        initialize()
        mutation.withLock {
            dao.getAll().filter { it.enabled }.forEach { old ->
                val updated = old.copy(revision = Uuid.random().toString(), nextRunAt = if (old.scheduleType == ScheduleType.ONCE.name) old.nextRunAt else ScheduledTaskSchedule.next(old, clock()))
                dao.upsert(updated)
                ScheduledTaskScheduler.cancelPending(context, old)
                ScheduledTaskScheduler.enqueue(context, updated)
            }
        }
    }
}
