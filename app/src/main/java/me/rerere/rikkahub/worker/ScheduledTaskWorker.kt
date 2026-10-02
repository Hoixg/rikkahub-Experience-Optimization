package me.rerere.rikkahub.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunStatus
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val repository: ScheduledTaskRepository by inject()
    private val chatService: ChatService by inject()
    private val eventBus: AppEventBus by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        // Requests left over from an earlier build must not restart the removed manual execution.
        if (inputData.getBoolean("manual", false)) return Result.success()
        var claimed: ScheduledTaskEntity? = null
        val conversationId = Uuid.random()
        try {
            val scheduledAt = inputData.getLong("scheduled_at", 0L)
            if (scheduledAt > System.currentTimeMillis()) return Result.retry()
            claimed = repository.claim(taskId, inputData.getString("revision").orEmpty(), scheduledAt,
                id.toString(), conversationId.toString()) ?: return Result.success()
            setForeground(foreground(claimed, conversationId))
            chatService.startScheduledConversation(claimed, conversationId).await()
            return Result.success()
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (claimed != null) {
                    chatService.stopGeneration(conversationId)
                    val status = if (e is CancellationException) ScheduledTaskRunStatus.CANCELLED else ScheduledTaskRunStatus.FAILED
                    if (repository.finish(claimed, status, e.message.orEmpty())) {
                        eventBus.emit(AppEvent.ScheduledTaskEnded(conversationId.takeIf { repository.getById(taskId)?.lastConversationId == it.toString() }, claimed.name, status.name, e.message.orEmpty()))
                    }
                }
            }
            if (e is CancellationException) throw e
            return Result.failure()
        } finally {
            withContext(NonCancellable) {
                chatService.releaseScheduledWorker(conversationId)
                repository.reschedule(taskId)
            }
        }
    }

    private fun foreground(task: ScheduledTaskEntity, conversationId: Uuid): ForegroundInfo {
        val notificationId = 20_000 + (id.hashCode() and 0xffff)
        val intent = Intent(applicationContext, RouteActivity::class.java).putExtra("conversationId", conversationId.toString())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, notificationId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle(task.name).setContentText("定时任务正在执行")
            .setContentIntent(pending).setOngoing(true).setSilent(true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}
