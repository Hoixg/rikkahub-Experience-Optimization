package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import org.junit.Assert.*
import org.junit.Test

class ImageToolResultTransformerTest {
    private val output = listOf(UIMessagePart.Text("{\"success\":true}"), UIMessagePart.Image("file:///result.png"))
    private val tool = UIMessagePart.Tool("call", IMAGE_GENERATION_TOOL_NAME,
        """{"prompt":"Approved prompt","_target":{"modelId":"private"},"_references":[]} """.trim(), output)
    private val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))

    @Test fun `text only models get a valid textual result without changing persisted images`() {
        val sent = imageToolRequestMessages(messages, false).single().getTools().single()
        assertTrue(sent.output.none { it is UIMessagePart.Image })
        assertTrue(sent.output.filterIsInstance<UIMessagePart.Text>().last().text.contains("cannot view"))
        assertFalse(sent.input.contains("_target"))
        assertTrue(sent.input.contains("Approved prompt"))
        assertEquals(output, messages.single().getTools().single().output)
        assertTrue(tool.input.contains("_target"))
    }

    @Test fun `vision models retain images and omit only internal request snapshots`() {
        val sent = imageToolRequestMessages(messages, true).single().getTools().single()
        assertEquals(output, sent.output)
        assertFalse(sent.input.contains("_references"))
    }

    @Test fun `other tool outputs are unchanged`() {
        val other = tool.copy(toolName = "another_tool")
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(other)))
        assertEquals(messages, imageToolRequestMessages(messages, false))
    }
}
