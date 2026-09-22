package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    /** Global mounts shared by every workspace; custom workspace mounts can shadow them. */
    val globalBindMounts: List<WorkspaceBindMount> get() = bindMounts

    private val fileSystem = WorkspaceFileSystem(config)

    private val sortedBindMounts = mergeBindMounts(bindMounts, emptyList())

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    private fun mergeBindMounts(
        global: List<WorkspaceBindMount>,
        extra: List<WorkspaceBindMount>,
    ): List<WorkspaceBindMount> {
        if (extra.isEmpty()) {
            return global.sortedByDescending { it.target.trimEnd('/').length }
        }
        val extraTargets = extra.mapTo(mutableSetOf()) { it.target.trimEnd('/') }
        return (extra + global.filterNot { it.target.trimEnd('/') in extraTargets })
            .sortedByDescending { it.target.trimEnd('/').length }
    }

    fun buildProotArgs(
        root: String,
        cwd: String = "",
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): List<String> {
        val args = mutableListOf(
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir(root).absolutePath,
            "-w",
            prootCwd(cwd),
            "-b",
            "${filesDir(root).absolutePath}:$ROOTFS_WORKSPACE_DIR",
        )
        mergeBindMounts(bindMounts, extraBindMounts).forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += mount.prootBindSpec()
            }
        }
        KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        return args
    }

    fun prootCwd(cwd: String): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) ROOTFS_WORKSPACE_DIR else "$ROOTFS_WORKSPACE_DIR/$normalized"
    }

    fun bindMountFor(mountDir: WorkspaceMountDir): WorkspaceBindMount = WorkspaceBindMount(
        source = File(mountDir.sourcePath),
        target = mountDir.target,
        readOnly = mountDir.readOnly,
    )

    fun validateMountDir(
        root: String,
        mountDir: WorkspaceMountDir,
        existing: List<WorkspaceMountDir>,
    ): String? {
        val source = mountDir.sourcePath.trim()
        // On host JVMs the test-created source may use a Windows drive path; Android paths stay POSIX absolute.
        val sourceFile = File(source)
        if (!source.startsWith("/") && !sourceFile.isAbsolute) return "source_not_absolute"
        if (source == "/") return "source_is_root"
        val target = mountDir.target.trim().trimEnd('/')
        if (!target.startsWith("/")) return "target_not_absolute"
        if (target.isEmpty() || target == "/") return "target_is_root"
        if (RESERVED_MOUNT_TARGETS.any { target == it || target.startsWith("$it/") }) {
            return "target_reserved"
        }
        if (!sourceFile.exists()) return "source_missing"
        if (!sourceFile.isDirectory) return "source_not_directory"
        if (existing.any { it.target.trim().trimEnd('/') == target }) return "target_duplicated"
        if (existing.any { it.sourcePath.trim() == source }) return "source_duplicated"
        val linux = linuxDir(root)
        if (sourceFile.canonicalPath.startsWith(linux.canonicalPath + File.separator)) {
            return "source_inside_rootfs"
        }
        return null
    }

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolvePreviewFile(
        root: String,
        area: WorkspaceStorageArea,
        path: String,
    ): File = fileSystem.resolveFile(areaDir(root, area), path)

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        val mounts = if (extraBindMounts.isEmpty()) sortedBindMounts else mergeBindMounts(bindMounts, extraBindMounts)
        mounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "", mount.readOnly)
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"), mount.readOnly)
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(
        root: String,
        path: String,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): Long = resolveRootfsFile(root, path, extraBindMounts)
        .also { it.requireReadableFile(path) }
        .length()

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ) {
        val file = resolveRootfsFile(root, path, extraBindMounts)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(
        root: String,
        path: String,
        extraBindMounts: List<WorkspaceBindMount>,
    ): File {
        val location = resolveRootfsPath(root, path, extraBindMounts)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = mergeBindMounts(bindMounts, extraBindMounts),
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        /** User mounts may not shadow fixed Rootfs namespaces. */
        val RESERVED_MOUNT_TARGETS = listOf(ROOTFS_WORKSPACE_DIR) + KERNEL_FS_MOUNTS

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
    val readOnly: Boolean = false,
)
