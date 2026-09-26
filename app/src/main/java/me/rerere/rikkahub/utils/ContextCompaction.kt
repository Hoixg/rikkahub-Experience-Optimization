package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

/** A provider-safe transcript used only for building automatic context checkpoints. */
internal fun UIMessage.toCompactionText(): String = buildString {
    append("[").append(role.name).appendLine("]")
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> appendLine(part.text)
            is UIMessagePart.Image -> appendLine("[Image attachment]")
            is UIMessagePart.Video -> appendLine("[Video attachment]")
            is UIMessagePart.Audio -> appendLine("[Audio attachment]")
            is UIMessagePart.Document -> appendLine("[Document attachment: ${part.fileName}; ${part.mime}]")
            is UIMessagePart.Reasoning -> appendLine("[Internal reasoning omitted]")
            UIMessagePart.Search -> appendLine("[Search used]")
            is UIMessagePart.ToolCall -> {
                appendLine("[Tool call: ${part.toolName}]")
                appendLine("Arguments: ${part.arguments}")
                appendLine("Approval: ${part.approvalState}")
            }
            is UIMessagePart.ToolResult -> {
                appendLine("[Tool result: ${part.toolName}]")
                appendLine("Arguments: ${part.arguments}")
                appendLine("Result: ${part.content}")
            }
            is UIMessagePart.ServerTool -> {
                appendLine("[Server tool: ${part.toolName} (${part.status})]")
                part.input?.let { appendLine("Input: $it") }
                part.output?.let { appendLine("Output: $it") }
            }
            is UIMessagePart.Tool -> {
                appendLine("[Tool call: ${part.toolName}]")
                appendLine("Arguments: ${part.input}")
                appendLine("Approval: ${part.approvalState}")
                part.output.forEach { output ->
                    append("Tool output: ")
                    appendLine(output.toCompactionPartText())
                }
            }
        }
    }
}.trim()

private fun UIMessagePart.toCompactionPartText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Image -> "[Image attachment]"
    is UIMessagePart.Video -> "[Video attachment]"
    is UIMessagePart.Audio -> "[Audio attachment]"
    is UIMessagePart.Document -> "[Document attachment: $fileName; $mime]"
    is UIMessagePart.Reasoning -> "[Internal reasoning omitted]"
    UIMessagePart.Search -> "[Search used]"
    is UIMessagePart.ToolCall -> "[Tool call: $toolName] Arguments: $arguments"
    is UIMessagePart.ToolResult -> "[Tool result: $toolName] Arguments: $arguments Result: $content"
    is UIMessagePart.ServerTool -> "[Server tool: $toolName ($status)] Input: $input Output: $output"
    is UIMessagePart.Tool -> buildString {
        append("[Tool call: ").append(toolName).append("] Arguments: ").append(input)
        output.forEach { append("\nResult: ").append(it.toCompactionPartText()) }
    }
}

/** The same conservative approximation is used by the trigger and the chunk planner. */
internal fun estimateTextTokenCount(text: String): Int {
    var units = 0L
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        units += if (codePoint.isCjk()) 8 else 3 // twelfths of an estimated token
        offset += Character.charCount(codePoint)
    }
    return ((units + 11) / 12).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun chunkCompactionTexts(texts: List<String>, tokenBudget: Int): List<String> {
    require(tokenBudget > 0)
    val budgetUnits = tokenBudget.toLong() * 12
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentUnits = 0L

    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.setLength(0)
            currentUnits = 0
        }
    }

    texts.forEachIndexed { index, text ->
        val pieces = if (index == 0) listOf(text) else listOf("\n\n", text)
        pieces.forEach { piece ->
            var offset = 0
            while (offset < piece.length) {
                val codePoint = piece.codePointAt(offset)
                val charCount = Character.charCount(codePoint)
                val weight = if (codePoint.isCjk()) 8L else 3L
                if (current.isNotEmpty() && currentUnits + weight > budgetUnits) flush()
                current.appendCodePoint(codePoint)
                currentUnits += weight
                // A code point is never split; one oversized symbol cannot exceed a practical budget.
                if (currentUnits >= budgetUnits) flush()
                offset += charCount
            }
        }
    }
    flush()
    return chunks
}

internal fun selectNodesForCompaction(nodes: List<MessageNode>, keepBudgetTokens: Int): List<MessageNode> {
    if (nodes.isEmpty()) return emptyList()
    var keptCount = 0
    var keptTokens = 0
    for (node in nodes.asReversed()) {
        val nodeTokens = estimateTokenCount(listOf(node.currentMessage))
        if (keptCount > 0 && keptTokens + nodeTokens > keepBudgetTokens) break
        keptTokens += nodeTokens
        keptCount++
    }
    // A triggered compact must always include at least one node, even for a one-message conversation.
    return nodes.dropLast(keptCount.coerceAtMost((nodes.size - 1).coerceAtLeast(0)))
}

/** Selects an old prefix while retaining the newest user turn and keeping its messages together. */
internal fun selectCompactionPrefixKeepingLatestTurn(
    nodes: List<MessageNode>,
    keepBudgetTokens: Int,
): List<MessageNode> {
    if (nodes.isEmpty()) return emptyList()
    val latestUserIndex = nodes.indexOfLast { it.currentMessage.role == MessageRole.USER }
    val minimumKeepStart = if (latestUserIndex >= 0) latestUserIndex else nodes.lastIndex
    var keepStart = nodes.size
    var keptTokens = 0

    for (index in nodes.lastIndex downTo minimumKeepStart) {
        val nodeTokens = estimateTokenCount(listOf(nodes[index].currentMessage))
        if (keepStart < nodes.size && keptTokens + nodeTokens > keepBudgetTokens) break
        keepStart = index
        keptTokens += nodeTokens
    }

    val alignedKeepStart = (keepStart downTo minimumKeepStart)
        .firstOrNull { nodes[it].currentMessage.role == MessageRole.USER }
        ?: minimumKeepStart
    return nodes.take(alignedKeepStart)
}

internal fun priorCheckpointMergeContext(summary: String): String = if (summary.isBlank()) "" else
    "PRIOR CHECKPOINT (merge this exactly once with the new conversation into one consolidated checkpoint):\n$summary"

private fun Int.isCjk(): Boolean =
    this in 0x4e00..0x9fff || this in 0x3040..0x30ff || this in 0xac00..0xd7af
