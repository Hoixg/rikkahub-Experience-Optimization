package me.rerere.rikkahub.data.ai.tools.local

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.koin.android.ext.android.inject

class ToolHostActivity : AppCompatActivity() {
    private val buffer: SafPickerResultBuffer by inject()
    private var requestId = ""
    private var pickerLaunched = false
    private var resultCompleted = false

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val result = if (uri == null) {
            SafPickerResult.Cancelled
        } else {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                SafPickerResult.Granted(uri.toString())
            } catch (e: Throwable) {
                SafPickerResult.Error("无法保存文件夹授权：${e.message ?: "系统拒绝了授权"}")
            }
        }
        completeResult(result)
    }

    private val openFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        completeResult(uri?.let { SafPickerResult.FilePicked(it.toString()) } ?: SafPickerResult.Cancelled)
    }

    private val createFilePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        completeResult(uri?.let { SafPickerResult.FilePicked(it.toString()) } ?: SafPickerResult.Cancelled)
    }

    private fun completeResult(result: SafPickerResult) {
        if (resultCompleted) return
        resultCompleted = true
        buffer.complete(requestId, result)
        finish()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) { finish(); return }
        pickerLaunched = state?.getBoolean(KEY_PICKER_LAUNCHED, false) == true
        if (pickerLaunched) return

        // Activity recreation while DocumentsUI is open must not launch a second picker.
        pickerLaunched = true
        val initial = intent.getStringExtra(EXTRA_INITIAL_URI)?.let(Uri::parse)
        // Existing grant_directory_access callers do not pass a mode; keep them on the original tree picker.
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { MODE_DIRECTORY }
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty().ifBlank { "*/*" }
        val suggestedName = intent.getStringExtra(EXTRA_SUGGESTED_NAME).orEmpty()
            .ifBlank { "exported_file" }
        runCatching {
            when (mode) {
                MODE_DIRECTORY -> directoryPicker.launch(initial)
                MODE_OPEN_FILE -> openFilePicker.launch(arrayOf(mimeType))
                MODE_CREATE_FILE -> createFilePicker.launch(suggestedName)
                else -> error("未知文件选择模式")
            }
        }.onFailure {
            completeResult(SafPickerResult.Error("无法打开系统文件夹选择器：${it.message ?: "未知错误"}"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PICKER_LAUNCHED, pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Covers Back/task removal when DocumentsUI does not deliver a result callback.
        if (!isChangingConfigurations && pickerLaunched && !resultCompleted && requestId.isNotBlank()) {
            resultCompleted = true
            buffer.complete(requestId, SafPickerResult.Cancelled)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_INITIAL_URI = "initial_uri"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_SUGGESTED_NAME = "suggested_name"
        const val MODE_DIRECTORY = "directory"
        const val MODE_OPEN_FILE = "open_file"
        const val MODE_CREATE_FILE = "create_file"
        private const val KEY_PICKER_LAUNCHED = "picker_launched"
    }
}
