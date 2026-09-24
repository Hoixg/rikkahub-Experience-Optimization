package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.prompts.isCompactionCheckpoint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.CompressionSummary
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

class ContextWindowTest {
    private fun message(text: String, role: MessageRole = MessageRole.USER): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun node(vararg messages: UIMessage): MessageNode =
        MessageNode(messages = messages.toList())

    @Test
    fun contextLengthInputParsing() {
        assertEquals(4096, parseContextLengthInput("4096"))
        assertEquals(256_000, parseContextLengthInput("256k"))
        assertEquals(1_000_000, parseContextLengthInput(" 1M "))
        assertEquals(1_500_000, parseContextLengthInput("1.5m"))
        assertNull(parseContextLengthInput("abc"))
        assertNull(parseContextLengthInput("0"))
        assertNull(parseContextLengthInput("100000001"))
    }

    @Test
    fun contextLengthFormatting() {
        assertEquals("", formatContextLength(null))
        assertEquals("4.1K", formatContextLength(4096))
        assertEquals("256K", formatContextLength(256_000))
        assertEquals("1M", formatContextLength(1_000_000))
        assertEquals("1.5M", formatContextLength(1_500_000))
    }

    @Test
    fun effectiveWindowPrefersUserConfiguredThenNullModelDefault() {
        val modelId = "context-indicator-model"
        assertEquals(8_192, Model(modelId = modelId, contextLength = 8_192).effectiveContextLength())
        assertEquals(262_144, Model(modelId = modelId).effectiveContextLength())
        assertEquals(262_144, null.effectiveContextLength())
    }

    @Test
    fun autoCompactionOnlyRunsWhenEnabledAndAtThreshold() {
        assertFalse(shouldAutoCompact(enabled = false, usedTokens = 100, windowTokens = 100))
        assertFalse(shouldAutoCompact(enabled = true, usedTokens = 79, windowTokens = 100))
        assertTrue(shouldAutoCompact(enabled = true, usedTokens = 80, windowTokens = 100))
        assertFalse(shouldAutoCompact(enabled = true, usedTokens = 1, windowTokens = 0))
    }

    @Test
    fun autoCompactionIsEnabledByDefaultButCanBeDisabled() {
        assertTrue(Settings().enableAutoCompaction)
        assertFalse(Settings(enableAutoCompaction = false).enableAutoCompaction)
    }

    @Test
    fun requestWindowUsesCheckpointBoundary() {
        val first = node(message("old"), message("old-selected"))
        val second = node(message("new", MessageRole.ASSISTANT))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(first, second),
            compressionSummaries = listOf(CompressionSummary(content = "summary", boundaryNodeId = first.id)),
        )

        val window = conversation.requestWindowMessages()

        assertEquals(2, window.size)
        assertTrue(window.first().isSynthetic)
        assertTrue(window.first().isCompactionCheckpoint())
        assertEquals("new", (window.last().parts.single() as UIMessagePart.Text).text)
        assertEquals(second.currentMessage, window.last())
    }

    @Test
    fun requestWindowFallsBackWhenBoundaryIsMissing() {
        val first = node(message("old"))
        val second = node(message("new", MessageRole.ASSISTANT))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(first, second),
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = Uuid.random()),
            ),
        )

        assertTrue(conversation.activeCompression() == null)
        assertEquals(conversation.currentMessages, conversation.requestWindowMessages())
    }

    @Test
    fun laterAssistantUsageIsUsableAfterCheckpoint() {
        val checkpointAt = LocalDateTime(2026, 1, 1, 10, 0)
            .toInstant(TimeZone.currentSystemDefault())
            .toJavaInstant()
        val later = LocalDateTime(2026, 1, 1, 11, 0)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(node(message("new", MessageRole.ASSISTANT))),
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = Uuid.random(), createdAt = checkpointAt),
            ),
        )
        val usageMessage = conversation.currentMessages.single().copy(
            createdAt = later,
            finishedAt = later,
            usage = TokenUsage(promptTokens = 123),
        )
        val usageConversation = conversation.copy(
            messageNodes = listOf(node(usageMessage)),
        )

        assertEquals(123, usageConversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }

    @Test
    fun staleAssistantUsageFallsBackToLocalEstimate() {
        val checkpointAt = LocalDateTime(2026, 1, 1, 11, 0)
            .toInstant(TimeZone.currentSystemDefault())
            .toJavaInstant()
        val earlier = LocalDateTime(2026, 1, 1, 10, 0)
        val usageMessage = message("new", MessageRole.ASSISTANT).copy(
            createdAt = earlier,
            finishedAt = earlier,
            usage = TokenUsage(promptTokens = 10_000),
        )
        val staleNode = node(usageMessage)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(staleNode),
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = staleNode.id, createdAt = checkpointAt),
            ),
        )

        assertEquals(88, conversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }
}
