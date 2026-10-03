package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

/**
 * 订阅 [AppEventBus]，发送聊天完成及定时任务结果通知。
 * 生成期间的必要通知由前台服务管理。
 */
class ChatNotificationManager(
    private val context: Application,
    private val appScope: AppScope,
    eventBus: AppEventBus,
) {
    private val isForeground = MutableStateFlow(false)

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    is AppEvent.ScheduledTaskEnded -> handleScheduledTaskEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        if (event.scheduledTask) return
        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    private fun handleScheduledTaskEnded(event: AppEvent.ScheduledTaskEnded) {
        if (!event.notify) return
        val state = when (event.status) {
            "SUCCESS" -> "已完成"
            "WAITING_APPROVAL" -> "等待审批"
            "CANCELLED" -> "已取消"
            else -> "执行失败"
        }
        context.sendNotification(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, 100_000 + (event.runId.ifBlank { event.taskName }.hashCode() and 0xffff)) {
            title = "${event.taskName} · $state"
            content = if (event.showPreview) event.preview.ifBlank { state }.take(150) else state
            autoCancel = true
            useDefaults = true
            contentIntent = event.conversationId?.let { getPendingIntent(context, it) } ?: PendingIntent.getActivity(
                context, 2004, Intent(context, RouteActivity::class.java).putExtra("openScheduledTasks", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
