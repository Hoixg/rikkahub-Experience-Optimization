package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 交给系统应用 (视频/音频/文档等) 打开
 */
enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

internal sealed interface WorkspaceMarkdownImagePath {
    data class Network(val url: String) : WorkspaceMarkdownImagePath
    data class Local(val path: String) : WorkspaceMarkdownImagePath
}

private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

internal fun resolveWorkspaceMarkdownImagePath(
    markdownPath: String,
    source: String,
): WorkspaceMarkdownImagePath? {
    val trimmed = source.trim().removeSurrounding("<", ">")
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return WorkspaceMarkdownImagePath.Network(trimmed)
    }
    if (trimmed.isEmpty() || URI_SCHEME.containsMatchIn(trimmed)) return null

    val withoutSuffix = trimmed.substringBefore('#').substringBefore('?')
    val decoded = runCatching {
        URLDecoder.decode(withoutSuffix.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrNull()?.replace('\\', '/') ?: return null
    if (URI_SCHEME.containsMatchIn(decoded)) return null
    val segments = if (decoded.startsWith('/')) {
        mutableListOf()
    } else {
        markdownPath.substringBeforeLast('/', "")
            .split('/')
            .filterTo(mutableListOf()) { it.isNotEmpty() }
    }
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    val resolved = segments.joinToString("/")
    if (resolved.isEmpty() || !isWorkspaceImageFileName(resolved)) return null
    return WorkspaceMarkdownImagePath.Local(resolved)
}

internal fun isWorkspaceImageFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext.isEmpty() -> WorkspaceFileType.OTHER
        isWorkspaceImageFileName(name) -> WorkspaceFileType.IMAGE
        ext in TEXT_EXTENSIONS -> WorkspaceFileType.TEXT
        else -> WorkspaceFileType.OTHER
    }
}
