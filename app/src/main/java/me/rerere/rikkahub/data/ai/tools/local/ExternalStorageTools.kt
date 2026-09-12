package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import java.io.InputStream
import java.util.Base64

private const val MAX_EXTERNAL_READ_BYTES = 1_048_576
private const val MAX_EXTERNAL_WRITE_BYTES = 32 * 1024 * 1024
private const val MAX_EXTERNAL_FIND_VISITS = 10_000

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

private fun stringArg(obj: JsonObject, vararg names: String?): String? = names.asSequence()
    .mapNotNull { name -> name?.let { obj[it]?.jsonPrimitive?.contentOrNull?.trim() } }
    .firstOrNull { it.isNotBlank() }

private fun booleanArg(obj: JsonObject, name: String, default: Boolean): Boolean =
    obj[name]?.jsonPrimitive?.booleanOrNull ?: default

internal fun safeChildName(raw: String?): String? {
    val name = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (name == "." || name == ".." || name.contains('/') ||
        name.contains('\\') || name.contains('\u0000')
    ) return null
    return name
}

private fun mimeTypeFor(name: String, requested: String?): String =
    requested?.trim()?.takeIf { it.isNotBlank() }
        ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

private fun classifyAuthority(authority: String): String = when {
    authority == "com.android.externalstorage.documents" -> "volume_root"
    authority == "com.android.providers.downloads.documents" -> "downloads"
    authority.contains("docs.storage") || authority.contains("dropbox") ||
        authority.contains("skydrive") || authority.contains("drive") -> "cloud"
    else -> "other"
}

private fun matchesName(name: String, query: String?, glob: Boolean): Boolean {
    if (query.isNullOrBlank()) return true
    if (!glob || (!query.contains('*') && !query.contains('?'))) {
        return name.contains(query, ignoreCase = true)
    }
    val regex = buildString {
        append('^')
        query.forEach { char ->
            when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(char.toString()))
            }
        }
        append('$')
    }.toRegex(RegexOption.IGNORE_CASE)
    return regex.matches(name)
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

fun fileManagerTools(context: Context): List<Tool> = listOf(
    fileTool(context, "list_files", "列出目录中的文件"), fileTool(context, "read_file", "读取文件"), fileTool(context, "file_info", "查看文件信息"), fileTool(context, "find_files", "按名称搜索文件"),
    fileTool(context, "write_binary_file", "写入二进制文件"), fileTool(context, "create_directory", "创建目录"), fileTool(context, "delete_file", "删除文件或目录"), fileTool(context, "copy_file", "复制文件"), fileTool(context, "move_file", "移动或重命名文件")
)

private fun fileTool(context: Context, name: String, description: String): Tool = Tool(
    name = name,
    description = "$description。仅支持已授权的 content:// 文件夹 URI；目录参数可使用 path、root、content_uri、directory_uri 或 uri。写入目录时同时提供 name。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put("root", buildJsonObject { put("type", "string") })
        put("content_uri", buildJsonObject { put("type", "string") })
        put("directory_uri", buildJsonObject { put("type", "string") })
        put("uri", buildJsonObject { put("type", "string") })
        put("query", buildJsonObject { put("type", "string") })
        put("pattern", buildJsonObject { put("type", "string") })
        put("recursive", buildJsonObject { put("type", "boolean") })
        put("limit", buildJsonObject { put("type", "integer") })
        put("max_bytes", buildJsonObject { put("type", "integer") })
        put("base64_content", buildJsonObject { put("type", "string") })
        put("name", buildJsonObject { put("type", "string") })
        put("file_name", buildJsonObject { put("type", "string") })
        put("filename", buildJsonObject { put("type", "string") })
        put("mime_type", buildJsonObject { put("type", "string") })
        put("overwrite", buildJsonObject { put("type", "boolean") })
        put("src", buildJsonObject { put("type", "string") })
        put("dst", buildJsonObject { put("type", "string") })
        put("dst_name", buildJsonObject { put("type", "string") })
        put("destination_name", buildJsonObject { put("type", "string") })
    }) },
    execute = { input ->
        withContext(Dispatchers.IO) {
            try {
                val o = input.jsonObject
                val path = contentUriArg(o)
                when (name) {
                    "list_files", "find_files" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        if (name == "find_files" &&
                            o["query"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                            o["pattern"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                        ) {
                            return@withContext fail("参数错误", "find_files 必须提供 query 或 pattern")
                        }
                        val directory = ContentUriResolver.resolve(context, p)
                            ?: return@withContext fail("未授权", "目录不存在或尚未授权")
                        if (!directory.isDirectory) return@withContext fail("类型错误", "目标不是目录")
                        val query = o["query"]?.jsonPrimitive?.contentOrNull
                        val pattern = o["pattern"]?.jsonPrimitive?.contentOrNull
                        val recursive = booleanArg(o, "recursive", name == "find_files")
                        val limit = (o["limit"]?.jsonPrimitive?.intOrNull ?: 500).coerceIn(1, 500)
                        val files = mutableListOf<DocumentFile>()
                        var visited = 0
                        var truncated = false
                        fun collect(current: DocumentFile) {
                            for (child in current.listFiles()) {
                                if (visited >= MAX_EXTERNAL_FIND_VISITS) {
                                    truncated = true
                                    return
                                }
                                visited++
                                if (files.size >= limit) { truncated = true; return }
                                val matches = when {
                                    !pattern.isNullOrBlank() -> matchesName(child.name.orEmpty(), pattern, glob = true)
                                    name == "find_files" -> matchesName(child.name.orEmpty(), query, glob = false)
                                    else -> matchesName(child.name.orEmpty(), query, glob = false)
                                }
                                if (matches) files += child
                                if (recursive && child.isDirectory) collect(child)
                            }
                        }
                        collect(directory)
                        part(buildJsonObject {
                            put("files", buildJsonArray {
                                files.forEach { f ->
                                    addJsonObject {
                                        put("path", f.uri.toString())
                                        put("name", f.name ?: "")
                                        put("is_directory", f.isDirectory)
                                        put("size_bytes", if (f.isDirectory) 0L else f.length())
                                        put("modified_at_ms", f.lastModified())
                                        put("is_content_uri", true)
                                        f.type?.let { put("mime", it) }
                                    }
                                }
                            })
                            put("truncated", truncated)
                        })
                    }
                    "read_file" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        val file = ContentUriResolver.resolve(context, p)
                            ?: return@withContext fail("未找到", "文件不存在或尚未授权")
                        if (file.isDirectory) return@withContext fail("类型错误", "目标是目录，不是文件")
                        val maxBytes = (o["max_bytes"]?.jsonPrimitive?.intOrNull ?: MAX_EXTERNAL_READ_BYTES).coerceIn(1, MAX_EXTERNAL_READ_BYTES)
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
                    "file_info" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        val file = ContentUriResolver.resolve(context, p)
                            ?: return@withContext part(buildJsonObject { put("path", p); put("exists", false) })
                        part(buildJsonObject {
                            put("path", file.uri.toString())
                            put("exists", file.exists())
                            put("name", file.name ?: "")
                            put("is_directory", file.isDirectory)
                            put("size_bytes", if (file.isDirectory) 0L else file.length())
                            put("modified_at_ms", file.lastModified())
                            put("is_content_uri", true)
                            file.type?.let { put("mime", it) }
                        })
                    }
                    "write_binary_file" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        val encoded = o["base64_content"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext fail("参数错误", "缺少 base64_content")
                        val maxEncodedLength = ((MAX_EXTERNAL_WRITE_BYTES.toLong() + 2) / 3 * 4).toInt()
                        if (encoded.length > maxEncodedLength) return@withContext fail("文件过大", "单次写入不能超过 32 MiB")
                        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
                            .getOrElse { return@withContext fail("参数错误", "base64_content 不是有效内容") }
                        if (bytes.size > MAX_EXTERNAL_WRITE_BYTES) return@withContext fail("文件过大", "单次写入不能超过 32 MiB")
                        val parentOrFile = ContentUriResolver.resolve(context, p)
                            ?: return@withContext fail("未授权", "目标不存在或尚未授权")
                        val overwrite = booleanArg(o, "overwrite", false)
                        val requestedName = safeChildName(stringArg(o, "name", "file_name", "filename"))
                        val target = if (parentOrFile.isDirectory) {
                            val childName = requestedName
                                ?: return@withContext fail("参数错误", "path 是目录时必须提供 name 文件名")
                            val existing = parentOrFile.findFile(childName)
                            if (existing?.isDirectory == true) return@withContext fail("类型错误", "目标文件名对应的是目录")
                            if (existing != null && !overwrite) {
                                return@withContext fail("文件已存在", "目标文件已存在，覆盖写入时请提供 overwrite=true")
                            }
                            existing ?: parentOrFile.createFile(mimeTypeFor(childName, o["mime_type"]?.jsonPrimitive?.contentOrNull), childName)
                                ?: return@withContext fail("创建失败", "系统拒绝在授权目录中创建文件")
                        } else {
                            if (requestedName != null) return@withContext fail("参数错误", "path 已经是文件 URI，不能再提供 name")
                            if (o.containsKey("overwrite") && !overwrite) {
                                return@withContext fail("文件已存在", "path 已经是文件 URI，覆盖写入时请提供 overwrite=true")
                            }
                            parentOrFile
                        }
                        ContentUriResolver.openOutput(context, target.uri.toString())?.use { it.write(bytes) }
                            ?: return@withContext fail("写入失败", "无法打开文件")
                        part(buildJsonObject {
                            put("success", true)
                            put("path", target.uri.toString())
                            put("bytes_written", bytes.size)
                        })
                    }
                    "create_directory" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        val parent = ContentUriResolver.resolve(context, p)
                            ?: return@withContext fail("未授权", "目录不存在或尚未授权")
                        if (!parent.isDirectory) return@withContext fail("类型错误", "目标不是目录")
                        val childName = safeChildName(stringArg(o, "name"))
                            ?: return@withContext fail("参数错误", "缺少有效的目录名称")
                        val existing = parent.findFile(childName)
                        val created = existing ?: parent.createDirectory(childName)
                            ?: return@withContext fail("创建失败", "系统拒绝创建目录")
                        if (!created.isDirectory) return@withContext fail("类型错误", "同名目标不是目录")
                        part(buildJsonObject { put("success", true); put("path", created.uri.toString()); put("created", existing == null) })
                    }
                    "delete_file" -> {
                        val p = path ?: return@withContext fail("参数错误", "必须提供已授权的 content:// URI")
                        if (ContentUriResolver.isTreeRoot(context, p)) return@withContext fail("根目录受保护", "禁止删除已授权的外部文件夹根目录")
                        val target = ContentUriResolver.resolve(context, p)
                            ?: return@withContext fail("未找到", "文件或目录不存在")
                        val recursive = booleanArg(o, "recursive", false)
                        if (target.isDirectory && target.listFiles().isNotEmpty() && !recursive) {
                            return@withContext fail("目录非空", "删除非空目录时必须提供 recursive=true")
                        }
                        var deleted = 0
                        fun deleteTree(file: DocumentFile): Boolean {
                            if (file.isDirectory) {
                                for (child in file.listFiles()) {
                                    if (!deleteTree(child)) return false
                                }
                            }
                            return file.delete().also { if (it) deleted++ }
                        }
                        if (!deleteTree(target)) return@withContext fail("删除失败", "系统拒绝删除目标，可能只完成了部分删除")
                        part(buildJsonObject { put("success", true); put("path", p); put("deleted_count", deleted) })
                    }
                    "copy_file", "move_file" -> {
                        val sourceUri = uriArg(o["src"]?.jsonPrimitive?.contentOrNull)
                            ?: return@withContext fail("参数错误", "缺少源文件 URI")
                        val destinationUri = uriArg(o["dst"]?.jsonPrimitive?.contentOrNull)
                            ?: return@withContext fail("参数错误", "缺少目标文件 URI")
                        if (name == "move_file" && ContentUriResolver.isTreeRoot(context, sourceUri)) {
                            return@withContext fail("根目录受保护", "禁止移动已授权的外部文件夹根目录")
                        }
                        val source = ContentUriResolver.resolve(context, sourceUri)
                            ?: return@withContext fail("未找到", "源文件不存在或尚未授权")
                        if (source.isDirectory) return@withContext fail("类型错误", "暂不支持复制或移动目录")
                        val destinationParentOrFile = ContentUriResolver.resolve(context, destinationUri)
                            ?: return@withContext fail("未找到", "目标不存在或尚未授权")
                        val overwrite = booleanArg(o, "overwrite", false)
                        val destinationName = safeChildName(stringArg(o, "dst_name", "destination_name", "name"))
                        val destination = if (destinationParentOrFile.isDirectory) {
                            val childName = destinationName
                                ?: return@withContext fail("参数错误", "dst 是目录时必须提供 dst_name")
                            val existing = destinationParentOrFile.findFile(childName)
                            if (existing?.isDirectory == true) return@withContext fail("类型错误", "目标文件名对应的是目录")
                            if (existing != null && o.containsKey("overwrite") && !overwrite) {
                                return@withContext fail("目标已存在", "目标文件已存在，覆盖写入时请提供 overwrite=true")
                            }
                            existing ?: destinationParentOrFile.createFile(
                                mimeTypeFor(childName, o["mime_type"]?.jsonPrimitive?.contentOrNull), childName
                            ) ?: return@withContext fail("创建失败", "系统拒绝创建目标文件")
                        } else {
                            if (destinationName != null) return@withContext fail("参数错误", "dst 已经是文件 URI，不能再提供目标文件名")
                            if (o.containsKey("overwrite") && !overwrite) {
                                return@withContext fail("目标已存在", "目标文件已存在，覆盖写入时请提供 overwrite=true")
                            }
                            destinationParentOrFile
                        }
                        if (source.uri == destination.uri) {
                            return@withContext fail("参数错误", "源文件和目标文件不能是同一个 URI")
                        }
                        val inputStream = ContentUriResolver.openInput(context, source.uri.toString())
                            ?: return@withContext fail("读取失败", "无法读取源文件")
                        var copied = 0L
                        inputStream.use { input ->
                            val output = ContentUriResolver.openOutput(context, destination.uri.toString())
                                ?: return@withContext fail("写入失败", "无法写入目标文件")
                            output.use { out ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    out.write(buffer, 0, count)
                                    copied += count
                                }
                            }
                        }
                        if (name == "move_file" && !source.delete()) {
                            return@withContext part(buildJsonObject {
                                put("success", false); put("copied", true); put("source_deleted", false)
                                put("bytes_copied", copied); put("error", "目标副本已创建，但源文件删除失败")
                            })
                        }
                        part(buildJsonObject {
                            put("success", true); put("from", sourceUri); put("to", destination.uri.toString())
                            put("bytes_copied", copied); if (name == "move_file") put("source_deleted", true)
                        })
                    }
                    else -> fail("不支持的操作", name)
                }
            } catch (_: SecurityException) {
                fail("未授权", "文件夹授权无效或已被系统撤销，请重新授权")
            } catch (error: Throwable) {
                fail("I/O 错误", error.message ?: "外部文件操作失败")
            }
        }
    },
)
