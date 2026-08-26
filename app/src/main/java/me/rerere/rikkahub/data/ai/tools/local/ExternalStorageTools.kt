package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.util.Base64
import java.util.UUID

private fun part(obj: JsonObject) = listOf(UIMessagePart.Text(obj.toString()))
private fun fail(code: String, detail: String) = part(buildJsonObject { put("error", code); put("detail", detail) })
private fun uriArg(raw: String?): String? = raw?.takeIf { it.startsWith("content://") && Uri.parse(it).authority != null }
private fun resolve(context: Context, raw: String): DocumentFile? = runCatching {
    val uri = Uri.parse(raw)
    DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() }
        ?: DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }
}.getOrNull()

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = "列出手机内部存储、SD 卡和 USB 存储卷及容量。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val sm = context.getSystemService(StorageManager::class.java)
            ?: return@Tool fail("不可用", "系统不支持读取存储卷")
        part(buildJsonObject {
            put("volumes", buildJsonArray {
                sm.storageVolumes.forEach { v ->
                    addJsonObject {
                        put("id", v.uuid ?: v.directory?.absolutePath ?: v.toString())
                        put("label", v.getDescription(context) ?: "存储设备")
                        put("type", if (v.isPrimary) "internal" else if (v.isRemovable) "sd_or_usb" else "external")
                        put("primary", v.isPrimary)
                        put("removable", v.isRemovable)
                        put("mounted", v.state == Environment.MEDIA_MOUNTED)
                        put("free_bytes", v.directory?.freeSpace ?: 0L)
                        put("total_bytes", v.directory?.totalSpace ?: 0L)
                    }
                }
            })
        })
    }
)

fun listGrantedDirectoriesTool(store: StorageVolumeGrantStore): Tool = Tool(
    name = "list_granted_directories", description = "列出用户已经授权给应用的外部文件夹。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { val grants = store.reconcile(); part(buildJsonObject { put("directories", buildJsonArray {
        grants.forEach { g -> addJsonObject { put("content_uri", g.contentUri); put("display_name", g.displayName); put("authority", g.authority) } }
    }) }) }
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
            is SafPickerResult.Granted -> { val uri = Uri.parse(r.contentUri); val name = DocumentFile.fromTreeUri(context, uri)?.name ?: r.contentUri; store.add(StorageVolumeGrantStore.Grant(r.contentUri, name, uri.authority ?: "unknown")); part(buildJsonObject { put("granted", true); put("content_uri", r.contentUri); put("display_name", name) }) }
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
        put("path", buildJsonObject { put("type", "string") }); put("root", buildJsonObject { put("type", "string") }); put("query", buildJsonObject { put("type", "string") }); put("base64_content", buildJsonObject { put("type", "string") }); put("name", buildJsonObject { put("type", "string") }); put("src", buildJsonObject { put("type", "string") }); put("dst", buildJsonObject { put("type", "string") })
    }) },
    execute = { input ->
        val o = input.jsonObject; val path = uriArg(o["path"]?.jsonPrimitive?.contentOrNull ?: o["root"]?.jsonPrimitive?.contentOrNull)
        when (name) {
            "list_files", "find_files" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val d = resolve(context, p) ?: return@Tool fail("未授权", "目录不存在或尚未授权"); if (!d.isDirectory) return@Tool fail("类型错误", "目标不是目录"); val q = o["query"]?.jsonPrimitive?.contentOrNull; part(buildJsonObject { put("files", buildJsonArray { d.listFiles().filter { q == null || (it.name ?: "").contains(q, true) }.take(500).forEach { f -> addJsonObject { put("path", f.uri.toString()); put("name", f.name ?: ""); put("is_directory", f.isDirectory); put("size_bytes", f.length()) } } }) }) }
            "read_file" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val b = context.contentResolver.openInputStream(Uri.parse(p))?.use { it.readBytes().take(1_048_576).toByteArray() } ?: return@Tool fail("读取失败", "无法打开文件"); part(buildJsonObject { put("content", b.toString(Charsets.UTF_8)); put("content_base64", Base64.getEncoder().encodeToString(b)); put("bytes_read", b.size) }) }
            "file_info" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val f = resolve(context, p) ?: return@Tool part(buildJsonObject { put("path", p); put("exists", false) }); part(buildJsonObject { put("path", p); put("exists", true); put("name", f.name ?: ""); put("is_directory", f.isDirectory); put("size_bytes", f.length()); put("modified_at_ms", f.lastModified()) }) }
            "write_binary_file" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val b = runCatching { Base64.getDecoder().decode(o["base64_content"]?.jsonPrimitive?.content ?: "") }.getOrElse { return@Tool fail("参数错误", "base64_content 不是有效内容") }; context.contentResolver.openOutputStream(Uri.parse(p))?.use { it.write(b) } ?: return@Tool fail("写入失败", "无法打开文件"); part(buildJsonObject { put("success", true); put("bytes_written", b.size) }) }
            "create_directory" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val d = resolve(context, p) ?: return@Tool fail("未授权", "目录不存在或尚未授权"); val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@Tool fail("参数错误", "缺少目录名称"); val c = d.createDirectory(n) ?: return@Tool fail("创建失败", "系统拒绝创建目录"); part(buildJsonObject { put("success", true); put("path", c.uri.toString()) }) }
            "delete_file" -> { val p = path ?: return@Tool fail("参数错误", "必须提供已授权的 content:// URI"); val f = resolve(context, p) ?: return@Tool fail("未找到", "文件不存在"); part(buildJsonObject { put("success", f.delete()); put("path", p) }) }
            "copy_file", "move_file" -> { val s = uriArg(o["src"]?.jsonPrimitive?.contentOrNull) ?: return@Tool fail("参数错误", "缺少源文件 URI"); val d = uriArg(o["dst"]?.jsonPrimitive?.contentOrNull) ?: return@Tool fail("参数错误", "缺少目标文件 URI"); val sf = resolve(context, s) ?: return@Tool fail("未找到", "源文件不存在"); val b = context.contentResolver.openInputStream(sf.uri)?.use { it.readBytes() } ?: return@Tool fail("读取失败", "无法读取源文件"); context.contentResolver.openOutputStream(Uri.parse(d))?.use { it.write(b) } ?: return@Tool fail("写入失败", "无法写入目标文件"); if (name == "move_file") sf.delete(); part(buildJsonObject { put("success", true); put("bytes_copied", b.size) }) }
            else -> fail("不支持的操作", name)
        }
    }
)
