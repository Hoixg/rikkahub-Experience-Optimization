package me.rerere.rikkahub.utils

import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlinx.datetime.toKotlinLocalDateTime

fun parseContextLengthInput(text: String): Int? {
    val normalized = text.trim().lowercase().replace(" ", "")
    if (normalized.isEmpty()) return null
    val value = when {
        normalized.endsWith("m") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000_000).toLong()
        normalized.endsWith("k") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000).toLong()
        else -> normalized.toLongOrNull() ?: return null
    }
    return value.takeIf { it > 0 && it <= 100_000_000 }?.toInt()
}

fun formatContextLength(tokens: Int?): String = when {
    tokens == null -> ""
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

const val AUTO_COMPACT_THRESHOLD_RATIO = 0.8f

private const val DEFAULT_CONTEXT_LENGTH = 256 * 1024

fun Model?.effectiveContextLength(): Int =
    this?.contextLength?.takeIf { it > 0 }
        ?: this?.modelId
            ?.let { ModelRegistry.MODEL_CONTEXT_LENGTH.getData(it) }
            ?.takeIf { value -> value > 0 }
            ?: DEFAULT_CONTEXT_LENGTH

fun Settings.getConversationChatModel(conversation: Conversation): Model? {
    conversation.modelOverrideId?.let { return findModelById(it) }
    val assistant = getAssistantById(conversation.assistantId) ?: getCurrentAssistant()
    return findModelById(assistant.chatModelId) ?: findModelById(chatModelId)
}

fun shouldAutoCompact(enabled: Boolean, usedTokens: Int, windowTokens: Int): Boolean =
    enabled && windowTokens > 0 && usedTokens >= (windowTokens * AUTO_COMPACT_THRESHOLD_RATIO).toInt()

private fun java.time.Instant.toCheckpointLocalDateTime(): kotlinx.datetime.LocalDateTime =
    atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toKotlinLocalDateTime()

fun Conversation.estimateWindowTokens(model: Model?): Int {
    val window = requestWindowMessages()
    val lastAssistant = window.lastOrNull { it.role == MessageRole.ASSISTANT }
    val usageTokens = lastAssistant?.usage?.promptTokens ?: 0
    val checkpoint = activeCompression()
    val finishedAt = lastAssistant?.finishedAt ?: lastAssistant?.createdAt
    val usageUsable = usageTokens > 0 && lastAssistant != null && (
        checkpoint == null ||
            (finishedAt ?: lastAssistant.createdAt) > checkpoint.createdAt.toCheckpointLocalDateTime()
        )
    return if (usageUsable) usageTokens else estimateTokenCount(window)
}

fun estimateTokenCount(messages: List<UIMessage>): Int {
    var cjk = 0
    var other = 0
    for (message in messages) {
        for (part in message.parts) {
            val text = when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                else -> continue
            }
            for (ch in text) {
                if (ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af') {
                    cjk++
                } else {
                    other++
                }
            }
        }
    }
    return (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(0)
}
