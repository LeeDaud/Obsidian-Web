package xyz.leedaud.echo.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.NoteRepository
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

class LocalUiExports(private val context: Context, private val repository: NoteRepository) {
    private val directory = File(context.cacheDir, "share/local-ui").apply { mkdirs() }
    private fun file(id: String): File {
        if (!Regex("[a-f0-9-]{36}").matches(id)) throw LocalUiFailure("导出标识无效")
        return File(directory, "$id.zip")
    }
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun resolve(resource: String): Pair<String, File> {
        val id = resource.removePrefix("exports/"); val output = file(id); val metadata = File(directory, "$id.json")
        if (!resource.startsWith("exports/") || !output.isFile || !metadata.isFile || JSONObject(metadata.readText()).optString("sha256") != sha(output)) throw LocalUiFailure("导出文件核验失败")
        return "application/zip" to output
    }
    fun create(notes: List<Note>, archive: Boolean): JSONObject {
        val id = newId(); val output = file(id)
        val reservedNames = notes.map { timestamp(it.created) }.toMutableSet()
        val allocatedNames = mutableSetOf<String>()
        fun markdownPath(note: Note): String {
            var time = note.created
            var name = timestamp(time)
            if (name in allocatedNames) {
                do { time += 1000; name = timestamp(time) } while (name in reservedNames || name in allocatedNames)
            }
            allocatedNames.add(name)
            return "00_Inbox/$name.md"
        }
        ZipOutputStream(output.outputStream()).use { zip ->
            val seen = mutableSetOf<String>()
            fun put(path: String, bytes: ByteArray) { if (!seen.add(path)) return; zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            if (archive) put("manifest.json", JSONObject().put("format", "memos-export").put("formatVersion", "1.0")
                .put("generator", JSONObject().put("name", "Echo Android").put("version", "0.4"))
                .put("exportTime", Instant.now().toString()).put("scope", JSONObject().put("kind", "USER").put("user", JSONObject().put("username", "device")))
                .put("counts", JSONObject().put("memos", notes.size).put("attachments", notes.flatMap { it.attachments }.distinctBy { it.id }.size)).toString().toByteArray())
            notes.forEach { note ->
                val attachments = JSONArray()
                note.attachments.forEach { ref ->
                    repository.verifyAttachment(ref)
                    val filename = ref.name.replace(Regex("[/\\\\\r\n]"), "_")
                    val path = "attachments/${ref.id}/$filename"
                    if (archive) put(path, repository.file(ref.file).readBytes())
                    attachments.put(JSONObject().put("uid", ref.id).put("filename", ref.name).put("type", ref.mime).put("size", ref.size).put("sha256", ref.sha256)
                        .put("path", path).put("createTime", Instant.ofEpochMilli(ref.created).toString()))
                }
                if (archive) {
                    var content = note.body
                    note.attachments.forEach { ref -> content = content.replace("echo-attachment:${ref.id}", "/file/attachments/${ref.id}/${android.net.Uri.encode(ref.name)}") }
                    put("memos/${note.id}.md", content.toByteArray())
                    val relations = JSONArray((listOfNotNull(note.parentId) + note.referenceIds.orEmpty()).distinct().map { JSONObject().put("type", "REFERENCE").put("memo", it) })
                    put("memos/${note.id}.json", JSONObject().put("uid", note.id).put("creator", "device").put("createTime", Instant.ofEpochMilli(note.created).toString())
                        .put("updateTime", Instant.ofEpochMilli(note.updated).toString()).put("state", if (note.archived) "ARCHIVED" else "NORMAL").put("visibility", "PRIVATE")
                        .put("pinned", note.pinned).put("contentPath", "memos/${note.id}.md").put("relations", relations).put("attachments", attachments)
                        .apply { note.location?.let { runCatching { put("location", JSONObject(it)) } } }.toString().toByteArray())
                } else repository.bundle(note, markdownPath(note), note.parentId?.let { parent -> repository.deliveries().find { it.noteId == parent && it.state == "verified" }?.receipt?.path })
                    .forEach { put(it.path, java.util.Base64.getDecoder().decode(it.base64)) }
            }
        }
        File(directory, "$id.json").writeText(JSONObject().put("sha256", sha(output)).toString())
        return JSONObject().put("name", "exports/$id").put("filename", if (archive) "memos-export.zip" else "memos-markdown.zip")
    }
}
