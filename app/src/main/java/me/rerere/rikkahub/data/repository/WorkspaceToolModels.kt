package me.rerere.rikkahub.data.repository

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceToolManifest(
    val schema_version: Int = 1,
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val icon: String? = null,
    val entry: WorkspaceToolEntry,
    val inputs: List<WorkspaceToolInput> = emptyList(),
    val prepare: WorkspaceToolPrepare? = null,
    val result: WorkspaceToolResult = WorkspaceToolResult(),
)

@Serializable
data class WorkspaceToolEntry(
    val mode: String = "command",
    val command: String? = null,
    val working_directory: String = ".",
    val html: String? = null,
    val port: Int? = null,
)

@Serializable
data class WorkspaceToolInput(
    val name: String,
    val label: String = "",
    val type: String = "text",
    val required: Boolean = false,
    val default: String? = null,
    val placeholder: String? = null,
    val options: List<String> = emptyList(),
)

@Serializable
data class WorkspaceToolPrepare(
    val command: String,
    val working_directory: String = ".",
    val once: Boolean = true,
)

@Serializable
data class WorkspaceToolResult(
    val type: String = "text",
    val path: String? = null,
    val html: String? = null,
)

data class WorkspaceTool(
    val rootPath: String,
    val manifest: WorkspaceToolManifest,
)

enum class WorkspaceToolRunStatus {
    PREPARING,
    RUNNING,
    FINISHED,
    FAILED,
    STOPPED,
}

data class WorkspaceToolRun(
    val id: String,
    val workspaceId: String,
    val tool: WorkspaceToolManifest,
    val status: WorkspaceToolRunStatus,
    val log: String = "",
    val exitCode: Int? = null,
    val sessionId: Long? = null,
    val webUrl: String? = null,
    val resultFiles: List<WorkspaceToolFile> = emptyList(),
    val error: String? = null,
)

data class WorkspaceToolFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)
