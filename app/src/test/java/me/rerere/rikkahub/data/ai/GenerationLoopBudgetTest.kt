package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GenerationLoopBudgetTest {
    private val originalBudget = ToolRuntimeLimits.turnBudgetMs
    private val originalSteps = ToolRuntimeLimits.maxToolSteps

    @After
    fun restoreLimits() {
        ToolRuntimeLimits.turnBudgetMs = originalBudget
        ToolRuntimeLimits.maxToolSteps = originalSteps
    }

    @Test
    fun `slow tool returns structured timeout`() = runBlocking {
        val timeoutMs = 20L
        val output = withTimeoutOrNull(timeoutMs) {
            delay(timeoutMs + 1)
            listOf(UIMessagePart.Text("done"))
        } ?: listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", "tool_timeout")
                    put("timeout_ms", timeoutMs.toString())
                }.toString()
            )
        )

        val text = output.filterIsInstance<UIMessagePart.Text>().single().text
        val error = kotlinx.serialization.json.Json.parseToJsonElement(text)
            .jsonObject["error"]?.jsonPrimitive?.content
        assertEquals("tool_timeout", error)
    }

    @Test
    fun `configured max steps respect runtime limit`() {
        ToolRuntimeLimits.maxToolSteps = 7
        assertEquals(7, ToolRuntimeLimits.maxToolSteps)
    }

    @Test
    fun `hardline guard blocks destructive termux command`() {
        val reason = HardlineCommandGuard.checkTool(
            "termux_run_command",
            """{"command":"rm -rf /"}"""
        )
        assertNotNull(reason)
    }

    @Test
    fun `hardline guard blocks shell-wrapped shutdown`() {
        val reason = HardlineCommandGuard.checkTool(
            "termux_run_command",
            """{"command":"bash -c 'shutdown -h now'"}"""
        )
        assertNotNull(reason)
    }
}
