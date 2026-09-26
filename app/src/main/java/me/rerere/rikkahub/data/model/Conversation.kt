package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // Internal checkpoints; the latest valid consolidated checkpoint replaces the request prefix.
    val compressionSummaries: List<CompressionSummary> = emptyList(),
    val modelOverrideId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun activeCompression(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryNodeId = checkpoint.boundaryNodeId ?: return null
        if (messageNodes.none { it.id == boundaryNodeId }) return null
        val expectedFingerprint = checkpoint.sourceFingerprint ?: return checkpoint
        return checkpoint.takeIf { compressionSourceFingerprint(boundaryNodeId) == expectedFingerprint }
    }

    /** A request may use only checkpoints whose source can still be verified. */
    fun activeCompressionForRequest(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryNodeId = checkpoint.boundaryNodeId ?: return null
        val expectedFingerprint = checkpoint.sourceFingerprint ?: return null
        return checkpoint.takeIf {
            messageNodes.any { node -> node.id == boundaryNodeId } &&
                compressionSourceFingerprint(boundaryNodeId) == expectedFingerprint
        }
    }

    /** Hashes the source history, including branch selection, alternates, and attachment references. */
    fun compressionSourceFingerprint(boundaryNodeId: Uuid): String? {
        val boundaryIndex = messageNodes.indexOfFirst { it.id == boundaryNodeId }
        if (boundaryIndex < 0) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val delimiter = byteArrayOf(0)
        fun update(value: String) {
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(delimiter)
        }
        for (index in 0..boundaryIndex) {
            val node = messageNodes[index]
            update(node.id.toString())
            update(node.selectIndex.toString())
            node.messages.forEach { message -> update(message.toString()) }
        }
        val hash = digest.digest()
        val hex = "0123456789abcdef"
        return buildString(hash.size * 2) {
            hash.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]).append(hex[value and 0x0f])
            }
        }
    }

    fun requestWindowMessages(): List<UIMessage> = requestContextForGeneration().messages

    /** Compatibility alias for callers that also need streamed-message source mapping. */
    internal fun requestWindowForGeneration(messageRange: ClosedRange<Int>? = null): ConversationRequestWindow =
        requestContextForGeneration(messageRange)

    /** Builds a model request window with the checkpoint separated from persisted chat messages. */
    internal fun requestContextForGeneration(messageRange: ClosedRange<Int>? = null): ConversationRequestWindow {
        val checkpoint = activeCompressionForRequest()
        val boundaryIndex = checkpoint?.boundaryNodeId?.let { boundaryId ->
            messageNodes.indexOfFirst { it.id == boundaryId }.takeIf { it >= 0 }
        }
        val shouldUseCheckpoint = checkpoint != null && boundaryIndex != null &&
            (messageRange == null || messageRange.endInclusive >= boundaryIndex)
        val messages = mutableListOf<UIMessage>()
        val nodeIndexes = mutableListOf<Int?>()

        messageNodes.forEachIndexed { index, node ->
            val belongsToWindow = !shouldUseCheckpoint || index > boundaryIndex
            if (belongsToWindow && (messageRange == null || index in messageRange)) {
                messages += node.messages[node.selectIndex]
                nodeIndexes += index
            }
        }

        val appendNodeIndex = if (messageRange == null) {
            messageNodes.size
        } else {
            (messageRange.endInclusive.toLong() + 1L)
                .coerceIn(0L, messageNodes.size.toLong())
                .toInt()
        }
        return ConversationRequestWindow(
            messages = messages,
            sourceNodeIndexes = nodeIndexes,
            appendNodeIndex = appendNodeIndex,
            checkpointContent = checkpoint?.content?.takeIf { shouldUseCheckpoint },
        )
    }

    /** Applies streamed request-window messages without persisting synthetic checkpoint messages. */
    internal fun updateRequestWindowMessages(
        requestWindow: ConversationRequestWindow,
        messages: List<UIMessage>,
    ): Conversation {
        val newNodes = messageNodes.toMutableList()
        messages.forEachIndexed { responseIndex, message ->
            val nodeIndex = if (responseIndex < requestWindow.sourceNodeIndexes.size) {
                requestWindow.sourceNodeIndexes[responseIndex] ?: return@forEachIndexed
            } else {
                requestWindow.appendNodeIndex + responseIndex - requestWindow.sourceNodeIndexes.size
            }

            val node = newNodes.getOrNull(nodeIndex)
            if (node == null) {
                if (nodeIndex == newNodes.size) newNodes += message.toMessageNode()
                return@forEachIndexed
            }

            val updatedMessages = node.messages.toMutableList()
            val existingIndex = updatedMessages.indexOfFirst { it.id == message.id }
            val selectedIndex = if (existingIndex >= 0) {
                updatedMessages[existingIndex] = message
                node.selectIndex
            } else {
                updatedMessages += message
                updatedMessages.lastIndex
            }
            newNodes[nodeIndex] = node.copy(messages = updatedMessages, selectIndex = selectedIndex)
        }
        return copy(messageNodes = newNodes)
    }

    fun windowNodes(): List<MessageNode> {
        val checkpoint = activeCompressionForRequest() ?: return messageNodes
        val boundaryIndex = messageNodes.indexOfFirst { it.id == checkpoint.boundaryNodeId }
        if (boundaryIndex < 0) return messageNodes
        return messageNodes.drop(boundaryIndex + 1)
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

internal class ConversationRequestWindow(
    val messages: List<UIMessage>,
    internal val sourceNodeIndexes: List<Int?>,
    internal val appendNodeIndex: Int,
    val checkpointContent: String? = null,
)

@Serializable
data class CompressionSummary(
    val id: Uuid = Uuid.random(),
    val content: String = "",
    val messageCount: Int = 0,
    val boundaryNodeId: Uuid? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    /** Null identifies a legacy checkpoint whose source cannot be verified for requests. */
    val sourceFingerprint: String? = null,
)

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
