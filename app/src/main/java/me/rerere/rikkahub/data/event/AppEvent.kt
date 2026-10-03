package me.rerere.rikkahub.data.event

import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
        val scheduledTask: Boolean = false,
    ) : AppEvent()

    data class ScheduledTaskEnded(val conversationId: Uuid?, val taskName: String, val status: String, val preview: String, val taskId: String = "", val runId: String = "", val notify: Boolean = true, val showPreview: Boolean = true) : AppEvent()

    data class ChatTurnFinished(val conversationId: Uuid) : AppEvent()
}
