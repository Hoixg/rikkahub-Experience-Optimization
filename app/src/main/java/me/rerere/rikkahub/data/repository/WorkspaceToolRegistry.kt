package me.rerere.rikkahub.data.repository

import kotlinx.serialization.SerializationException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceToolRegistry(
    private val repository: WorkspaceRepository,
) {
    suspend fun list(workspaceId: String): List<WorkspaceTool> {
        val directories = runCatching {
            repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, TOOLS_PATH)
        }.getOrDefault(emptyList())
        return directories
            .filter { it.isDirectory }
            .mapNotNull { directory ->
                runCatching {
                    val manifestPath = "${directory.path}/$MANIFEST_NAME"
                    val manifest = JsonInstant.decodeFromString<WorkspaceToolManifest>(
                        repository.readText(workspaceId, manifestPath),
                    )
                    validate(directory.name, manifest)
                    WorkspaceTool(directory.path, manifest)
                }.getOrNull()
            }
            .sortedBy { it.manifest.name.lowercase() }
    }

    suspend fun get(workspaceId: String, toolId: String): WorkspaceTool? =
        list(workspaceId).firstOrNull { it.manifest.id == toolId }

    suspend fun register(workspaceId: String, manifestJson: String): WorkspaceTool {
        val manifest = try {
            JsonInstant.decodeFromString<WorkspaceToolManifest>(manifestJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid tool.json: ${error.message}", error)
        }
        validate(manifest.id, manifest)
        require(get(workspaceId, manifest.id) == null) {
            "A tool with this id is already registered"
        }
        val path = "$TOOLS_PATH/${manifest.id}/$MANIFEST_NAME"
        runCatching { repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, TOOLS_PATH) }
            .getOrDefault(emptyList())
            .firstOrNull { it.name == manifest.id && !it.isDirectory }
            ?.let { error("A file already uses the tool id: ${manifest.id}") }
        repository.writeText(workspaceId, path, JsonInstant.encodeToString(manifest), overwrite = true)
        return WorkspaceTool("$TOOLS_PATH/${manifest.id}", manifest)
    }

    private fun validate(directoryName: String, manifest: WorkspaceToolManifest) {
        require(manifest.schema_version == 1) { "Unsupported tool schema version: ${manifest.schema_version}" }
        require(ID_REGEX.matches(manifest.id)) { "Invalid tool id: ${manifest.id}" }
        require(directoryName == manifest.id) { "tool.json id must match its directory name" }
        require(manifest.name.isNotBlank()) { "Tool name is required" }
        require(manifest.entry.mode in ENTRY_MODES) { "Unsupported tool entry mode: ${manifest.entry.mode}" }
        if (manifest.entry.mode == "command" || manifest.entry.mode == "web_server") {
            require(!manifest.entry.command.isNullOrBlank()) { "Tool command is required" }
        }
        if (manifest.entry.mode == "static_html") {
            require(!manifest.entry.html.isNullOrBlank()) { "HTML entry is required" }
        }
        manifest.entry.port?.let { require(it in 1..65535) { "Invalid tool port" } }
        manifest.inputs.forEach { input ->
            require(INPUT_NAME_REGEX.matches(input.name)) { "Invalid tool input name: ${input.name}" }
            require(input.type in INPUT_TYPES) { "Unsupported tool input type: ${input.type}" }
            if (input.type == "select") require(input.options.isNotEmpty()) { "Select input needs options" }
        }
        require(manifest.inputs.map { it.name }.toSet().size == manifest.inputs.size) {
            "Tool input names must be unique"
        }
        manifest.prepare?.let { require(it.command.isNotBlank()) { "Prepare command is required" } }
        listOf(
            manifest.entry.working_directory,
            manifest.entry.html,
            manifest.icon,
            manifest.result.path,
            manifest.result.html,
            manifest.prepare?.working_directory,
        )
            .filterNotNull()
            .forEach { path -> require(isRelativePath(path)) { "Tool path must stay inside its directory: $path" } }
    }

    private fun isRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.replace('\\', '/').startsWith("/") &&
            path.replace('\\', '/').split('/').none { it == ".." }

    companion object {
        const val TOOLS_PATH = "tools"
        const val MANIFEST_NAME = "tool.json"
        private val ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val INPUT_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val ENTRY_MODES = setOf("command", "static_html", "web_server")
        private val INPUT_TYPES = setOf("text", "number", "url", "boolean", "select")
    }
}
