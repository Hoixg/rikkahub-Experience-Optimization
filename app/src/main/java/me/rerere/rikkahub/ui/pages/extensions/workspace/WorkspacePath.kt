package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A safe path reference into the workspace files area. */
data class WorkspacePathReference(val path: String)

private const val WORKSPACE_PREFIX = "/workspace"

fun parseWorkspacePathReference(source: String): WorkspacePathReference? {
    val value = source.trim().removeSurrounding("<", ">")
        .substringBefore('#')
        .substringBefore('?')
    val filePath = when {
        value.startsWith("file:///", ignoreCase = true) -> value.substring(7)
        else -> value
    }
    val decoded = runCatching {
        URLDecoder.decode(filePath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrNull() ?: return null
    val normalized = decoded.replace('\\', '/')
    if (!normalized.equals(WORKSPACE_PREFIX, ignoreCase = true) &&
        !normalized.startsWith("$WORKSPACE_PREFIX/", ignoreCase = true)
    ) return null

    val relative = normalized.substring(WORKSPACE_PREFIX.length).trimStart('/')
    val segments = mutableListOf<String>()
    for (segment in relative.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> return null
            else -> segments += segment
        }
    }
    return WorkspacePathReference(segments.joinToString("/"))
}

fun WorkspacePathReference.parentPath(): String = path.substringBeforeLast('/', "")

private val PLAIN_WORKSPACE_PATH = Regex(
    "(?<![\\w\"'(/])((?:file:///)?/workspace/[^\\s<>()\\]]+)",
    RegexOption.IGNORE_CASE,
)

/** Turns bare workspace paths into markdown links without touching fenced code blocks. */
fun linkifyWorkspacePaths(text: String): String = buildString {
    var cursor = 0
    var inFence = false
    text.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            append(line)
        } else if (inFence) {
            append(line)
        } else {
            append(PLAIN_WORKSPACE_PATH.replace(line) { match ->
                val path = match.groupValues[1].trimEnd('.', ',', ';', ':')
                "[$path]($path)"
            })
        }
    }
}
