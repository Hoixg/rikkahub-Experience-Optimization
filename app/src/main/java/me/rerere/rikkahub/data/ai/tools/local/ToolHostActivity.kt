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

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
        buffer.complete(requestId, result)
        finish()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) { finish(); return }
        val initial = intent.getStringExtra(EXTRA_INITIAL_URI)?.let(Uri::parse)
        runCatching { picker.launch(initial) }.onFailure {
            buffer.complete(requestId, SafPickerResult.Error("无法打开系统文件夹选择器：${it.message ?: "未知错误"}"))
            finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_INITIAL_URI = "initial_uri"
    }
}
