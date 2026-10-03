package me.rerere.rikkahub.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_ONGOING_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.data.event.*
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.worker.ScheduledTaskScheduler
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

/** Short-lived foreground owner of scheduled and manual generations, never of idle waiting. */
class ScheduledTaskExecutionService : Service() {
    companion object {
        const val NOTIFICATION_ID = 2005
        private const val ACTION_RESUME = "me.rerere.rikkahub.action.RESUME_SCHEDULED_TASKS"
        @Volatile var processingAlarm = false
            private set
        fun intent(context: Context, taskId: String, revision: String, dueAt: Long) =
            Intent(context, ScheduledTaskExecutionService::class.java).putExtra("task_id", taskId)
                .putExtra(ScheduledTaskScheduler.EXTRA_REVISION, revision)
                .putExtra(ScheduledTaskScheduler.EXTRA_SCHEDULED_AT, dueAt)
        fun resume(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScheduledTaskExecutionService::class.java).setAction(ACTION_RESUME))
        }
    }
    private val repository: ScheduledTaskRepository by inject()
    private val chat: ChatService by inject()
    private val events: AppEventBus by inject()
    private val appScope: AppScope by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dispatch = Mutex()
    private val inFlight = mutableSetOf<String>()
    private var handlers = 0
    private var generating = 0
    private var closing = false
    private val lease by lazy { GenerationWakeLease(this, "scheduled", scope) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        handlers++
        processingAlarm = true
        try {
            val pending = PendingIntent.getActivity(this, NOTIFICATION_ID,
                Intent(this, RouteActivity::class.java).putExtra("openScheduledTasks", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHAT_ONGOING_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle(getString(R.string.app_name))
                .setContentText("正在处理助手任务").setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true).setSilent(true).build()
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
            BackgroundRuntime.service("scheduled", true)
        } catch (e: Exception) {
            android.util.Log.e("ScheduledTaskExecution", "Unable to start foreground service", e)
            appScope.launch {
                val task = claimIntent(intent)
                if (task != null && repository.finish(task, ScheduledTaskRunStatus.FAILED, "无法启动前台服务"))
                    events.emit(AppEvent.ScheduledTaskEnded(null, task.name, "FAILED", "无法启动前台服务", task.id, task.activeRunId.orEmpty(), task.notify, task.showPreview))
            }
            handlers--; stopIfIdle(); return START_NOT_STICKY
        }
        scope.launch {
            try {
                claimIntent(intent)
                dispatch.withLock {
                    repository.waitingRuns().forEach { run ->
                        if (inFlight.add(run.id)) scope.launch {
                            try { repository.getById(run.taskId)?.takeIf { it.activeRunId == run.id }?.let { execute(it) } }
                            finally { inFlight.remove(run.id); stopIfIdle() }
                        }
                    }
                    repository.refreshResumeAlarm()
                }
            } catch (e: Exception) { android.util.Log.e("ScheduledTaskExecution", "Unable to dispatch tasks", e) }
            finally { handlers--; stopIfIdle() }
        }
        return START_NOT_STICKY
    }
    private suspend fun claimIntent(intent: Intent): ScheduledTaskEntity? {
        val id = intent.getStringExtra("task_id") ?: return null
        val revision = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_REVISION) ?: return null
        val due = intent.getLongExtra(ScheduledTaskScheduler.EXTRA_SCHEDULED_AT, 0)
        if (due <= 0) return null
        return repository.claim(id, revision, due, Uuid.random().toString(), Uuid.random().toString())
    }
    private suspend fun execute(task: ScheduledTaskEntity) {
        var conversationId: Uuid? = null
        var acquired = false
        var ownedCompletion: Deferred<String>? = null
        try {
            val completion = chat.startScheduledConversation(task, Uuid.random()) ?: return
            ownedCompletion = completion
            conversationId = repository.getById(task.id)?.activeConversationId?.let(Uuid::parse)
            generating++; acquired = true; lease.update(generating)
            completion.await()
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val message = if (e is TimeoutCancellationException) "任务执行超时" else e.message.orEmpty()
                android.util.Log.e("ScheduledTaskExecution", "Task execution ended: $message", e)
                conversationId?.let { chat.stopGeneration(it) }
                val status = if (e is CancellationException && e !is TimeoutCancellationException) ScheduledTaskRunStatus.CANCELLED else ScheduledTaskRunStatus.FAILED
                if (repository.finish(task, status, message)) events.emit(AppEvent.ScheduledTaskEnded(conversationId,
                    task.name, status.name, message, task.id, task.activeRunId.orEmpty(), task.notify, task.showPreview))
            }
        } finally {
            conversationId?.let { chat.releaseScheduledWorker(it, ownedCompletion) }
            if (acquired) { generating--; if (closing) lease.close() else lease.update(generating) }
            withContext(NonCancellable) { repository.refreshResumeAlarm() }
        }
    }
    private fun stopIfIdle() {
        if (handlers == 0 && inFlight.isEmpty()) {
            processingAlarm = false
            lease.close(); BackgroundRuntime.service("scheduled", false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        closing = true
        lease.close()
        android.util.Log.e("ScheduledTaskExecution", "Foreground service timed out (type=$fgsType)")
        scope.cancel(); stopSelf()
    }
    override fun onDestroy() {
        closing = true
        processingAlarm = false
        scope.cancel(); lease.close(); BackgroundRuntime.service("scheduled", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
