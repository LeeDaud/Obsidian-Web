package xyz.leedaud.echo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.notes.newId
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Explicitly selected content is copied privately; no source URI or filesystem path reaches JS. */
class LocalUiPickedFiles(private val context: Context,
    private val root: File = File(context.filesDir, "local-ui-picked"), private val limit: Long = 25L * 1024 * 1024) {
    private fun id(resource: String): String {
        if (!Regex("picked/[a-f0-9-]{36}").matches(resource)) throw LocalUiFailure("文件资源标识无效")
        return resource.removePrefix("picked/")
    }
    fun copy(uris: List<Uri>): JSONObject {
        if (uris.size > 100 || uris.any { it.scheme != "content" }) throw LocalUiFailure("只接受系统选择的内容文件，最多100个")
        var total = 0L
        val files = uris.map { uri ->
            val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "selected.bin"
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val item = context.contentResolver.openInputStream(uri)?.use { store(it, filename, mime, limit - total) }
                ?: throw LocalUiFailure("选中文件无法读取，原内容保留")
            total += item.getLong("size")
            item
        }
        return JSONObject().put("files", JSONArray(files))
    }
    internal fun store(input: InputStream, filename: String, type: String, maxBytes: Long = limit): JSONObject {
        check(root.isDirectory || root.mkdirs())
        val key = newId()
        val atomic = AtomicFile(File(root, "$key.bin"))
        val output = atomic.startWrite()
        var size = 0L
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > minOf(limit, maxBytes)) throw LocalUiFailure("选中文件合计不能超过25MiB，原内容保留")
                output.write(buffer, 0, count); hash.update(buffer, 0, count)
            }
            output.fd.sync(); atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        val mime = Intent.normalizeMimeType(type).orEmpty().takeIf { Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(it) }
            ?: "application/octet-stream"
        val safeName = filename.map { if (it.code < 32 || it in "/\\") '_' else it }.joinToString("").take(180).ifBlank { "selected.bin" }
        val data = JSONObject().put("name", "picked/$key").put("filename", safeName).put("type", mime).put("size", size)
            .put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
        val index = AtomicFile(File(root, "$key.json")); val stream = index.startWrite()
        try { stream.write(data.toString().toByteArray()); index.finishWrite(stream) }
        catch (error: Exception) { index.failWrite(stream); throw error }
        return JSONObject(data.toString()).apply { remove("sha256") }
    }
    fun resolve(resource: String): Pair<String, File> {
        val key = id(resource)
        val data = JSONObject(AtomicFile(File(root, "$key.json")).readFully().toString(Charsets.UTF_8))
        val file = File(root, "$key.bin")
        if (data.optString("name") != resource || !file.isFile || file.length() != data.getLong("size") || file.length() > limit) throw LocalUiFailure("文件暂存核验失败")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        if (hash.digest().joinToString("") { "%02x".format(it) } != data.getString("sha256")) throw LocalUiFailure("文件暂存已损坏，请重新选择")
        return data.getString("type") to file
    }
}
