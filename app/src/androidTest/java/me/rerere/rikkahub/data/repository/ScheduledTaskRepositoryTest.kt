package me.rerere.rikkahub.data.repository

import android.content.Context
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Tests actual Room transactions and WorkManager identity without making model requests. */
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
        repository = ScheduledTaskRepository(context, db) { now }
    }
    @After fun teardown() { db.close(); WorkManagerTestInitHelper.closeWorkDatabase() }
    private fun task() = ScheduledTaskEntity("test-task", "Task", "Prompt", "assistant-a", "INTERVAL", intervalMinutes = 15,
        createdAt = now, updatedAt = now, revision = "initial")
    private suspend fun create() = task().also { repository.upsert(it) }.let { repository.getById(it.id)!! }
    private fun work() = WorkManager.getInstance(context).getWorkInfosByTag(ScheduledTaskScheduler.TAG).get()

    @Test fun disablingAndDeletingCancelPendingWork() = runBlocking {
        val task = create()
        assertEquals(1, work().count { it.state == WorkInfo.State.ENQUEUED })
        repository.setEnabled(task.id, false)
        assertEquals(0, work().count { it.state == WorkInfo.State.ENQUEUED })
        repository.setEnabled(task.id, true)
        repository.delete(repository.getById(task.id)!!)
        assertNull(repository.getById(task.id))
        assertEquals(0, work().count { it.state == WorkInfo.State.ENQUEUED })
    }
    @Test fun startupCancelsLegacyManualRequests() = runBlocking {
        val task = create()
        val request = OneTimeWorkRequestBuilder<me.rerere.rikkahub.worker.ScheduledTaskWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(workDataOf("task_id" to task.id, "manual" to true)).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("scheduled_task_v2_${task.id}_manual", ExistingWorkPolicy.KEEP, request).result.get()
        ScheduledTaskRepository(context, db) { now }.initialize()
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
            repository.claim(task.id, task.revision, now, token, "conversation-$token")
        } }.awaitAll().filterNotNull()
        assertEquals(1, runs.size)
        assertFalse(repository.finish(runs.single().copy(activeRunId = "obsolete"), ScheduledTaskRunStatus.SUCCESS))
        assertTrue(repository.finish(runs.single(), ScheduledTaskRunStatus.WAITING_APPROVAL))
        assertNotNull(repository.getById(task.id)!!.activeRunId)
        repository.resumeConversation(runs.single().activeConversationId!!)
        assertEquals("RUNNING", repository.getById(task.id)!!.lastRunStatus)
        assertTrue(repository.finish(runs.single(), ScheduledTaskRunStatus.SUCCESS))
        assertNull(repository.getById(task.id)!!.activeRunId)
        assertNull(repository.claim(task.id, task.revision, now, "replay", "new-conversation"))
    }
    @Test fun restoredInterruptedRunIsFailedAndItsConsumedSlotIsNotSentAgain() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = repository.claim(task.id, task.revision, now, "interrupted", "conversation")!!
        val recovered = ScheduledTaskRepository(context, db) { now }
        recovered.initialize()
        assertEquals("FAILED", recovered.getById(task.id)!!.lastRunStatus)
        assertNull(recovered.getById(task.id)!!.activeRunId)
        assertNull(recovered.claim(task.id, run.revision, now, "again", "again-conversation"))
    }
    @Test fun newProcessKeepsExistingPendingWorkInsteadOfDuplicatingIt() = runBlocking {
        val task = create()
        ScheduledTaskRepository(context, db) { now }.initialize()
        assertEquals(1, work().count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(task.nextRunAt, repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun restoredFutureDailySlotIsRecalibratedBeforeEnqueue() = runBlocking {
        val task = create().copy(scheduleType = "DAILY", nextRunAt = now + 3 * 86400000L)
        db.scheduledTaskDao().upsert(task)
        val recovered = ScheduledTaskRepository(context, db) { now }
        recovered.initialize()
        val updated = recovered.getById(task.id)!!
        assertEquals(ScheduledTaskSchedule.next(task, now), updated.nextRunAt)
        assertNotEquals(task.revision, updated.revision)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledTaskScheduler.workName(updated)).get().size)
    }
    @Test fun longRunCompletionSkipsExpiredPeriods() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = repository.claim(task.id, task.revision, now, "long-run", "long-conversation")!!
        now += 50 * 60000L
        repository.finish(run, ScheduledTaskRunStatus.SUCCESS)
        assertEquals(ScheduledTaskSchedule.next(task, now), repository.getById(task.id)!!.nextRunAt)
    }
    @Test fun storedApprovalsSurviveProcessRecoveryAndRemainBoundToTheirConversation() = runBlocking {
        val task = create(); now = task.nextRunAt!!
        val run = repository.claim(task.id, task.revision, now, "waiting-request", "waiting-conversation")!!
        db.conversationDao().insert(ConversationEntity("waiting-conversation", task.assistantId, "Task", "[]", now, now, "[]", false))
        val message = me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(
            me.rerere.ai.ui.UIMessagePart.Tool("call", "test_tool", "{}", approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)))
        db.messageNodeDao().insert(MessageNodeEntity("pending-node", "waiting-conversation", 0,
            me.rerere.rikkahub.utils.JsonInstant.encodeToString(listOf(message)), 0))
        repository.attachConversation(run)
        repository.finish(run, ScheduledTaskRunStatus.WAITING_APPROVAL)
        val recovered = ScheduledTaskRepository(context, db) { now }
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
}
