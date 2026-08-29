package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _folderExportResult = MutableStateFlow<WorkspaceFolderExportResult?>(null)
    val folderExportResult = _folderExportResult.asStateFlow()

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    expandedPaths = emptySet(),
                    childrenCache = emptyMap(),
                )
            }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun toggleExpand(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        val path = entry.path
        if (path in state.value.expandedPaths) {
            _state.update { it.copy(expandedPaths = it.expandedPaths - path) }
            return
        }
        _state.update { it.copy(expandedPaths = it.expandedPaths + path) }
        if (path in state.value.childrenCache) return
        viewModelScope.launch {
            runCatching {
                repository.listFiles(id = id, area = state.value.area, path = path)
            }.onSuccess { children ->
                _state.update { it.copy(childrenCache = it.childrenCache + (path to children)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        expandedPaths = it.expandedPaths - path,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(openSource: () -> Pair<InputStream, String>?) {
        viewModelScope.launch {
            runCatching {
                val (inputStream, fileName) = withContext(Dispatchers.IO) {
                    openSource() ?: error("无法打开导入文件")
                }
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, openOutputStream: () -> OutputStream?) {
        viewModelScope.launch {
            runCatching {
                val outputStream = withContext(Dispatchers.IO) {
                    openOutputStream() ?: error("无法打开导出目标")
                }
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun exportFolder(
        entry: WorkspaceFileEntry,
        openDestinationTree: () -> DocumentFile?,
        openOutputStream: (Uri) -> OutputStream?,
    ) {
        viewModelScope.launch {
            val area = state.value.area
            runCatching {
                withContext(Dispatchers.IO) {
                    val destinationTree = openDestinationTree() ?: error("无法打开导出目录")
                    val listing = mutableMapOf<String, List<WorkspaceFileEntry>>()
                    suspend fun collect(path: String) {
                        val children = repository.listFiles(id = id, area = area, path = path)
                        listing[path] = children
                        children.filter { it.isDirectory }.forEach { collect(it.path) }
                    }
                    collect(entry.path)

                    val plan = planWorkspaceFolderExport(entry.path, listing)
                    val destinationDirs = mutableMapOf<String, DocumentFile>()
                    destinationDirs[entry.path] = destinationTree.createDirectory(entry.name)
                        ?: error("无法创建导出目录：${entry.name}")

                    var failures = 0
                    for (item in plan) {
                        val parent = destinationDirs[item.parentPath]
                        if (parent == null) {
                            failures++
                            continue
                        }
                        if (item.isDirectory) {
                            val directory = parent.createDirectory(item.name)
                            if (directory == null) {
                                failures++
                            } else {
                                destinationDirs[item.sourcePath] = directory
                            }
                        } else {
                            runCatching {
                                val file = parent.createFile("application/octet-stream", item.name)
                                    ?: error("无法创建文件：${item.name}")
                                val output = openOutputStream(file.uri) ?: error("无法打开文件输出流")
                                output.use { stream ->
                                    repository.exportFile(
                                        id = id,
                                        area = area,
                                        path = item.sourcePath,
                                        outputStream = stream,
                                    )
                                }
                            }.onFailure { error ->
                                failures++
                                Log.w(TAG, "Folder export failed: ${item.sourcePath}", error)
                            }
                        }
                    }
                    failures
                }
            }.onSuccess { failures ->
                _folderExportResult.value = WorkspaceFolderExportResult(entry.name, failures)
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件夹失败") }
            }
        }
    }

    fun dismissFolderExportResult() {
        _folderExportResult.value = null
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                    val file = File(dir, entry.name)
                    file.outputStream().use { output ->
                        repository.exportFile(
                            id = id,
                            area = state.value.area,
                            path = entry.path,
                            outputStream = output,
                        )
                    }
                    file
                }
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun resolvePreviewFile(entry: WorkspaceFileEntry, onReady: (File) -> Unit) {
        val area = state.value.area
        viewModelScope.launch {
            runCatching { repository.resolvePreviewFile(id, area, entry.path) }
                .onSuccess(onReady)
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "无法预览图片") }
                }
        }
    }

    fun resolvePreviewFileSilently(entry: WorkspaceFileEntry, onReady: (File?) -> Unit) {
        val area = state.value.area
        viewModelScope.launch {
            runCatching { repository.resolvePreviewFile(id, area, entry.path) }
                .onSuccess(onReady)
                .onFailure { onReady(null) }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }

    companion object {
        private const val TAG = "WorkspaceDetailVM"
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val expandedPaths: Set<String> = emptySet(),
    val childrenCache: Map<String, List<WorkspaceFileEntry>> = emptyMap(),
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}

data class WorkspaceFolderExportResult(
    val folderName: String,
    val failures: Int,
)

data class WorkspaceTreeRow(
    val entry: WorkspaceFileEntry,
    val depth: Int,
)

internal fun flattenWorkspaceTree(
    entries: List<WorkspaceFileEntry>,
    expandedPaths: Set<String>,
    childrenCache: Map<String, List<WorkspaceFileEntry>>,
    depth: Int = 0,
): List<WorkspaceTreeRow> = entries.flatMap { entry ->
    val row = WorkspaceTreeRow(entry, depth)
    if (entry.isDirectory && entry.path in expandedPaths) {
        listOf(row) + flattenWorkspaceTree(
            childrenCache[entry.path].orEmpty(),
            expandedPaths,
            childrenCache,
            depth + 1,
        )
    } else {
        listOf(row)
    }
}

internal data class WorkspaceExportPlanEntry(
    val sourcePath: String,
    val parentPath: String,
    val name: String,
    val isDirectory: Boolean,
)

internal fun planWorkspaceFolderExport(
    rootPath: String,
    listing: Map<String, List<WorkspaceFileEntry>>,
): List<WorkspaceExportPlanEntry> {
    val plan = mutableListOf<WorkspaceExportPlanEntry>()
    fun walk(path: String) {
        for (child in listing[path].orEmpty()) {
            plan += WorkspaceExportPlanEntry(
                sourcePath = child.path,
                parentPath = path,
                name = child.name,
                isDirectory = child.isDirectory,
            )
            if (child.isDirectory) walk(child.path)
        }
    }
    walk(rootPath)
    return plan
}
