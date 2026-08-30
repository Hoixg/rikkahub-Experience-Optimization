package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownImageResolver
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val isMarkdown = fileName.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var markdownPreview by rememberSaveable(id, area, path) { mutableStateOf(true) }
    val imageResolver: MarkdownImageResolver = remember(id, area, path, repository) {
        { source ->
            when (val resolved = resolveWorkspaceMarkdownImagePath(path, source)) {
                null -> null
                is WorkspaceMarkdownImagePath.Network -> resolved.url
                is WorkspaceMarkdownImagePath.Local -> runCatching {
                    repository.resolvePreviewFile(id, area, resolved.path).absolutePath
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure {
            loadError = it.message ?: context.getString(R.string.workspace_file_read_failed)
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            id = id,
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        toaster.show(
                                            context.getString(R.string.workspace_file_saved),
                                            type = ToastType.Success,
                                        )
                                    }.onFailure {
                                        toaster.show(
                                            it.message ?: context.getString(R.string.workspace_file_save_failed),
                                            type = ToastType.Error,
                                        )
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (isMarkdown) {
                    val modes = listOf(
                        true to stringResource(R.string.workspace_file_preview),
                        false to stringResource(R.string.workspace_file_source),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        modes.forEachIndexed { index, (preview, label) ->
                            SegmentedButton(
                                selected = markdownPreview == preview,
                                onClick = { markdownPreview = preview },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                if (isMarkdown && markdownPreview) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            MarkdownBlock(
                                content = textState.text.toString(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                imageResolver = imageResolver,
                            )
                        }
                    }
                } else {
                    TextField(
                        state = textState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .imePadding(),
                        readOnly = !editable,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = JetbrainsMono,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }
        }
    }
}
