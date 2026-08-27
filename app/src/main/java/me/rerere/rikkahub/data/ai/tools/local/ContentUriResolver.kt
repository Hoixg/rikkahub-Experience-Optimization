package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

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
    private fun hasTreeGrant(context: Context, uri: Uri): Boolean = runCatching {
        val target = uri.toString()
        context.contentResolver.persistedUriPermissions.any { permission ->
            val held = permission.uri.toString()
            target == held || target.startsWith(held)
        }
    }.getOrDefault(false)

    fun resolve(context: Context, raw: String): DocumentFile? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val singleDocument = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentFile.fromSingleUri(context, uri)
            } else {
                null
            }
        }.getOrNull()
        if (singleDocument != null && singleDocument.exists()) return singleDocument
        val treeDocument = runCatching {
            if (hasTreeGrant(context, uri)) DocumentFile.fromTreeUri(context, uri) else null
        }.getOrNull()
        if (treeDocument != null && treeDocument.exists()) return treeDocument
        return runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
    }

    fun openInput(context: Context, raw: String) =
        context.contentResolver.openInputStream(Uri.parse(raw))

    fun openOutput(context: Context, raw: String) =
        context.contentResolver.openOutputStream(Uri.parse(raw))
}
