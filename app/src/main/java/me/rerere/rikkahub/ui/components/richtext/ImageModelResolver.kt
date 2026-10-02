package me.rerere.rikkahub.ui.components.richtext

import java.io.File

private val IMAGE_URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

internal fun resolveChatImageModel(model: String?): Any? {
    if (model == null) return null
    if (IMAGE_URI_SCHEME.containsMatchIn(model) && !WINDOWS_ABSOLUTE_PATH.matches(model)) {
        return model
    }

    val file = runCatching { File(model) }.getOrNull() ?: return model
    return if (file.isAbsolute && runCatching { file.isFile }.getOrDefault(false)) file else model
}
