package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
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
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Uuid?>(null) }
    val latestOnFinishEdit by rememberUpdatedState(onFinishEdit)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun cancelEdit() {
        editingId?.let { onFinishEdit(it, null) }
        editingId = null
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun closeSheet() {
        if (sheetOpen || editingId != null) cancelEdit()
        sheetOpen = false
    }

    DisposableEffect(Unit) {
        onDispose {
            editingId?.let { latestOnFinishEdit(it, null) }
        }
    }
    LaunchedEffect(state.messages.isEmpty()) {
        if (state.messages.isEmpty()) closeSheet()
    }
    LaunchedEffect(editingId, state.messages) {
        if (editingId != null && state.messages.none { it.id == editingId && it.isEditing }) {
            editingId = null
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val firstMessage = state.messages.firstOrNull()
    if (firstMessage != null) {
        Surface(
            onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                sheetOpen = true
            },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_message_queue"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.paused) R.string.chat_page_queue_paused_count
                        else R.string.chat_page_queue_pending_count,
                        state.messages.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.paused) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = messagePreview(firstMessage),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.chat_page_queue_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (sheetOpen && state.messages.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { closeSheet() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val editingMessage = state.messages.firstOrNull { it.id == editingId && it.isEditing }
                if (editingMessage == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.paused) R.string.chat_page_queue_paused_count
                                else R.string.chat_page_queue_pending_count,
                                state.messages.size,
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.paused) {
                            TextButton(onClick = onResume) {
                                Text(stringResource(R.string.chat_page_queue_resume))
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = state.messages,
                            key = { _, message -> message.id },
                        ) { index, message ->
                            QueuedMessageRow(
                                index = index,
                                message = message,
                                isPriority = state.priorityMessageId == message.id,
                                onEdit = {
                                    if (onBeginEdit(message.id) != null) editingId = message.id
                                },
                                onRemove = { onRemove(message.id) },
                                onSendImmediately = { onSendImmediately(message.id) },
                            )
                        }
                    }
                } else {
                    QueuedMessageEditor(
                        message = editingMessage,
                        onCancel = { cancelEdit() },
                        onSave = { parts ->
                            editingId = null
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onFinishEdit(editingMessage.id, parts)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageRow(
    index: Int,
    message: QueuedMessage,
    isPriority: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onSendImmediately: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        enabled = !message.isEditing,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = messagePreview(message),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPriority || message.isEditing) {
                    Text(
                        text = stringResource(
                            if (isPriority) R.string.chat_page_queue_priority
                            else R.string.chat_page_queue_editing
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_queue_send_now)) },
                        leadingIcon = { Icon(HugeIcons.ArrowUp02, contentDescription = null) },
                        enabled = !isPriority && !message.isEditing,
                        onClick = {
                            menuExpanded = false
                            onSendImmediately()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_queue_remove)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, contentDescription = null) },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageEditor(
    message: QueuedMessage,
    onCancel: () -> Unit,
    onSave: (List<UIMessagePart>) -> Unit,
) {
    val input = remember(message.id) {
        ChatInputState().apply {
            editingMessage = message.id
            setContents(message.parts)
        }
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(message.id) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.cancel))
        }
        Text(
            text = stringResource(R.string.chat_page_queue_edit_title),
            style = MaterialTheme.typography.titleLarge,
        )
    }
    if (input.messageContent.isNotEmpty()) MediaFileInputRow(input)
    TextField(
        state = input.textContent,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("chat_queue_edit_text"),
        lineLimits = TextFieldLineLimits.MultiLine(
            minHeightInLines = 3,
            maxHeightInLines = 8,
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
        TextButton(
            enabled = !input.isEmpty(),
            onClick = { onSave(input.getContents()) },
        ) {
            Text(stringResource(R.string.chat_page_save))
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
    }.replace(Regex("\\s+"), " ").trim().ifBlank { file }
}
