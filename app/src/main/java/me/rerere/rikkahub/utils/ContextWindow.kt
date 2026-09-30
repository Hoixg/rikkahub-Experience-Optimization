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

const val DEFAULT_AUTO_COMPACTION_THRESHOLD_PERCENT = 80
const val MIN_AUTO_COMPACTION_THRESHOLD_PERCENT = 50
const val MAX_AUTO_COMPACTION_THRESHOLD_PERCENT = 95
const val AUTO_COMPACTION_THRESHOLD_STEP_PERCENT = 5
const val MAX_AUTO_COMPACTION_TOKEN_LIMIT = 100_000_000

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

fun normalizeAutoCompactionThresholdPercent(value: Int): Int {
    val clamped = value.coerceIn(
        MIN_AUTO_COMPACTION_THRESHOLD_PERCENT,
        MAX_AUTO_COMPACTION_THRESHOLD_PERCENT,
    )
    val stepsFromMinimum = (clamped - MIN_AUTO_COMPACTION_THRESHOLD_PERCENT + AUTO_COMPACTION_THRESHOLD_STEP_PERCENT / 2) /
        AUTO_COMPACTION_THRESHOLD_STEP_PERCENT
    return MIN_AUTO_COMPACTION_THRESHOLD_PERCENT + stepsFromMinimum * AUTO_COMPACTION_THRESHOLD_STEP_PERCENT
}

fun normalizeAutoCompactionTokenLimit(value: Int?): Int? =
    value?.takeIf { it in 1..MAX_AUTO_COMPACTION_TOKEN_LIMIT }

internal fun contextUsageDisplayCapacity(tokenLimit: Int?): Int =
    normalizeAutoCompactionTokenLimit(tokenLimit) ?: DEFAULT_CONTEXT_LENGTH

internal fun contextUsageFraction(usedTokens: Int, capacityTokens: Int): Float =
    if (capacityTokens > 0) {
        (usedTokens.toFloat() / capacityTokens).coerceIn(0f, 1f)
    } else {
        0f
    }

fun autoCompactionThresholdTokens(
    windowTokens: Int,
    thresholdPercent: Int = DEFAULT_AUTO_COMPACTION_THRESHOLD_PERCENT,
    tokenLimit: Int? = null,
): Int? {
    if (windowTokens <= 0) return null

    val percentageThreshold = (
        windowTokens.toLong() * normalizeAutoCompactionThresholdPercent(thresholdPercent) / 100L
    ).toInt()
    return minOf(percentageThreshold, normalizeAutoCompactionTokenLimit(tokenLimit) ?: Int.MAX_VALUE)
}

fun shouldAutoCompact(
    enabled: Boolean,
    usedTokens: Int,
    windowTokens: Int,
    thresholdPercent: Int = DEFAULT_AUTO_COMPACTION_THRESHOLD_PERCENT,
    tokenLimit: Int? = null,
): Boolean {
    if (!enabled) return false
    val thresholdTokens = autoCompactionThresholdTokens(windowTokens, thresholdPercent, tokenLimit) ?: return false
    return usedTokens >= thresholdTokens
}

private fun java.time.Instant.toCheckpointLocalDateTime(): kotlinx.datetime.LocalDateTime =
    atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toKotlinLocalDateTime()

fun Conversation.estimateWindowTokens(model: Model?): Int {
    val requestContext = requestContextForGeneration()
    val window = requestContext.messages
    val lastAssistant = window.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
    val usage = lastAssistant?.usage
    val usageTokens = usage?.promptTokens ?: 0
    val checkpoint = activeCompressionForRequest()
    val finishedAt = lastAssistant?.finishedAt ?: lastAssistant?.createdAt
    val usageUsable = usageTokens > 0 && lastAssistant != null && model != null &&
        lastAssistant.modelId == model.id &&
        window.lastOrNull()?.id == lastAssistant.id &&
        (
            checkpoint == null ||
                (finishedAt ?: lastAssistant.createdAt) > checkpoint.createdAt.toCheckpointLocalDateTime()
            )
    return if (usageUsable) {
        val completionTokens = usage?.completionTokens?.takeIf { it > 0 }
            ?: estimateTokenCount(listOf(lastAssistant))
        usageTokens + completionTokens
    } else {
        estimateTokenCount(window) + estimateTextTokenCount(requestContext.checkpointContent.orEmpty())
    }
}

fun estimateTokenCount(messages: List<UIMessage>): Int {
    val textTokens = estimateTextTokenCount(messages.joinToString("\n\n", transform = UIMessage::toCompactionText))
    val attachmentTokens = messages.sumOf { message ->
        message.parts.sumOf(UIMessagePart::estimatedAttachmentTokens)
    }
    return (textTokens.toLong() + attachmentTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun UIMessagePart.estimatedAttachmentTokens(): Int = when (this) {
    is UIMessagePart.Image -> 1_024
    is UIMessagePart.Video -> 2_048
    is UIMessagePart.Audio -> 1_024
    is UIMessagePart.Document -> 2_048
    is UIMessagePart.Tool -> output.sumOf(UIMessagePart::estimatedAttachmentTokens)
    else -> 0
}
