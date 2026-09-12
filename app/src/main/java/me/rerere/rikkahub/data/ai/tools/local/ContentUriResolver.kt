package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Structural validation for SAF URIs. Android's persisted grant remains the access gate. */
internal object ContentUriSafetyGuard {
    data class Violation(val code: String, val detail: String)

    fun isContentUri(raw: String?): Boolean = raw?.startsWith("content://") == true

    fun check(raw: String?): Violation? {
        if (raw.isNullOrBlank()) {
            return Violation("path_blocked", "content URI must not be empty")
        }
        if (!raw.startsWith("content://")) {
            return Violation("path_blocked", "URI scheme must be content://")
        }
        val afterScheme = raw.removePrefix("content://")
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        if (authority.isBlank()) {
            return Violation("path_blocked", "content URI has no authority")
        }
        val path = if (slash < 0) "" else afterScheme.substring(slash)
        if (path.isBlank() || path == "/") {
            return Violation("path_blocked", "content URI has no path")
        }
        return null
    }
}

/** Shared SAF resolver used by external-storage tools. */
internal object ContentUriResolver {
    private data class TreeUriParts(
        val authority: String,
        val treeId: String,
        val documentId: String?,
    )

    /** Parse SAF tree URIs without relying on Android framework URI classification. */
    private fun treeUriParts(raw: String): TreeUriParts? = runCatching {
        if (!raw.startsWith("content://")) return@runCatching null
        val remainder = raw.removePrefix("content://")
        val authorityEnd = remainder.indexOf('/')
        if (authorityEnd <= 0) return@runCatching null
        val authority = remainder.substring(0, authorityEnd)
        val path = remainder.substring(authorityEnd).substringBefore("?").substringBefore("#")
        val treeMarker = "/tree/"
        val treeStart = path.indexOf(treeMarker)
        if (treeStart < 0) return@runCatching null
        val afterTree = path.substring(treeStart + treeMarker.length)
        val documentMarker = "/document/"
        val documentStart = afterTree.indexOf(documentMarker)
        val encodedTreeId = if (documentStart < 0) afterTree else afterTree.substring(0, documentStart)
        if (encodedTreeId.isBlank()) return@runCatching null
        val encodedDocumentId = if (documentStart < 0) null else afterTree.substring(documentStart + documentMarker.length)
            .takeIf { it.isNotBlank() }
        TreeUriParts(
            authority = authority,
            treeId = decodeUriPart(encodedTreeId),
            documentId = encodedDocumentId?.let(::decodeUriPart),
        )
    }.getOrNull()

    private fun decodeUriPart(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun treeKey(uri: Uri): String? = treeUriParts(uri.toString())?.let { parts ->
        parts.authority + ":" + parts.treeId
    }

    private fun hasTreeGrant(context: Context, uri: Uri): Boolean = runCatching {
        val target = uri.normalizeScheme()
        val targetTreeKey = treeKey(target)
        context.contentResolver.persistedUriPermissions.any { permission ->
            val held = permission.uri.normalizeScheme()
            held == target || (targetTreeKey != null && treeKey(held) == targetTreeKey)
        }
    }.getOrDefault(false)

    fun resolve(context: Context, raw: String): DocumentFile? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        // All external-file operations in this tool family must be backed by a persisted
        // grant. Without this gate, fromSingleUri() can expose an arbitrary provider URI
        // even though the user never granted its containing folder.
        if (treeUriParts(raw) != null && !hasTreeGrant(context, uri)) return null

        // A tree child URI contains both /tree/<root> and /document/<child>. Keep it
        // tree-backed so directory children retain createFile/listFiles support. A
        // single-document wrapper is only a fallback for providers that reject the tree form.
        if (treeUriParts(raw) != null) {
            val treeDocument = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            if (treeDocument != null && treeDocument.exists()) return treeDocument
        }

        return runCatching {
            DocumentFile.fromSingleUri(context, uri)
        }.getOrNull()?.takeIf { it.exists() }
    }

    /** Returns true for the granted tree itself, including its tree/document root form. */
    fun isTreeRoot(context: Context, raw: String): Boolean = isTreeRootUri(raw)

    internal fun isTreeRootUri(raw: String): Boolean = treeUriParts(raw)?.let { parts ->
        parts.documentId == null || parts.documentId == parts.treeId
    } == true

    fun openInput(context: Context, raw: String) =
        context.contentResolver.openInputStream(Uri.parse(raw))

    fun openOutput(context: Context, raw: String) =
        context.contentResolver.openOutputStream(Uri.parse(raw))

    fun notGrantedEnvelope(raw: String): String {
        val authority = raw.substringAfter("content://", "")
            .substringBefore('/')
            .ifBlank { "unknown" }
        return buildJsonObject {
            put("error", "directory_not_granted")
            put("detail", "call grant_directory_access first")
            put("authority", authority)
        }.toString()
    }
}
