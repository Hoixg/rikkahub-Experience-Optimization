package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState

@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onSendImmediately: (Uuid) -> Unit,
    onResume: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.messages.isEmpty()) {
        if (state.messages.isEmpty()) expanded = false
    }

    if (state.messages.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_message_queue"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.messages.none { it.isEditing }) {
                            expanded = !expanded
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (state.paused) R.string.chat_page_queue_paused_count
                                else R.string.chat_page_queue_pending_count,
                                state.messages.size,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val firstMessage = state.messages.firstOrNull()
                        if (state.priorityMessageId != null) {
                            Text(
                                text = stringResource(R.string.chat_page_queue_priority),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (!expanded && firstMessage != null) {
                            Text(
                                text = messagePreview(firstMessage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.paused) {
                        TextButton(
                            onClick = {
                                onResume()
                                expanded = true
                            },
                        ) { Text(stringResource(R.string.chat_page_queue_resume)) }
                    }
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = stringResource(
                            if (expanded) R.string.chat_page_queue_collapse
                            else R.string.chat_page_queue_expand
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (expanded) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = state.messages,
                            key = { _, message -> message.id },
                        ) { index, message ->
                            QueuedMessageCard(
                                index = index,
                                message = message,
                                isPriority = state.priorityMessageId == message.id,
                                onEdit = {
                                    if (onBeginEdit(message.id) != null) expanded = true
                                },
                                onRemove = { onRemove(message.id) },
                                onSendImmediately = { onSendImmediately(message.id) },
                                onFinishEdit = { parts -> onFinishEdit(message.id, parts) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageCard(
    index: Int,
    message: QueuedMessage,
    isPriority: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onSendImmediately: () -> Unit,
    onFinishEdit: (List<UIMessagePart>?) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = messagePreview(message),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPriority) {
                    Text(
                        text = stringResource(R.string.chat_page_queue_priority),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (message.isEditing) {
                val input = remember(message.id) {
                    ChatInputState().apply {
                        editingMessage = message.id
                        setContents(message.parts)
                    }
                }
                DisposableEffect(message.id) {
                    onDispose { onFinishEdit(null) }
                }
                if (input.messageContent.isNotEmpty()) MediaFileInputRow(input)
                TextField(
                    state = input.textContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_queue_edit_text"),
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 2,
                        maxHeightInLines = 6,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onFinishEdit(null) }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        enabled = !input.isEmpty(),
                        onClick = { onFinishEdit(input.getContents()) },
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                    FilledTonalButton(
                        enabled = !isPriority,
                        onClick = onSendImmediately,
                    ) {
                        Icon(HugeIcons.ArrowUp02, contentDescription = null)
                        Text(
                            text = stringResource(R.string.chat_page_queue_send_now),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.chat_page_queue_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun messagePreview(message: QueuedMessage): String {
    val image = stringResource(R.string.chat_page_queue_image)
    val file = stringResource(R.string.chat_page_queue_file)
    val audio = stringResource(R.string.chat_page_queue_audio)
    val video = stringResource(R.string.chat_page_queue_video)
    return message.parts.joinToString(" ") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> image
            is UIMessagePart.Document -> file
            is UIMessagePart.Audio -> audio
            is UIMessagePart.Video -> video
            else -> ""
        }
    }.ifBlank { file }
}
