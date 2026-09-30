package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.editApprovedImagePrompt
import me.rerere.rikkahub.utils.JsonInstant

/** Preparing a request cannot execute it; restored pending/approved requests retain their snapshot. */
internal suspend fun prepareToolApproval(call: UIMessagePart.Tool, definition: Tool?): UIMessagePart.Tool {
    if (call.isExecuted || call.approvalState != ToolApprovalState.Auto || definition == null) return call
    return try {
        val arguments = definition.prepareArguments(JsonInstant.parseToJsonElement(call.input.ifBlank { "{}" }))
        call.copy(
            input = arguments.toString(),
            approvalState = if (definition.needsApproval(arguments)) ToolApprovalState.Pending else ToolApprovalState.Auto,
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        call.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
            put("error", JsonPrimitive(e.message ?: "Invalid tool request"))
        }.toString())))
    }
}

internal fun applyToolApprovalDecision(
    call: UIMessagePart.Tool,
    approved: Boolean,
    reason: String = "",
    answer: String? = null,
    editedPrompt: String? = null,
): UIMessagePart.Tool {
    if (!call.isPending) return call
    require(editedPrompt == null || (call.toolName == IMAGE_GENERATION_TOOL_NAME && approved && answer == null)) {
        "Only an approved image request can update its prompt"
    }
    val input = editedPrompt?.let { editApprovedImagePrompt(call.inputAsJson(), it) } ?: call.input
    val state = when {
        answer != null -> ToolApprovalState.Answered(answer)
        approved -> ToolApprovalState.Approved
        else -> ToolApprovalState.Denied(reason)
    }
    return call.copy(input = input, approvalState = state)
}

/** Final execution gate also protects restored requests from bypassing explicit approval. */
internal suspend fun executeToolWithApproval(
    call: UIMessagePart.Tool,
    definition: Tool,
    arguments: JsonElement,
): List<UIMessagePart> {
    check(!call.isExecuted) { "Tool call already completed" }
    check(call.approvalState == ToolApprovalState.Auto || call.approvalState == ToolApprovalState.Approved) {
        "Tool is not approved for execution"
    }
    check(!definition.needsApproval(arguments) || call.approvalState == ToolApprovalState.Approved) {
        "Explicit approval is required"
    }
    return definition.execute(arguments)
}
