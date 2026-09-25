package me.rerere.rikkahub.service.scheduled

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard

class DirectModeActionRunner(private val json: Json) {
    @Serializable
    data class Action(val tool: String, val args: JsonObject)

    @Serializable
    data class StepResult(val index: Int, val tool: String, val outcome: String, val output: String?)

    data class SequenceResult(val outcome: String, val errorMessage: String?, val steps: List<StepResult>) {
        val preview: String get() = steps.lastOrNull()?.output.orEmpty().take(500)
    }

    fun parse(actionsJson: String): Result<List<Action>> = runCatching {
        val element = json.parseToJsonElement(actionsJson)
        require(element is JsonArray && element.isNotEmpty()) { "Actions must be a non-empty JSON array" }
        require(element.size <= MAX_ACTIONS) { "A direct task can contain at most $MAX_ACTIONS actions" }
        element.mapIndexed { index, value ->
            require(value is JsonObject) { "Action $index must be an object" }
            val tool = (value["tool"] as? JsonPrimitive)?.contentOrNull
                ?: error("Action $index is missing tool")
            val args = value["args"] as? JsonObject ?: error("Action $index is missing args")
            Action(tool, args)
        }
    }

    fun validate(actionsJson: String, availableTools: List<Tool>): Result<List<Action>> =
        parse(actionsJson).mapCatching { actions ->
            actions.forEachIndexed { index, action ->
                require(isHeadlessSafeTool(action.tool)) { "Action $index requires foreground interaction" }
                val blocked = HardlineCommandGuard.checkTool(action.tool, action.args.toString())
                require(blocked == null) { "Action $index blocked by safety guard: $blocked" }
                val tool = availableTools.firstOrNull { it.name == action.tool }
                    ?: error("Action $index tool is not enabled: ${action.tool}")
                require(!runCatching { tool.needsApproval(action.args) }.getOrDefault(true)) {
                    "Action $index requires interactive approval"
                }
            }
            actions
        }

    suspend fun run(actions: List<Action>, availableTools: List<Tool>): SequenceResult {
        val steps = mutableListOf<StepResult>()
        for ((index, action) in actions.withIndex()) {
            if (action.tool in INTERACTIVE_TOOLS) {
                return SequenceResult("failed", "action $index: ${action.tool} requires foreground interaction", steps)
            }
            val blocked = HardlineCommandGuard.checkTool(action.tool, action.args.toString())
            if (blocked != null) return SequenceResult("failed", "action $index: hardline:$blocked", steps)
            val tool = availableTools.firstOrNull { it.name == action.tool }
                ?: return SequenceResult("failed", "action $index: tool_unavailable: ${action.tool}", steps)
            if (runCatching { tool.needsApproval(action.args) }.getOrDefault(true)) {
                return SequenceResult("failed", "action $index: tool requires interactive approval", steps)
            }
            val output = try {
                withTimeoutOrNull(ACTION_TIMEOUT_MS) { tool.execute(action.args) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return SequenceResult(
                    "failed", "action $index: ${error::class.simpleName}: ${error.message.orEmpty()}".take(500), steps,
                )
            }
            if (output == null) {
                steps += StepResult(index, action.tool, "timed_out", null)
                return SequenceResult("timed_out", "action $index: ${action.tool} exceeded 60s", steps)
            }
            val rendered = output.mapNotNull { (it as? UIMessagePart.Text)?.text }
                .joinToString("\n").take(MAX_STEP_OUTPUT)
            steps += StepResult(index, action.tool, "success", rendered)
        }
        return SequenceResult("success", null, steps)
    }

    companion object {
        const val ACTION_TIMEOUT_MS = 60_000L
        const val MAX_ACTIONS = 20
        const val MAX_STEP_OUTPUT = 4_000
        private val INTERACTIVE_TOOLS = setOf("ask_user", "grant_directory_access")
        fun isHeadlessSafeTool(name: String) = name !in INTERACTIVE_TOOLS
    }
}
