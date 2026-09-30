package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME

object ImageToolResultTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        imageToolRequestMessages(messages, Modality.IMAGE in ctx.model.inputModalities)
}

/** Internal snapshots stay in storage/UI, while the model receives only the public tool arguments. */
internal fun imageToolRequestMessages(messages: List<UIMessage>, supportsImages: Boolean): List<UIMessage> =
    messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.toolName != IMAGE_GENERATION_TOOL_NAME) part
            else part.copy(
                input = (part.inputAsJson() as? JsonObject)?.let { input ->
                    JsonObject(input.filterKeys { !it.startsWith("_") }).toString()
                } ?: part.input,
                output = if (supportsImages) part.output else part.output.map { output ->
                    if (output is UIMessagePart.Image) UIMessagePart.Text(
                        "[An image was generated and displayed to the user. This model cannot view image contents.]"
                    ) else output
                },
            )
        })
    }
