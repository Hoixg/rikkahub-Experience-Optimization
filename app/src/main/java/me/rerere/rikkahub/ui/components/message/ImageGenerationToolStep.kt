package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ImageToolRequest
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.utils.JsonInstant

@Composable
fun ChainOfThoughtScope.ImageGenerationToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onApproval: ((String, Boolean, String, String?) -> Unit)?,
) {
    val request = remember(tool.input) { runCatching { ImageToolRequest.fromArguments(tool.inputAsJson()) }.getOrNull() }
    val cancelled = remember(tool.output) {
        tool.output.filterIsInstance<UIMessagePart.Text>().any {
            runCatching { JsonInstant.parseToJsonElement(it.text).jsonObject["status"]?.jsonPrimitive?.contentOrNull == "cancelled" }.getOrDefault(false)
        }
    }
    val error = remember(tool.output) {
        runCatching {
            val output = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return@runCatching null
            JsonInstant.parseToJsonElement(output).jsonObject["error"]?.jsonPrimitive?.contentOrNull?.substringBefore('\n')
        }.getOrNull()
    }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    var showSheet by remember(tool.toolCallId) { mutableStateOf(false) }
    // An external approval, cancellation or branch change invalidates the editing view.
    LaunchedEffect(tool.isPending) { if (!tool.isPending) showSheet = false }
    val title = when {
        tool.isPending -> stringResource(R.string.chat_image_generation_pending)
        cancelled -> stringResource(R.string.chat_image_generation_cancelled)
        tool.approvalState is ToolApprovalState.Denied -> stringResource(R.string.chat_image_generation_denied)
        error != null -> stringResource(R.string.chat_image_generation_failed)
        tool.isExecuted && images.isNotEmpty() -> stringResource(R.string.chat_image_generation_complete, images.size)
        loading && tool.approvalState == ToolApprovalState.Approved -> stringResource(R.string.chat_image_generation_running)
        tool.approvalState == ToolApprovalState.Approved -> stringResource(R.string.chat_image_generation_approved_waiting)
        loading -> stringResource(R.string.chat_image_generation_preparing)
        else -> stringResource(R.string.assistant_page_local_tools_image_generation_title)
    }

    ControlledChainOfThoughtStep(
        expanded = images.isNotEmpty() || (error != null && !cancelled),
        onExpandedChange = {},
        icon = {
            if (loading && !tool.isPending) DotLoading(size = 10.dp)
            else Icon(HugeIcons.Image03, null, Modifier.size(16.dp))
        },
        label = {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        extra = if (tool.isPending && onApproval != null) {
            { TextButton(onClick = { showSheet = true }) { Text(stringResource(R.string.chat_image_generation_review)) } }
        } else null,
        onClick = { showSheet = true },
        content = if (images.isNotEmpty() || (error != null && !cancelled)) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images) { image ->
                                ZoomableAsyncImage(image.url, null, Modifier.width(140.dp).height(120.dp))
                            }
                        }
                    }
                    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
    )

    if (showSheet) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            onDismissRequest = { showSheet = false },
        ) {
            ImageGenerationRequestContent(
                tool = tool,
                request = request,
                error = if (cancelled) stringResource(R.string.chat_image_generation_cancelled) else error,
                onApproval = onApproval,
                onDismiss = { showSheet = false },
            )
        }
    }
}

@Composable
private fun ImageGenerationRequestContent(
    tool: UIMessagePart.Tool,
    request: ImageToolRequest?,
    error: String?,
    onApproval: ((String, Boolean, String, String?) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var prompt by remember(tool.input) { mutableStateOf(request?.prompt.orEmpty()) }
    var deciding by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
            .imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.chat_image_generation_request), style = MaterialTheme.typography.headlineSmall)
        if (request != null) {
            Text(
                stringResource(R.string.chat_image_generation_model, request.target.modelName, request.target.providerName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.chat_image_generation_parameters, request.count,
                    if (request.size == "auto") stringResource(R.string.chat_image_generation_size_auto) else request.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tool.isPending) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    enabled = !deciding,
                    label = { Text(stringResource(R.string.chat_image_generation_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 8,
                    isError = prompt.isBlank(),
                    supportingText = {
                        Text(stringResource(if (prompt.isBlank()) R.string.chat_image_generation_prompt_empty else R.string.chat_image_generation_approval_desc))
                    },
                )
            } else {
                SelectionContainer { Text(request.prompt, style = MaterialTheme.typography.bodyMedium) }
            }
            if (request.references.isNotEmpty()) {
                Text(stringResource(R.string.chat_image_generation_references, request.references.size), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(request.references, key = { it.id }) { reference ->
                        ZoomableAsyncImage(reference.url, null, Modifier.size(96.dp))
                    }
                }
            }
        }
        val images = tool.output.filterIsInstance<UIMessagePart.Image>()
        images.forEach { ZoomableAsyncImage(it.url, null, Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (tool.approvalState is ToolApprovalState.Denied) {
            Text(stringResource(R.string.chat_image_generation_denied), style = MaterialTheme.typography.bodyMedium)
        }
        if (tool.isPending && onApproval != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !deciding,
                    onClick = { deciding = true; onApproval(tool.toolCallId, false, "", null); onDismiss() },
                ) { Text(stringResource(R.string.chat_message_tool_deny)) }
                FilledTonalButton(
                    enabled = request != null && prompt.isNotBlank() && !deciding,
                    modifier = Modifier.weight(1f),
                    onClick = { deciding = true; onApproval(tool.toolCallId, true, "", prompt); onDismiss() },
                ) { Text(stringResource(R.string.chat_image_generation_approve)) }
            }
        }
    }
}
