package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val COMPACTION_CHECKPOINT_PREAMBLE =
    "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. " +
        "Treat the captured context as established background and build on it without restating it. " +
        "Continue the task directly from the messages that follow, without acknowledging this checkpoint."

internal const val COMPACTION_SUMMARY_OPEN = "<compressed-summary>"
internal const val COMPACTION_SUMMARY_CLOSE = "</compressed-summary>"

fun buildCompactionCheckpointText(summaryContent: String): String = buildString {
    appendLine(COMPACTION_CHECKPOINT_PREAMBLE)
    appendLine()
    appendLine(COMPACTION_SUMMARY_OPEN)
    appendLine(summaryContent)
    append(COMPACTION_SUMMARY_CLOSE)
}

fun UIMessage.isCompactionCheckpoint(): Boolean {
    if (role != MessageRole.USER || parts.size != 1) return false
    val part = parts[0] as? UIMessagePart.Text ?: return false
    return part.text.startsWith(COMPACTION_CHECKPOINT_PREAMBLE)
}

fun compactionCheckpointBody(text: String): String {
    val start = text.indexOf(COMPACTION_SUMMARY_OPEN)
    val end = text.indexOf(COMPACTION_SUMMARY_CLOSE)
    return if (start >= 0 && end > start) {
        text.substring(start + COMPACTION_SUMMARY_OPEN.length, end).trim()
    } else {
        text
    }
}

val DEFAULT_COMPRESS_PROMPT = """
    You are now acting as a compaction engine for this AI assistant. Condense the conversation into a structured checkpoint that lets another model resume the work with no loss of essential context.

    Output EXACTLY the Markdown structure below: keep every section, in order. Use terse bullets, not prose paragraphs. Write "(none)" for an empty section — never drop a section.

    # Primary Request and Intent
    - [the user's original and evolving goals; quote verbatim where the exact wording matters]

    # Key Technical Concepts
    - [technologies, frameworks, patterns, and conventions in play]

    # Files and Code
    - [exact path: why it matters, key changes or snippets]

    # Errors and Fixes
    - [error: how it was resolved, plus any related user feedback]

    # Pending Jobs
    - [explicitly requested work not yet completed]

    # Current Work
    - [precisely what was in progress at this checkpoint]

    # Next Step
    - [the single next action, directly in line with the most recent request, or "(none)"]

    # Critical Context
    - [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]

    Rules:
    - Preserve exact file paths, commands, error strings, identifiers, numeric values, function signatures.
    - Capture user feedback and explicit instructions faithfully, especially corrections.
    - Do NOT mention this summarization request or that the context was compacted.
    - Output only the checkpoint text: do not call any tool or take any other action.
    - A PRIOR CHECKPOINT may be provided in the additional context above: if so, do not copy it verbatim; keep still-true facts, drop stale ones, and merge newer information into a single consolidated checkpoint under the same structure.
    - Use {locale} language. Target approximately {target_tokens} tokens.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
