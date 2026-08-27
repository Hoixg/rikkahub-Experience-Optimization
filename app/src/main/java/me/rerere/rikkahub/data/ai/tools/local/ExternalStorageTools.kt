package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.io.InputStream
import java.util.Base64
import java.util.UUID

private const val MAX_EXTERNAL_READ_BYTES = 1_048_576
private const val MAX_EXTERNAL_WRITE_BYTES = 32 * 1024 * 1024

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
private fun uriArg(raw: String?): String? = raw?.takeIf { ContentUriSafetyGuard.check(it) == null }

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
        grants.forEach { g -> addJsonObject { put("content_uri", g.contentUri); put("display_name", g.displayName); put("authority", g.authority) } }
    }) }) } }
)

fun grantDirectoryAccessTool(context: Context, store: StorageVolumeGrantStore, buffer: SafPickerResultBuffer): Tool = Tool(
    name = "grant_directory_access", description = "打开系统文件夹选择器，请用户授权一个外部文件夹。授权后可读写其中的文件。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("initial_uri", buildJsonObject { put("type", "string") }) }) },
    execute = { input ->
        val id = UUID.randomUUID().toString(); val deferred = buffer.register(id)
        val initial = input.jsonObject["initial_uri"]?.jsonPrimitive?.contentOrNull
        val intent = Intent(context, ToolHostActivity::class.java).apply { putExtra(ToolHostActivity.EXTRA_REQUEST_ID, id); putExtra(ToolHostActivity.EXTRA_INITIAL_URI, initial); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        when (val r = withTimeoutOrNull(300_000) { deferred.await() }) {
            is SafPickerResult.Granted -> { val uri = Uri.parse(r.contentUri); val name = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, uri)?.name } ?: r.contentUri; store.add(StorageVolumeGrantStore.Grant(r.contentUri, name, uri.authority ?: "unknown")); part(buildJsonObject { put("granted", true); put("content_uri", r.contentUri); put("display_name", name) }) }
            is SafPickerResult.Error -> fail("授权失败", r.message)
            else -> part(buildJsonObject { put("granted", false); put("message", "用户取消了授权") })
        }
    }
)

fun fileManagerTools(context: Context): List<Tool> = listOf(
    fileTool(context, "list_files", "列出目录中的文件"), fileTool(context, "read_file", "读取文件"), fileTool(context, "file_info", "查看文件信息"), fileTool(context, "find_files", "按名称搜索文件"),
    fileTool(context, "write_binary_file", "写入二进制文件"), fileTool(context, "create_directory", "创建目录"), fileTool(context, "delete_file", "删除文件或目录"), fileTool(context, "copy_file", "复制文件"), fileTool(context, "move_file", "移动或重命名文件")
)

private fun fileTool(context: Context, name: String, description: String): Tool = Tool(
    name = name, description = "$description。仅支持已授权的 content:// 文件夹 URI。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") }); put("root", buildJsonObject { put("type", "string") }); put("query", buildJsonObject { put("type", "string") }); put("max_bytes", buildJsonObject { put("type", "integer") }); put("base64_content", buildJsonObject { put("type", "string") }); put("name", buildJsonObject { put("type", "string") }); put("src", buildJsonObject { put("type", "string") }); put("dst", buildJsonObject { put("type", "string") })
    }) },
    execute = { input ->
        withContext(Dispatchers.IO) {
          try {
            val o = input.jsonObject; val path = uriArg(o["path"]?.jsonPrimitive?.contentOrNull ?: o["root"]?.jsonPrimitive?.contentOrNull)
            when (name) {
            "list_files", "find_files" -> { val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI"); val d = ContentUriResolver.resolve(context, p) ?: return@withContext fail("未授权", "目录不存在或尚未授权"); if (!d.isDirectory) return@withContext fail("类型错误", "目标不是目录"); val q = o["query"]?.jsonPrimitive?.contentOrNull; part(buildJsonObject { put("files", buildJsonArray { d.listFiles().filter { q == null || (it.name ?: "").contains(q, true) }.take(500).forEach { f -> addJsonObject { put("path", f.uri.toString()); put("name", f.name ?: ""); put("is_directory", f.isDirectory); put("size_bytes", f.length()) } } }) }) }
            "read_file" -> {
                val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                val maxBytes = (o["max_bytes"]?.jsonPrimitive?.intOrNull ?: MAX_EXTERNAL_READ_BYTES)
                    .coerceIn(1, MAX_EXTERNAL_READ_BYTES)
                val stream = ContentUriResolver.openInput(context, p)
                    ?: return@withContext fail("读取失败", "无法打开文件")
                val (bytes, overflow) = stream.use { readLimited(it, maxBytes) }
                part(buildJsonObject {
                    put("content", bytes.toString(Charsets.UTF_8))
                    put("content_base64", Base64.getEncoder().encodeToString(bytes))
                    put("bytes_read", bytes.size)
                    put("truncated", overflow)
                })
            }
            "file_info" -> { val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI"); val f = ContentUriResolver.resolve(context, p) ?: return@withContext part(buildJsonObject { put("path", p); put("exists", false) }); part(buildJsonObject { put("path", p); put("exists", true); put("name", f.name ?: ""); put("is_directory", f.isDirectory); put("size_bytes", f.length()); put("modified_at_ms", f.lastModified()) }) }
            "write_binary_file" -> {
                val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                val encoded = o["base64_content"]?.jsonPrimitive?.content ?: ""
                val maxEncodedLength = ((MAX_EXTERNAL_WRITE_BYTES.toLong() + 2) / 3 * 4).toInt()
                if (encoded.length > maxEncodedLength) {
                    return@withContext fail("文件过大", "单次写入不能超过 32 MiB")
                }
                val b = runCatching { Base64.getDecoder().decode(encoded) }
                    .getOrElse { return@withContext fail("参数错误", "base64_content 不是有效内容") }
                if (b.size > MAX_EXTERNAL_WRITE_BYTES) return@withContext fail("文件过大", "单次写入不能超过 32 MiB")
                ContentUriResolver.openOutput(context, p)?.use { it.write(b) }
                    ?: return@withContext fail("写入失败", "无法打开文件")
                part(buildJsonObject { put("success", true); put("bytes_written", b.size) })
            }
            "create_directory" -> { val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI"); val d = ContentUriResolver.resolve(context, p) ?: return@withContext fail("未授权", "目录不存在或尚未授权"); val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@withContext fail("参数错误", "缺少目录名称"); val c = d.createDirectory(n) ?: return@withContext fail("创建失败", "系统拒绝创建目录"); part(buildJsonObject { put("success", true); put("path", c.uri.toString()) }) }
            "delete_file" -> { val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI"); val f = ContentUriResolver.resolve(context, p) ?: return@withContext fail("未找到", "文件不存在"); if (!f.delete()) return@withContext fail("删除失败", "系统拒绝删除目标"); part(buildJsonObject { put("success", true); put("path", p) }) }
            "copy_file", "move_file" -> {
                val s = uriArg(o["src"]?.jsonPrimitive?.contentOrNull) ?: return@withContext fail("参数错误", "缺少源文件 URI")
                val d = uriArg(o["dst"]?.jsonPrimitive?.contentOrNull) ?: return@withContext fail("参数错误", "缺少目标文件 URI")
                val sf = ContentUriResolver.resolve(context, s) ?: return@withContext fail("未找到", "源文件不存在")
                if (sf.isDirectory) return@withContext fail("类型错误", "暂不支持复制目录")
                val inputStream = ContentUriResolver.openInput(context, s)
                    ?: return@withContext fail("读取失败", "无法读取源文件")
                var copied = 0L
                inputStream.use { input ->
                    val outputStream = ContentUriResolver.openOutput(context, d)
                        ?: return@withContext fail("写入失败", "无法写入目标文件")
                    outputStream.use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            copied += count
                        }
                    }
                }
                if (name == "move_file" && !sf.delete()) {
                    part(buildJsonObject { put("success", false); put("copied", true); put("source_deleted", false); put("bytes_copied", copied); put("error", "源文件删除失败，目标副本已创建") })
                } else {
                    part(buildJsonObject { put("success", true); put("bytes_copied", copied); if (name == "move_file") put("source_deleted", true) })
                }
            }
            else -> fail("不支持的操作", name)
            }
          } catch (_: SecurityException) {
              fail("未授权", "文件夹授权无效或已被系统撤销，请重新授权")
          } catch (error: Throwable) {
              fail("I/O 错误", error.message ?: "外部文件操作失败")
          }
        }
    }
)
