package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun archivePart(o: JsonObject) = listOf(UIMessagePart.Text(o.toString()))
private fun archiveFail(detail: String) = archivePart(buildJsonObject { put("error", "压缩包操作失败"); put("detail", detail) })
private fun uri(v: String?) = v?.takeIf { it.startsWith("content://") }

fun archiveTools(context: Context): List<Tool> = listOf(zipFilesTool(context), unzipFileTool(context), listZipContentsTool(context))

private fun zipFilesTool(context: Context) = Tool(
    name = "zip_files", description = "把多个已授权文件打包成 ZIP。参数 files 为文件 URI 数组，destination 为目标文件 URI。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("files", buildJsonObject { put("type", "array") }); put("destination", buildJsonObject { put("type", "string") }) }, required = listOf("files", "destination")) },
    execute = { input ->
        val o = input.jsonObject; val dst = uri(o["destination"]?.jsonPrimitive?.contentOrNull) ?: return@Tool archiveFail("destination 必须是 content:// URI")
        val items = o["files"]?.jsonArray ?: return@Tool archiveFail("缺少 files")
        val out = context.contentResolver.openOutputStream(Uri.parse(dst)) ?: return@Tool archiveFail("无法写入目标文件")
        runCatching { ZipOutputStream(BufferedOutputStream(out)).use { zip -> items.forEach { e -> val p = uri(e.jsonPrimitive.contentOrNull) ?: return@use; val name = Uri.parse(p).lastPathSegment ?: "file"; zip.putNextEntry(ZipEntry(name)); context.contentResolver.openInputStream(Uri.parse(p))?.use { it.copyTo(zip) }; zip.closeEntry() } } }.fold({ archivePart(buildJsonObject { put("success", true); put("files", items.size) }) }, { archiveFail(it.message ?: "创建 ZIP 失败") })
    }
)

private fun listZipContentsTool(context: Context) = Tool(
    name = "list_zip_contents", description = "查看 ZIP 压缩包中的文件列表。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("zip_file", buildJsonObject { put("type", "string") }) }, required = listOf("zip_file")) },
    execute = { input ->
        val p = uri(input.jsonObject["zip_file"]?.jsonPrimitive?.contentOrNull) ?: return@Tool archiveFail("zip_file 必须是 content:// URI")
        val ins = context.contentResolver.openInputStream(Uri.parse(p)) ?: return@Tool archiveFail("无法读取 ZIP 文件")
        runCatching { val names = mutableListOf<String>(); ZipInputStream(BufferedInputStream(ins)).use { z -> var e = z.nextEntry; while (e != null && names.size < 1000) { names += e.name; e = z.nextEntry } }; archivePart(buildJsonObject { put("entries", buildJsonArray { names.forEach { add(it) } }) }) }.getOrElse { archiveFail(it.message ?: "读取 ZIP 失败") }
    }
)

private fun unzipFileTool(context: Context) = Tool(
    name = "unzip_file", description = "解压 ZIP 到已授权目录。会拒绝带有 .. 的危险路径。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("zip_file", buildJsonObject { put("type", "string") }); put("destination", buildJsonObject { put("type", "string") }) }, required = listOf("zip_file", "destination")) },
    execute = { input ->
        val o = input.jsonObject; val zp = uri(o["zip_file"]?.jsonPrimitive?.contentOrNull) ?: return@Tool archiveFail("zip_file 必须是 content:// URI"); val dp = uri(o["destination"]?.jsonPrimitive?.contentOrNull) ?: return@Tool archiveFail("destination 必须是 content:// URI")
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(dp)) ?: return@Tool archiveFail("目标目录未授权")
        val ins = context.contentResolver.openInputStream(Uri.parse(zp)) ?: return@Tool archiveFail("无法读取 ZIP 文件")
        runCatching { var count = 0; ZipInputStream(BufferedInputStream(ins)).use { z -> var e = z.nextEntry; while (e != null) { val n = e.name; if (n.startsWith("/") || n.split('/').contains("..")) throw IllegalArgumentException("ZIP 包含危险路径: $n"); if (!e.isDirectory) { val f = root.createFile("application/octet-stream", n.substringAfterLast('/')) ?: throw IllegalStateException("无法创建文件: $n"); context.contentResolver.openOutputStream(f.uri)?.use { z.copyTo(it) }; count++ }; e = z.nextEntry } }; archivePart(buildJsonObject { put("success", true); put("files", count) }) }.getOrElse { archiveFail(it.message ?: "解压失败") }
    }
)
