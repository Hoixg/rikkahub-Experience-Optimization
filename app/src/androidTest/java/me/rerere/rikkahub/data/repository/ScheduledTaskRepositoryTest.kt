package me.rerere.rikkahub.data.repository

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.worker.ScheduledTaskScheduler
import me.rerere.rikkahub.worker.ScheduledTaskClockReceiver
import me.rerere.rikkahub.utils.SystemPermissions
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Tests Room claims and the platform alarm identity without making model requests. */
@RunWith(AndroidJUnit4::class)
class ScheduledTaskRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ScheduledTaskRepository
    private var now = System.currentTimeMillis()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) { override fun doWork(): Result = Result.success() }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        Assume.assumeTrue(SystemPermissions.canScheduleExactAlarms(context))
    }
    @After fun teardown() { db.close(); WorkManagerTestInitHelper.closeWorkDatabase() }
    private fun task() = ScheduledTaskEntity("test-task", "Task", "Prompt", "assistant-a", "INTERVAL", intervalMinutes = 15,
        createdAt = now, updatedAt = now, revision = "initial")
    private suspend fun create() = task().also { repository.upsert(it) }.let { repository.getById(it.id)!! }
    private suspend fun claimRunning(id: String, revision: String, dueAt: Long, runId: String, conversationId: String): ScheduledTaskEntity? {
        val queued = repository.claim(id, revision, dueAt, runId, conversationId) ?: return null
        assertTrue(repository.bindConversation(queued, conversationId))
        return repository.getById(id)
    }
    private fun alarm(id: String): PendingIntent? {
        val intent = Intent(context, ScheduledTaskClockReceiver::class.java).setAction(ScheduledTaskScheduler.ACTION_FIRE)
            .setData(Uri.Builder().scheme("rikkaplus").authority("scheduled-task").appendPath(id).build())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }

    @Test fun disablingAndDeletingCancelPendingAlarms() = runBlocking {
        val task = create()
        assertNotNull(alarm(task.id))
        repository.setEnabled(task.id, false)
        assertNull(alarm(task.id))
        repository.setEnabled(task.id, true)
        assertNotNull(alarm(task.id))
        repository.delete(repository.getById(task.id)!!)
        assertNull(repository.getById(task.id))
        assertNull(alarm(task.id))
    }
    @Test fun startupCancelsLegacyManualRequests() = runBlocking {
        val task = create()
        val request = OneTimeWorkRequestBuilder<me.rerere.rikkahub.worker.ScheduledTaskWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(workDataOf("task_id" to task.id, "manual" to true)).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("scheduled_task_v2_${task.id}_manual", ExistingWorkPolicy.KEEP, request).result.get()
        ScheduledTaskRepository(context, db, startRunner = {}) { now }.initialize()
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(request.id).get()!!.state)
    }
    @Test fun startupCancelsLegacyScheduledRequests() = runBlocking {
        create()
        val request = OneTimeWorkRequestBuilder<me.rerere.rikkahub.worker.ScheduledTaskWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).addTag(ScheduledTaskScheduler.TAG).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get()
        ScheduledTaskRepository(context, db, startRunner = {}) { now }.initialize()
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(request.id).get()!!.state)
    }
    @Test fun legacyManualWorkerIsIgnoredWithoutStartingGeneration() = runBlocking {
        val task = create()
        val worker = androidx.work.testing.TestListenableWorkerBuilder<me.rerere.rikkahub.worker.ScheduledTaskWorker>(context)
            .setInputData(workDataOf("task_id" to task.id, "manual" to true)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertNull(repository.getById(task.id)!!.activeRunId)
        assertEquals(task.nextRunAt, repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun concurrentClaimsStartOnlyOneRunAndLateCompletionCannotOverwriteIt() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val runs = listOf("one", "two").map { token -> async(Dispatchers.Default) {
            claimRunning(task.id, task.revision, now, token, "conversation-$token")
        } }.awaitAll().filterNotNull()
        assertEquals(1, runs.size)
        assertFalse(repository.finish(runs.single().copy(activeRunId = "obsolete"), ScheduledTaskRunStatus.SUCCESS))
        assertTrue(repository.finish(runs.single(), ScheduledTaskRunStatus.WAITING_APPROVAL))
        assertNotNull(repository.getById(task.id)!!.activeRunId)
        repository.resumeConversation(runs.single().activeConversationId!!)
        assertEquals("RUNNING", repository.getById(task.id)!!.lastRunStatus)
        assertTrue(repository.finish(runs.single(), ScheduledTaskRunStatus.SUCCESS))
        assertNull(repository.getById(task.id)!!.activeRunId)
        assertNull(claimRunning(task.id, task.revision, now, "replay", "new-conversation"))
    }
    @Test fun restoredInterruptedRunIsFailedAndItsConsumedSlotIsNotSentAgain() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = claimRunning(task.id, task.revision, now, "interrupted", "conversation")!!
        val recovered = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        recovered.initialize()
        assertEquals("FAILED", recovered.getById(task.id)!!.lastRunStatus)
        assertNull(recovered.getById(task.id)!!.activeRunId)
        assertNull(recovered.claim(task.id, run.revision, now, "again", "again-conversation"))
    }
    @Test fun newProcessKeepsExistingPendingAlarm() = runBlocking {
        val task = create()
        ScheduledTaskRepository(context, db, startRunner = {}) { now }.initialize()
        assertNotNull(alarm(task.id))
        assertEquals(task.nextRunAt, repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun restoredFutureDailySlotIsRecalibratedBeforeAlarm() = runBlocking {
        val task = create().copy(scheduleType = "DAILY", nextRunAt = now + 3 * 86400000L)
        db.scheduledTaskDao().upsert(task)
        val recovered = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        recovered.initialize()
        val updated = recovered.getById(task.id)!!
        assertEquals(ScheduledTaskSchedule.next(task, now), updated.nextRunAt)
        assertNotEquals(task.revision, updated.revision)
        assertNotNull(alarm(task.id))
    }
    @Test fun permissionReconciliationSkipsOverdueSlotWithoutReplayingIt() = runBlocking {
        val task = create().copy(nextRunAt = now - 60000L)
        db.scheduledTaskDao().upsert(task)
        val recovered = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        recovered.initialize()
        assertEquals(task.nextRunAt, recovered.getById(task.id)!!.nextRunAt)
        assertNull(alarm(task.id))
        recovered.reconcile(recalculate = true)
        val updated = recovered.getById(task.id)!!
        assertEquals(ScheduledTaskSchedule.next(task, now), updated.nextRunAt)
        assertNull(recovered.claim(task.id, task.revision, task.nextRunAt!!, "stale", "stale-conversation"))
        assertNotNull(alarm(task.id))
    }
    @Test fun longRunCompletionSkipsExpiredPeriods() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = claimRunning(task.id, task.revision, now, "long-run", "long-conversation")!!
        now += 50 * 60000L
        repository.finish(run, ScheduledTaskRunStatus.SUCCESS)
        assertEquals(ScheduledTaskSchedule.next(task, now), repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun storedApprovalsSurviveProcessRecoveryAndRemainBoundToTheirConversation() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = claimRunning(task.id, task.revision, now, "waiting-request", "waiting-conversation")!!
        db.conversationDao().insert(ConversationEntity("waiting-conversation", task.assistantId, "Task", "[]", now, now, "[]", false))
        val message = me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(
            me.rerere.ai.ui.UIMessagePart.Tool("call", "test_tool", "{}", approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)))
        db.messageNodeDao().insert(MessageNodeEntity("pending-node", "waiting-conversation", 0,
            me.rerere.rikkahub.utils.JsonInstant.encodeToString(listOf(message)), 0))
        repository.attachConversation(run)
        repository.finish(run, ScheduledTaskRunStatus.WAITING_APPROVAL)
        val recovered = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        recovered.initialize()
        assertEquals("WAITING_APPROVAL", recovered.getById(task.id)!!.lastRunStatus)
        assertEquals("waiting-request", recovered.getActiveByConversation("waiting-conversation")!!.activeRunId)
        now = recovered.getById(task.id)!!.nextRunAt!!
        assertNull(recovered.claim(task.id, task.revision, now, "second-request", "other-conversation"))
        recovered.resumeConversation("waiting-conversation")
        assertEquals("RUNNING", recovered.getById(task.id)!!.lastRunStatus)
        recovered.finish(run, ScheduledTaskRunStatus.SUCCESS)
        assertNull(recovered.getActiveByConversation("waiting-conversation"))
    }
    @Test fun assistantQueriesAreIsolatedAndUiNamesAreUniqueWithinAssistant() = runBlocking {
        create()
        assertTrue(repository.getTasksForAssistant("assistant-b").isEmpty())
        try { repository.upsert(task().copy(id = "duplicate")); fail("Expected duplicate-name rejection") }
        catch (_: IllegalArgumentException) {}
        repository.upsert(task().copy(id = "other", assistantId = "assistant-b"))
        assertEquals(1, repository.getTasksForAssistant("assistant-b").size)
    }
    @Test fun manualRunIsIdempotentAndDoesNotChangeSchedule() = runBlocking {
        val task = create()
        val first = repository.runNow(task.id)
        val second = repository.runNow(task.id)
        assertEquals(first.activeRunId, second.activeRunId)
        assertEquals(task.nextRunAt, second.nextRunAt)
        assertEquals(task.enabled, second.enabled)
        assertEquals(1, repository.history(task.id).size)
        repository.finish(first, ScheduledTaskRunStatus.SUCCESS)
        assertEquals(task.nextRunAt, repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun waitingIdleSurvivesRecoveryAndCanBeCancelledWithoutAConversation() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val waiting = repository.claim(task.id, task.revision, now, "idle-run", "unused")!!
        assertNull(waiting.activeConversationId)
        val recovered = ScheduledTaskRepository(context, db, startRunner = {}) { now }
        recovered.initialize()
        assertEquals("WAITING_IDLE", recovered.getById(task.id)!!.lastRunStatus)
        recovered.cancelRun(task.id)
        assertEquals("CANCELLED", recovered.history(task.id).single().status)
        assertNull(recovered.getById(task.id)!!.activeRunId)
    }
    @Test fun historyKeepsTwentyTerminalRunsAndAllPendingRuns() = runBlocking {
        val task = create()
        repeat(25) { index ->
            val run = repository.runNow(task.id)
            repository.finish(run, ScheduledTaskRunStatus.SUCCESS, preview = "Result $index")
            now++
        }
        repository.runNow(task.id)
        val history = repository.history(task.id)
        assertEquals(21, history.size)
        assertEquals(1, history.count { it.status == "WAITING_IDLE" })
    }
    @Test fun editingOrDeletingAnActiveRunRequiresCancellationButDisablingDoesNot() = runBlocking {
        val task = create()
        repository.runNow(task.id)
        val active = repository.getById(task.id)!!
        try { repository.upsert(active.copy(prompt = "changed")); fail("Expected active edit rejection") } catch (_: IllegalArgumentException) {}
        try { repository.delete(active); fail("Expected active delete rejection") } catch (_: IllegalArgumentException) {}
        repository.setEnabled(task.id, false)
        assertFalse(repository.getById(task.id)!!.enabled)
        assertNotNull(repository.getById(task.id)!!.activeRunId)
    }

    @Test fun approvalTimeDoesNotConsumeGenerationBudgetAndWaitingOrderIsDurable() = runBlocking {
        val task = create()
        val pending = repository.runNow(task.id)
        assertTrue(repository.bindConversation(pending, "budget-chat"))
        val running = repository.getById(task.id)!!
        now += 2_000
        repository.finish(running, ScheduledTaskRunStatus.WAITING_APPROVAL)
        now += 3600_000
        repository.resumeConversation("budget-chat")
        assertEquals(598_000L, repository.remainingGenerationMs(repository.getById(task.id)!!))
        now += 3_000
        repository.finish(running, ScheduledTaskRunStatus.SUCCESS)
        assertEquals(5_000L, repository.history(task.id).single().generationMs)
        val second = task.copy(id = "second", name = "Second", createdAt = now, updatedAt = now)
        repository.upsert(second)
        repository.runNow(task.id); now++
        repository.runNow(second.id)
        assertEquals(listOf(task.id, second.id), repository.waitingRuns().map { it.taskId })
    }

    @Test fun aLongWaitingRunIsRetainedWhenItFinallyEndsAfterManySkippedSlots() = runBlocking {
        val task = create()
        val pending = repository.runNow(task.id)
        repeat(25) { index ->
            now++
            db.scheduledTaskRunDao().upsert(me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity(
                "skipped-$index", task.id, "SCHEDULED", now, endedAt = now, status = "SKIPPED"))
        }
        now++
        repository.finish(pending, ScheduledTaskRunStatus.SUCCESS)
        val history = repository.history(task.id)
        assertEquals(20, history.size)
        assertTrue(history.any { it.id == pending.activeRunId && it.status == "SUCCESS" })
    }

}
