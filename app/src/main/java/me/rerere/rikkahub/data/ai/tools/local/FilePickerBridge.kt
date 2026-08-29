package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val FILE_PICKER_TIMEOUT_MILLIS = 300_000L

/** Runs a system picker for a tool call and waits for its result without buffering file data. */
suspend fun launchToolFilePicker(
    context: Context,
    buffer: SafPickerResultBuffer,
    mode: String,
    mimeType: String = "*/*",
    suggestedName: String = "exported_file",
    initialUri: String? = null,
): SafPickerResult {
    val requestId = UUID.randomUUID().toString()
    val deferred = buffer.register(requestId)
    val intent = Intent(context, ToolHostActivity::class.java).apply {
        putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
        putExtra(ToolHostActivity.EXTRA_MODE, mode)
        putExtra(ToolHostActivity.EXTRA_MIME_TYPE, mimeType)
        putExtra(ToolHostActivity.EXTRA_SUGGESTED_NAME, suggestedName)
        if (!initialUri.isNullOrBlank()) {
            putExtra(ToolHostActivity.EXTRA_INITIAL_URI, initialUri)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(intent)
        }
        withTimeoutOrNull(FILE_PICKER_TIMEOUT_MILLIS) { deferred.await() }
            ?: SafPickerResult.Error("等待系统文件选择器超时")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        SafPickerResult.Error("无法打开系统文件选择器：${error.message ?: "未知错误"}")
    } finally {
        buffer.cancel(requestId)
    }
}
