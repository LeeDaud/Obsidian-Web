package xyz.leedaud.echo.ui

import org.json.JSONObject
import org.json.JSONArray
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.NoteRepository
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/** Parses the fixed upstream Memos 1.0 container without extracting caller-controlled paths. */
class LocalUiImports(private val repository: NoteRepository) {
    data class Archive(val manifest: JSONObject, val memos: List<JSONObject>, val entries: Map<String, ByteArray>)
    fun read(file: File): Archive {
        val entries = mutableMapOf<String, ByteArray>(); var total = 0L
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = entry.name
                if (path.startsWith('/') || path.contains('\\') || path.split('/').any { it == ".." || it == "." } || entries.containsKey(path)) throw LocalUiFailure("导入包含无效或重复路径")
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32768)
                while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; if (total > 50 * 1024 * 1024 || output.size() + n > 10 * 1024 * 1024) throw LocalUiFailure("导入解包超过容量限制"); output.write(buffer, 0, n) }
                entries[path] = output.toByteArray()
                if (entries.size > 10000) throw LocalUiFailure("导入条目过多")
            }
        }
        val manifest = entries["manifest.json"]?.let { JSONObject(it.toString(Charsets.UTF_8)) } ?: throw LocalUiFailure("导入包缺少 manifest.json")
        if (manifest.optString("format") !in setOf("memos-export", "memos-archive") || !manifest.optString("formatVersion").startsWith("1.")) throw LocalUiFailure("导入格式版本不支持")
        val memos = entries.filterKeys { it.startsWith("memos/") && it.endsWith(".json") }.values.map { JSONObject(it.toString(Charsets.UTF_8)) }
        if (memos.size > 1000 || memos.map { it.optString("uid") }.distinct().size != memos.size) throw LocalUiFailure("导入笔记数量或标识无效")
        memos.forEach { memo ->
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(memo.optString("uid"))) throw LocalUiFailure("导入笔记标识无效")
            val content = entries[memo.optString("contentPath")] ?: throw LocalUiFailure("导入缺少正文")
            if (content.size > NoteFormat.TEXT_LIMIT) throw LocalUiFailure("导入单条正文超过 256 KiB")
            val attachments = memo.optJSONArray("attachments") ?: JSONArray(); var bytes = 0L
            if (attachments.length() > 100) throw LocalUiFailure("导入附件条数超限")
            for (i in 0 until attachments.length()) {
                val attachment = attachments.getJSONObject(i)
                if (attachment.optString("externalLink").isNotBlank()) throw LocalUiFailure("外链附件不能作为完整本机导入")
                val data = entries[attachment.optString("path")] ?: throw LocalUiFailure("导入缺少附件")
                if (attachment.optLong("size", -1) != data.size.toLong() || (attachment.optString("sha256").isNotBlank() && attachment.getString("sha256") != digest(data))) throw LocalUiFailure("导入附件大小或摘要不匹配")
                bytes += data.size
            }
            if (bytes > NoteFormat.TOTAL_ATTACHMENTS) throw LocalUiFailure("导入单条附件总量超限")
        }
        return Archive(manifest, memos, entries)
    }
    fun plan(archive: Archive): JSONObject {
        val existing = archive.memos.count { memo -> repository.notes().any { it.id == memo.getString("uid") } }
        return JSONObject().put("exportTime", archive.manifest.optString("exportTime")).put("exporter", archive.manifest.optJSONObject("scope")?.optJSONObject("user")?.optString("username", "device"))
            .put("generator", archive.manifest.optJSONObject("generator")?.optString("name", "Memos")).put("memos", archive.memos.size)
            .put("attachments", archive.memos.sumOf { it.optJSONArray("attachments")?.length() ?: 0 }).put("new", archive.memos.size - existing).put("existing", existing).put("renamed", 0)
    }
    fun commit(archive: Archive, policy: String): JSONObject {
        if (policy !in setOf("SKIP", "REPLACE", "DUPLICATE")) throw LocalUiFailure("导入冲突策略无效")
        val ids = archive.memos.associate { memo -> memo.getString("uid") to if (policy == "DUPLICATE") newId() else memo.getString("uid") }
        var created = 0; var updated = 0; var skipped = 0; val failures = JSONArray()
        archive.memos.forEach { memo ->
            val id = ids.getValue(memo.getString("uid")); val previous = repository.notes().find { it.id == id }
            if (previous != null && policy == "SKIP") { skipped++; return@forEach }
            try {
                if (previous?.frozen == true) throw LocalUiFailure("原版本已冻结，导入不能覆盖")
                var content = archive.entries.getValue(memo.getString("contentPath")).toString(Charsets.UTF_8)
                val attachments = memo.optJSONArray("attachments") ?: JSONArray()
                val imported = (0 until attachments.length()).map { i ->
                    val attachment = attachments.getJSONObject(i)
                    val ref = repository.importStream(archive.entries.getValue(attachment.getString("path")).inputStream(), attachment.getString("filename"), attachment.getString("type"))
                    content = content.replace("/file/attachments/${attachment.getString("uid")}/${android.net.Uri.encode(attachment.getString("filename"))}", "echo-attachment:${ref.id}")
                    ref
                }
                val relations = memo.optJSONArray("relations") ?: JSONArray()
                val refs = (0 until relations.length()).mapNotNull { i -> ids[relations.getJSONObject(i).optString("memo")] }.filterNot { it == id }
                val generation = (repository.find("draft", id, Draft::class.java)?.generation ?: 0) + 1
                val draft = Draft(id = id, body = content, attachments = imported, created = Instant.parse(memo.getString("createTime")).toEpochMilli(),
                    location = memo.optJSONObject("location")?.toString(), referenceIds = refs, generation = generation, baseRevision = previous?.revision ?: 0)
                val saved = repository.save(draft, authorizeDelivery = false) ?: throw LocalUiFailure("空白笔记未导入")
                repository.updateNote(saved.copy(pinned = memo.optBoolean("pinned"), archived = memo.optString("state") == "ARCHIVED"))
                if (previous == null) created++ else updated++
            } catch (_: Exception) { failures.put(JSONObject().put("memo", memo.getString("uid")).put("message", "此条导入未完成，原版本保留")) }
        }
        return JSONObject().put("created", created).put("updated", updated).put("skipped", skipped).put("failed", failures.length()).put("failures", failures)
    }
}
