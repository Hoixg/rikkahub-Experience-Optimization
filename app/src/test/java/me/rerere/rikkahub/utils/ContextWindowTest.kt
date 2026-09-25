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
import me.rerere.rikkahub.utils.JsonInstant
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
        val model = Model(modelId = "context-test-model")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(node(message("new", MessageRole.ASSISTANT).copy(modelId = model.id))),
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

        assertEquals(123, usageConversation.estimateWindowTokens(model))
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

        assertEquals(estimateTokenCount(conversation.requestWindowMessages()), conversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }

    @Test
    fun assistantUsageFromAnotherModelFallsBackToLocalEstimate() {
        val usageMessage = message("new", MessageRole.ASSISTANT).copy(
            usage = TokenUsage(promptTokens = 10_000),
            modelId = Uuid.random(),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(node(usageMessage)),
        )

        assertEquals(estimateTokenCount(conversation.requestWindowMessages()), conversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }

    @Test
    fun pendingInputIsIncludedInThresholdCalculation() {
        val currentTokens = 75
        val pendingTokens = estimateTokenCount(listOf(message("x".repeat(100))))

        assertFalse(shouldAutoCompact(true, currentTokens, 100))
        assertTrue(shouldAutoCompact(true, currentTokens + pendingTokens, 100))
        assertTrue(
            estimateTokenCount(listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///image.png"))))) >= 1_024,
        )
    }

    @Test
    fun shortConversationCanCompactAtLeastOneNode() {
        val nodes = listOf(node(message("first")), node(message("last")))

        assertEquals(listOf(nodes.first()), selectNodesForCompaction(nodes, keepBudgetTokens = 0))
        assertEquals(listOf(nodes.first()), selectNodesForCompaction(nodes, keepBudgetTokens = Int.MAX_VALUE))
        assertEquals(1, selectNodesForCompaction(nodes, keepBudgetTokens = Int.MAX_VALUE).size)
        assertTrue(selectNodesForCompaction(listOf(nodes.first()), keepBudgetTokens = 0).isNotEmpty())
    }

    @Test
    fun compactionInputKeepsToolsAndAttachmentMetadataButNotLocalPaths() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Document("file:///private/report.pdf", "report.pdf", "application/pdf"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    input = "{\"path\":\"notes.txt\"}",
                    output = listOf(UIMessagePart.Text("file contents")),
                ),
            ),
        )

        val formatted = message.toCompactionText()

        assertTrue(formatted.contains("report.pdf"))
        assertTrue(formatted.contains("application/pdf"))
        assertTrue(formatted.contains("read_file"))
        assertTrue(formatted.contains("notes.txt"))
        assertTrue(formatted.contains("file contents"))
        assertFalse(formatted.contains("file:///private"))
    }

    @Test
    fun compactionChunksRespectEstimatedTokenBudgetAndPreserveContent() {
        val text = "中文内容和 latin text ".repeat(800)
        val chunks = chunkCompactionTexts(listOf(text), tokenBudget = 128)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { estimateTextTokenCount(it) <= 128 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun priorCheckpointIsAddedToOnlyTheFinalMergeContext() {
        val prior = "previous checkpoint"
        val context = priorCheckpointMergeContext(prior)
        val sourceChunks = chunkCompactionTexts(listOf("new message 1", "new message 2"), tokenBudget = 32)

        assertTrue(sourceChunks.none { it.contains(prior) })
        assertEquals(1, context.windowed(prior.length).count { it == prior })
        assertEquals("", priorCheckpointMergeContext("  "))
    }

    @Test
    fun oldCheckpointJsonWithoutFingerprintRemainsReadable() {
        val checkpoint = CompressionSummary(content = "legacy summary", boundaryNodeId = Uuid.random())
        val oldJson = JsonInstant.encodeToString(checkpoint)
            .replace(Regex(",\\\"sourceFingerprint\\\":null"), "")

        val decoded = JsonInstant.decodeFromString<CompressionSummary>(oldJson)

        assertEquals("legacy summary", decoded.content)
        assertNull(decoded.sourceFingerprint)
    }

    @Test
    fun sourceFingerprintDetectsBranchAndAttachmentChangesButAcceptsLegacyCheckpoint() {
        val first = node(
            message("selected").copy(parts = listOf(UIMessagePart.Text("selected"), UIMessagePart.Image("file:///one.png"))),
            message("alternate").copy(parts = listOf(UIMessagePart.Text("alternate"), UIMessagePart.Image("file:///two.png"))),
        )
        val later = node(message("new"))
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, later))
        val fingerprint = initial.compressionSourceFingerprint(first.id)!!
        val checkpoint = CompressionSummary(
            content = "summary",
            boundaryNodeId = first.id,
            sourceFingerprint = fingerprint,
        )
        val withCheckpoint = initial.copy(compressionSummaries = listOf(checkpoint))

        assertEquals(checkpoint, withCheckpoint.activeCompression())
        assertEquals(
            checkpoint,
            withCheckpoint.copy(messageNodes = listOf(first, later, node(message("appended")))).activeCompression(),
        )
        assertTrue(withCheckpoint.copy(messageNodes = listOf(first.copy(selectIndex = 1), later)).activeCompression() == null)
        val changedAlternate = first.copy(messages = first.messages + message("new branch"))
        assertTrue(withCheckpoint.copy(messageNodes = listOf(changedAlternate, later)).activeCompression() == null)
        val changedAttachment = first.copy(messages = first.messages.mapIndexed { index, item ->
            if (index == 0) item.copy(parts = listOf(UIMessagePart.Image("file:///changed.png"))) else item
        })
        assertTrue(withCheckpoint.copy(messageNodes = listOf(changedAttachment, later)).activeCompression() == null)

        val legacy = withCheckpoint.copy(compressionSummaries = listOf(checkpoint.copy(sourceFingerprint = null)))
        assertEquals("summary", legacy.activeCompression()?.content)
    }
}
