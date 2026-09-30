package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val INLINE_QUEUE_LIMIT = 2

@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onMove: (Uuid, Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onSendImmediately: (Uuid) -> Unit,
    onResume: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var actionsMessageId by remember { mutableStateOf<Uuid?>(null) }
    var editingId by remember { mutableStateOf<Uuid?>(null) }
    var editInput by remember { mutableStateOf<ChatInputState?>(null) }

    fun finishEdit(parts: List<UIMessagePart>?) {
        editingId?.let { onFinishEdit(it, parts) }
        editingId = null
        editInput = null
    }

    fun dismissSheet() {
        finishEdit(null)
        actionsMessageId = null
        sheetOpen = false
    }

    DisposableEffect(Unit) {
        onDispose { editingId?.let { onFinishEdit(it, null) } }
    }
    LaunchedEffect(state.messages.map { it.id }) {
        val ids = state.messages.map { it.id }.toSet()
        if (editingId !in ids) {
            editingId = null
            editInput = null
        }
        if (actionsMessageId !in ids) actionsMessageId = null
        if (ids.isEmpty()) sheetOpen = false
    }

    if (state.messages.isEmpty()) return

    val beginEdit: (Uuid) -> Unit = { id ->
        if (editingId != id) {
            finishEdit(null)
            onBeginEdit(id)?.let { message ->
                editingId = id
                editInput = ChatInputState().apply {
                    editingMessage = id
                    setContents(message.parts)
                }
                actionsMessageId = null
                sheetOpen = true
            }
        }
    }
    val remove: (Uuid) -> Unit = { id ->
        if (editingId == id) finishEdit(null)
        actionsMessageId = null
        onRemove(id)
    }
    val send: (Uuid) -> Unit = { id ->
        actionsMessageId = null
        onSendImmediately(id)
    }

    CompactQueueList(
        state = state,
        actionsMessageId = actionsMessageId,
        onShowAll = {
            actionsMessageId = null
            sheetOpen = true
        },
        onToggleActions = { id -> actionsMessageId = id.takeUnless { it == actionsMessageId } },
        onBeginEdit = beginEdit,
        onRemove = remove,
        onSendImmediately = send,
        onMove = onMove,
    )

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = ::dismissSheet) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                QueueHeader(state, onResume)
                QueueList(
                    messages = state.messages,
                    priorityMessageId = state.priorityMessageId,
                    editingId = editingId,
                    editInput = editInput,
                    actionsMessageId = actionsMessageId,
                    onToggleActions = { id -> actionsMessageId = id.takeUnless { it == actionsMessageId } },
                    onBeginEdit = beginEdit,
                    onFinishEdit = ::finishEdit,
                    onRemove = remove,
                    onSendImmediately = send,
                    onMove = onMove,
                    maxHeight = 560.dp,
                )
            }
        }
    }
}

@Composable
private fun CompactQueueList(
    state: MessageQueueState,
    actionsMessageId: Uuid?,
    onShowAll: () -> Unit,
    onToggleActions: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> Unit,
    onRemove: (Uuid) -> Unit,
    onSendImmediately: (Uuid) -> Unit,
    onMove: (Uuid, Uuid) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().testTag("chat_message_queue"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            QueueList(
                messages = state.messages.take(INLINE_QUEUE_LIMIT),
                priorityMessageId = state.priorityMessageId,
                editingId = null,
                editInput = null,
                actionsMessageId = actionsMessageId,
                onToggleActions = onToggleActions,
                onBeginEdit = onBeginEdit,
                onFinishEdit = {},
                onRemove = onRemove,
                onSendImmediately = onSendImmediately,
                onMove = onMove,
                maxHeight = 82.dp,
                compact = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.chat_page_queue_expand), onClick = onShowAll)
                    .heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(
                        if (state.paused) R.string.chat_page_queue_paused_compact_count
                        else R.string.chat_page_queue_pending_count,
                        state.messages.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.paused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(
                    HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueHeader(
    state: MessageQueueState,
    onResume: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (state.paused) R.string.chat_page_queue_paused_count
                else R.string.chat_page_queue_pending_count,
                state.messages.size,
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.paused) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
        }
    }
}

@Composable
private fun QueueList(
    messages: List<QueuedMessage>,
    priorityMessageId: Uuid?,
    editingId: Uuid?,
    editInput: ChatInputState?,
    actionsMessageId: Uuid?,
    onToggleActions: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> Unit,
    onFinishEdit: (List<UIMessagePart>?) -> Unit,
    onRemove: (Uuid) -> Unit,
    onSendImmediately: (Uuid) -> Unit,
    onMove: (Uuid, Uuid) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
    compact: Boolean = false,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? Uuid
        val toId = to.key as? Uuid
        if (fromId != null && toId != null) onMove(fromId, toId)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
    ) {
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            ReorderableItem(state = reorderableState, key = message.id) { isDragging ->
                val canDrag = !message.isEditing && message.id != priorityMessageId
                QueuedMessageRow(
                    message = message,
                    isPriority = message.id == priorityMessageId,
                    isEditing = editingId == message.id,
                    editInput = if (editingId == message.id) editInput else null,
                    actionsExpanded = actionsMessageId == message.id,
                    isDragging = isDragging,
                    canDrag = canDrag,
                    compact = compact,
                    previewModifier = if (canDrag) Modifier.longPressDraggableHandle() else Modifier,
                    onToggleActions = { onToggleActions(message.id) },
                    onBeginEdit = { onBeginEdit(message.id) },
                    onFinishEdit = onFinishEdit,
                    onRemove = { onRemove(message.id) },
                    onSendImmediately = { onSendImmediately(message.id) },
                )
            }
            if (compact && index < messages.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun QueuedMessageRow(
    message: QueuedMessage,
    isPriority: Boolean,
    isEditing: Boolean,
    editInput: ChatInputState?,
    actionsExpanded: Boolean,
    isDragging: Boolean,
    canDrag: Boolean,
    compact: Boolean,
    previewModifier: Modifier,
    onToggleActions: () -> Unit,
    onBeginEdit: () -> Unit,
    onFinishEdit: (List<UIMessagePart>?) -> Unit,
    onRemove: () -> Unit,
    onSendImmediately: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isDragging) MaterialTheme.colorScheme.primaryContainer
        else if (compact) Color.Transparent
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).then(previewModifier)
                        .heightIn(min = if (compact) 40.dp else 48.dp)
                        .padding(start = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canDrag) {
                        Icon(
                            HugeIcons.DragDropHorizontal,
                            contentDescription = stringResource(R.string.chat_page_queue_reorder),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (compact && isPriority) {
                                "${stringResource(R.string.chat_page_queue_priority)} · ${messagePreview(message)}"
                            } else messagePreview(message),
                            style = if (compact) MaterialTheme.typography.bodySmall
                            else MaterialTheme.typography.bodyMedium,
                            color = if (compact && isPriority) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isPriority && !compact) {
                            Text(
                                text = stringResource(R.string.chat_page_queue_priority),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (!isEditing) {
                    QueuedMessageActions(
                        actionsExpanded = actionsExpanded,
                        isPriority = isPriority,
                        onToggleActions = onToggleActions,
                        onBeginEdit = onBeginEdit,
                        onRemove = onRemove,
                        onSendImmediately = onSendImmediately,
                        compact = compact,
                    )
                }
            }
            if (isEditing && editInput != null) {
                if (editInput.messageContent.isNotEmpty()) MediaFileInputRow(editInput)
                TextField(
                    state = editInput.textContent,
                    modifier = Modifier.fillMaxWidth().testTag("chat_queue_edit_text"),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onFinishEdit(null) }) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        enabled = !editInput.isEmpty(),
                        onClick = { onFinishEdit(editInput.getContents()) },
                    ) { Text(stringResource(R.string.chat_page_save)) }
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageActions(
    actionsExpanded: Boolean,
    isPriority: Boolean,
    onToggleActions: () -> Unit,
    onBeginEdit: () -> Unit,
    onRemove: () -> Unit,
    onSendImmediately: () -> Unit,
    compact: Boolean = false,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 40.dp else 48.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = actionsExpanded,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally { it } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn())
                            .togetherWith(slideOutHorizontally { it } + fadeOut())
                    }.using(SizeTransform(clip = false))
                },
                label = "Queued message actions",
            ) { showActions ->
                if (showActions) {
                    Row {
                        QueueActionButton(R.string.edit, actionsExpanded, compact, onBeginEdit)
                        QueueActionButton(R.string.delete, actionsExpanded, compact, onRemove)
                    }
                } else {
                    QueueActionButton(
                        R.string.chat_page_queue_send_now,
                        !actionsExpanded && !isPriority,
                        compact,
                        onSendImmediately,
                    )
                }
            }
            IconButton(
                onClick = onToggleActions,
                modifier = Modifier.size(if (compact) 40.dp else 48.dp),
            ) {
                Icon(
                    HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.chat_page_queue_actions),
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }
    }
}

@Composable
private fun QueueActionButton(label: Int, enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    TextButton(
        modifier = if (compact) Modifier.size(width = 48.dp, height = 40.dp) else Modifier,
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 6.dp),
    ) {
        Text(
            stringResource(label),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
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
    }.replace(Regex("\\s+"), " ").trim().ifBlank { file }
}
