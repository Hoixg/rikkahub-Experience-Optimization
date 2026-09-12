package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceTool
import me.rerere.rikkahub.data.repository.WorkspaceToolFile
import me.rerere.rikkahub.data.repository.WorkspaceToolInput
import me.rerere.rikkahub.data.repository.WorkspaceToolRun
import me.rerere.rikkahub.data.repository.WorkspaceToolRunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

@Composable
fun WorkspaceToolsPage(id: String) {
    val navController = LocalNavController.current
    val manager: WorkspaceToolRunManager = koinInject()
    val runs by manager.observeWorkspace(id).collectAsStateWithLifecycle(initialValue = emptyList())
    val toolsVersion by manager.observeToolsVersion().collectAsStateWithLifecycle(initialValue = 0)
    var tools by remember(id) { mutableStateOf<List<WorkspaceTool>>(emptyList()) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var refreshKey by remember(id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id, refreshKey, toolsVersion) {
        loading = true
        runCatching { manager.listTools(id) }
            .onSuccess { tools = it; error = null }
            .onFailure { error = it.message.orEmpty() }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.workspace_tools_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.workspace_tools_registered_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.workspace_tools_refresh))
                }
            }
        }
        if (loading) {
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (!loading && tools.isEmpty() && error == null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(HugeIcons.Tools, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.workspace_tools_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(tools, key = { it.manifest.id }) { tool ->
            val activeRun = runs.firstOrNull {
                it.tool.id == tool.manifest.id && it.status in setOf(WorkspaceToolRunStatus.PREPARING, WorkspaceToolRunStatus.RUNNING)
            }
            val latestStaticRun = runs.firstOrNull {
                it.tool.id == tool.manifest.id &&
                    it.tool.entry.mode == "static_html" &&
                    it.status == WorkspaceToolRunStatus.FINISHED
            }
            WorkspaceToolCard(
                tool = tool,
                activeRun = activeRun,
                displayRun = activeRun ?: latestStaticRun,
                onOpen = { navController.navigate(Screen.WorkspaceToolRun(id, tool.manifest.id, (activeRun ?: latestStaticRun)?.id)) },
                onStop = {
                    activeRun?.let { run ->
                        scope.launch { manager.stop(run.id) }
                    }
                },
            )
        }
    }
}

@Composable
private fun WorkspaceToolCard(
    tool: WorkspaceTool,
    activeRun: WorkspaceToolRun?,
    displayRun: WorkspaceToolRun?,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HugeIcons.Tools, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.manifest.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        tool.manifest.id + " · " + tool.manifest.version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (activeRun != null) {
                    IconButton(onClick = onStop) {
                        Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.workspace_tools_stop), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (tool.manifest.description.isNotBlank()) Text(tool.manifest.description, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayRun?.status?.name ?: stringResource(R.string.workspace_tools_idle),
                    modifier = Modifier.weight(1f),
                    color = if (activeRun == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onOpen) {
                    Icon(HugeIcons.Play, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (displayRun == null) R.string.workspace_tools_run else R.string.workspace_tools_view))
                }
            }
        }
    }
}

@Composable
fun WorkspaceToolRunPage(workspaceId: String, toolId: String, initialRunId: String?) {
    val navController = LocalNavController.current
    val manager: WorkspaceToolRunManager = koinInject()
    val repository: WorkspaceRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tool by remember(workspaceId, toolId) { mutableStateOf<WorkspaceTool?>(null) }
    var runId by rememberSaveable(workspaceId, toolId, initialRunId) { mutableStateOf(initialRunId) }
    var values by remember(tool?.manifest?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var input by rememberSaveable(runId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<WorkspaceToolFile?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    val runFlow = remember(runId, manager) { runId?.let(manager::observe) ?: flowOf(null) }
    val observedRun by runFlow.collectAsStateWithLifecycle(initialValue = null)
    val run = observedRun ?: runId?.let(manager::current)

    DisposableEffect(runId) {
        onDispose {
            runId?.let(manager::closeWebServer)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget.also { exportTarget = null }
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportFile(workspaceId, WorkspaceStorageArea.FILES, target.path, output)
                } ?: error("Unable to open export destination")
            }.onFailure { Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(workspaceId, toolId) {
        loading = true
        runCatching { manager.listTools(workspaceId).firstOrNull { it.manifest.id == toolId } }
            .onSuccess {
                tool = it
                values = it?.manifest?.inputs.orEmpty().associate { field -> field.name to (field.default ?: "") }
                pageError = if (it == null) "Tool not found" else null
            }
            .onFailure { pageError = it.message.orEmpty() }
        loading = false
    }

    LaunchedEffect(runId) {
        runId?.let { manager.ensureStaticWebServer(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.manifest?.name ?: stringResource(R.string.workspace_tools_run)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        when {
            loading -> Row(modifier = Modifier.fillMaxSize().padding(padding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator() }
            tool == null -> Text(pageError ?: stringResource(R.string.workspace_tools_not_found), modifier = Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> WorkspaceToolRunContent(
                modifier = Modifier.padding(padding),
                tool = tool!!,
                run = run,
                values = values,
                input = input,
                error = pageError,
                onValueChange = { name, value -> values = values + (name to value) },
                onInputChange = { input = it },
                onStart = {
                    scope.launch {
                        runCatching { manager.start(workspaceId, toolId, values) }
                            .onSuccess { started -> runId = started.id; pageError = null }
                            .onFailure { pageError = it.message.orEmpty() }
                    }
                },
                onSend = {
                    val currentRun = run
                    if (currentRun != null) scope.launch {
                        runCatching { manager.sendInput(currentRun.id, input) }
                            .onSuccess { input = "" }
                            .onFailure { pageError = it.message.orEmpty() }
                    }
                },
                onStop = { run?.let { current -> scope.launch { manager.stop(current.id) } } },
                onOpenFile = { file ->
                    if (isWorkspaceImageFileName(file.name)) {
                        scope.launch {
                            runCatching {
                                repository.resolvePreviewFile(workspaceId, WorkspaceStorageArea.FILES, file.path).absolutePath
                            }.onSuccess { previewImagePath = it }
                                .onFailure { pageError = it.message.orEmpty() }
                        }
                    } else {
                        navController.navigate(Screen.WorkspaceFileEditor(workspaceId, WorkspaceStorageArea.FILES.name, file.path))
                    }
                },
                onExportFile = { file -> exportTarget = file; exportLauncher.launch(file.name) },
            )
        }
    }

    previewImagePath?.let { path ->
        ImagePreviewDialog(
            images = listOf(path),
            onDismissRequest = { previewImagePath = null },
        )
    }
}

@Composable
private fun WorkspaceToolRunContent(
    modifier: Modifier,
    tool: WorkspaceTool,
    run: WorkspaceToolRun?,
    values: Map<String, String>,
    input: String,
    error: String?,
    onValueChange: (String, String) -> Unit,
    onInputChange: (String) -> Unit,
    onStart: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenFile: (WorkspaceToolFile) -> Unit,
    onExportFile: (WorkspaceToolFile) -> Unit,
) {
    val sendDescription = stringResource(R.string.workspace_tools_send)
    val stopDescription = stringResource(R.string.workspace_tools_stop)
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (run == null || run.status in setOf(WorkspaceToolRunStatus.FINISHED, WorkspaceToolRunStatus.FAILED, WorkspaceToolRunStatus.STOPPED)) {
            ToolInputs(tool.manifest.inputs, values, onValueChange)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(HugeIcons.Play, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workspace_tools_run))
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        run?.let { current ->
            Text(stringResource(R.string.workspace_tools_status, current.status.name), style = MaterialTheme.typography.titleMedium)
            current.error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            current.webUrl?.let { url ->
                val webState = rememberWebViewState(url = url)
                WebView(state = webState, modifier = Modifier.fillMaxWidth().height(320.dp))
            }
            Surface(modifier = Modifier.fillMaxWidth().height(240.dp), color = Color.Black, shape = RoundedCornerShape(6.dp)) {
                SelectionContainer {
                    Text(
                        current.log.ifBlank { stringResource(R.string.workspace_tools_no_output) },
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        color = Color.White,
                        fontFamily = JetbrainsMono,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (current.status == WorkspaceToolRunStatus.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input, onValueChange = onInputChange, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.workspace_tools_input)) }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onSend, modifier = Modifier.semantics { contentDescription = sendDescription }) { Icon(HugeIcons.ArrowUp02, contentDescription = null) }
                    IconButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = stopDescription }) { Icon(HugeIcons.Cancel01, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                }
            } else if (current.status == WorkspaceToolRunStatus.PREPARING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.workspace_tools_preparing), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = stopDescription }) {
                        Icon(HugeIcons.Cancel01, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (current.resultFiles.isNotEmpty()) {
                Text(stringResource(R.string.workspace_tools_results), style = MaterialTheme.typography.titleMedium)
                current.resultFiles.forEach { file -> WorkspaceToolResultFile(file, onOpenFile, onExportFile) }
            }
        }
    }
}

@Composable
private fun ToolInputs(inputs: List<WorkspaceToolInput>, values: Map<String, String>, onValueChange: (String, String) -> Unit) {
    if (inputs.isEmpty()) return
    Text(stringResource(R.string.workspace_tools_inputs), style = MaterialTheme.typography.titleMedium)
    inputs.forEach { field -> ToolInputField(field, values[field.name].orEmpty()) { onValueChange(field.name, it) } }
}

@Composable
private fun ToolInputField(field: WorkspaceToolInput, value: String, onValueChange: (String) -> Unit) {
    val label = field.label.ifBlank { field.name }
    when (field.type) {
        "boolean" -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = value == "true", onCheckedChange = { onValueChange(it.toString()) })
        }
        "select" -> {
            var expanded by remember(field.name) { mutableStateOf(false) }
            Column {
                OutlinedTextField(value = value, onValueChange = {}, modifier = Modifier.fillMaxWidth().clickable { expanded = true }, label = { Text(label) }, readOnly = true, singleLine = true)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
                    }
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = field.placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "number") KeyboardType.Number else KeyboardType.Text),
        )
    }
}

@Composable
private fun WorkspaceToolResultFile(file: WorkspaceToolFile, onOpenFile: (WorkspaceToolFile) -> Unit, onExportFile: (WorkspaceToolFile) -> Unit) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HugeIcons.File02, contentDescription = null)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.path + " · " + file.sizeBytes.fileSizeToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onOpenFile(file) }) { Icon(HugeIcons.File02, contentDescription = stringResource(R.string.workspace_tools_open_file)) }
            IconButton(onClick = { onExportFile(file) }) { Icon(HugeIcons.Download01, contentDescription = stringResource(R.string.workspace_tools_export_file)) }
        }
    }
}
