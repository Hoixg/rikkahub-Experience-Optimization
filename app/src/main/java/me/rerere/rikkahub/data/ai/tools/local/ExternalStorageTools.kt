package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.io.InputStream


/** Reads pipe-backed SAF providers in a loop and reports whether another byte remains. */
internal fun readLimited(input: InputStream, limit: Int): Pair<ByteArray, Boolean> {
    val bytes = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = input.read(bytes, offset, limit - offset)
        if (count < 0) return (bytes.copyOf(offset) to false)
        if (count == 0) continue
        offset += count
    }
    return bytes to (input.read() >= 0)
}

private fun part(obj: JsonObject) = listOf(UIMessagePart.Text(obj.toString()))
private fun fail(code: String, detail: String) = part(buildJsonObject { put("error", code); put("detail", detail) })
private fun uriArg(vararg raw: String?): String? = raw.asSequence()
    .mapNotNull { it?.trim()?.takeIf { value -> ContentUriSafetyGuard.check(value) == null } }
    .firstOrNull()


internal fun safeChildName(raw: String?): String? {
    val name = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (name == "." || name == ".." || name.contains('/') ||
        name.contains('\\') || name.contains('\u0000')
    ) return null
    return name
}


private fun classifyAuthority(authority: String): String = when {
    authority == "com.android.externalstorage.documents" -> "volume_root"
    authority == "com.android.providers.downloads.documents" -> "downloads"
    authority.contains("docs.storage") || authority.contains("dropbox") ||
        authority.contains("skydrive") || authority.contains("drive") -> "cloud"
    else -> "other"
}


/** Accept the field names emitted by the grant tool and used by different model adapters. */
internal fun contentUriArg(obj: JsonObject): String? = uriArg(
    obj["path"]?.jsonPrimitive?.contentOrNull,
    obj["root"]?.jsonPrimitive?.contentOrNull,
    obj["content_uri"]?.jsonPrimitive?.contentOrNull,
    obj["directory_uri"]?.jsonPrimitive?.contentOrNull,
    obj["uri"]?.jsonPrimitive?.contentOrNull,
)

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = "列出手机内部存储、SD 卡和 USB 存储卷及容量。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        withContext(Dispatchers.IO) {
            val sm = context.getSystemService(StorageManager::class.java)
                ?: return@withContext fail("不可用", "系统不支持读取存储卷")
            part(buildJsonObject {
                put("volumes", buildJsonArray {
                    sm.storageVolumes.forEach { v ->
                        addJsonObject {
                            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else null
                            put("id", v.uuid ?: directory?.absolutePath ?: v.toString())
                            put("label", v.getDescription(context) ?: "存储设备")
                            put("type", if (v.isPrimary) "internal" else if (v.isRemovable) "sd_or_usb" else "external")
                            put("primary", v.isPrimary)
                            put("removable", v.isRemovable)
                            put("mounted", v.state == Environment.MEDIA_MOUNTED)
                            put("free_bytes", directory?.freeSpace ?: 0L)
                            put("total_bytes", directory?.totalSpace ?: 0L)
                        }
                    }
                })
            })
        }
    }
)

fun listGrantedDirectoriesTool(store: StorageVolumeGrantStore): Tool = Tool(
    name = "list_granted_directories", description = "列出用户已经授权给应用的外部文件夹。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { withContext(Dispatchers.IO) { val grants = store.reconcile(); part(buildJsonObject { put("directories", buildJsonArray {
        grants.forEach { g -> addJsonObject {
            put("content_uri", g.contentUri)
            put("display_name", g.displayName)
            put("authority", g.authority)
            put("kind", classifyAuthority(g.authority))
        } }
    }) }) } }
)

fun grantDirectoryAccessTool(context: Context, store: StorageVolumeGrantStore, buffer: SafPickerResultBuffer): Tool = Tool(
    name = "grant_directory_access", description = "打开系统文件夹选择器，请用户授权一个外部文件夹。授权后可读写其中的文件。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("initial_uri", buildJsonObject { put("type", "string") }) }) },
    execute = { input ->
        val initial = input.jsonObject["initial_uri"]?.jsonPrimitive?.contentOrNull
        when (val r = launchToolFilePicker(
            context = context,
            buffer = buffer,
            mode = ToolHostActivity.MODE_DIRECTORY,
            initialUri = initial,
        )) {
            is SafPickerResult.Granted -> {
                val uri = Uri.parse(r.contentUri)
                val authority = uri.authority ?: "unknown"
                val name = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, uri)?.name } ?: r.contentUri
                store.add(StorageVolumeGrantStore.Grant(r.contentUri, name, authority))
                part(buildJsonObject {
                    put("granted", true)
                    put("content_uri", r.contentUri)
                    put("display_name", name)
                    put("authority", authority)
                })
            }
            is SafPickerResult.Error -> fail("授权失败", r.message)
            else -> part(buildJsonObject { put("granted", false); put("message", "用户取消了授权") })
        }
    }
)
