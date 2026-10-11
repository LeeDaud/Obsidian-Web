package xyz.leedaud.echo.ui

import org.json.JSONObject
import org.json.JSONArray
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.NoteRepository
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream
import com.google.gson.Gson
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.ListItem
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/** Parses the fixed upstream Memos 1.0 container without extracting caller-controlled paths. */
class LocalUiImports(private val repository: NoteRepository, private val afterSaved: () -> Unit = {}) {
    private val gson = Gson()
    private fun snapshot(note: Note?) = note?.let { digest(gson.toJson(it).toByteArray()) }.orEmpty()
    private fun containsTasks(source: String): Boolean {
        var found = false
        Parser.builder().build().parse(source).accept(object : AbstractVisitor() {
            override fun visit(item: ListItem) {
                val text = (item.firstChild as? Paragraph)?.firstChild as? Text
                if (text?.literal?.let { Regex("^\\[[ xX]](?:\\s|$)").containsMatchIn(it) } == true) found = true
                super.visit(item)
            }
        })
        return found
    }
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
            val attachmentIds = mutableSetOf<String>()
            for (i in 0 until attachments.length()) {
                val attachment = attachments.getJSONObject(i)
                val uid = attachment.optString("uid")
                if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(uid) || !attachmentIds.add(uid)) throw LocalUiFailure("导入附件标识无效或重复")
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
    fun commit(archive: Archive, policy: String, destinations: Map<String, JSONArray> = emptyMap(),
               session: JSONObject = JSONObject(), checkpoint: (JSONObject) -> Unit = {}): JSONObject {
        if (policy !in setOf("SKIP", "REPLACE", "DUPLICATE")) throw LocalUiFailure("导入冲突策略无效")
        val archiveHash = digest(JSONArray(archive.entries.keys.sorted().map { path ->
            JSONObject().put("path", path).put("sha256", digest(archive.entries.getValue(path)))
        }).toString().toByteArray())
        if (!session.has("version")) {
            if (session.length() != 0) throw LocalUiFailure("导入恢复记录无效")
            val entries = JSONObject()
            archive.memos.forEach { memo ->
                val uid = memo.getString("uid")
                var target = if (policy == "DUPLICATE") newId() else uid
                while (policy == "DUPLICATE" && repository.find("note", target, Note::class.java) != null) target = newId()
                entries.put(uid, JSONObject().put("target", target).put("beforeHash", snapshot(repository.find("note", target, Note::class.java))).put("phase", "pending"))
            }
            session.put("version", 1).put("archiveHash", archiveHash).put("policy", policy).put("entries", entries)
        }
        if (session.optInt("version") != 1 || session.optString("archiveHash") != archiveHash || session.optString("policy") != policy) throw LocalUiFailure("导入档案或策略已变化，请重新暂存")
        val entries = session.getJSONObject("entries")
        if (entries.keys().asSequence().toSet() != archive.memos.map { it.getString("uid") }.toSet()) throw LocalUiFailure("导入恢复清单不匹配")
        val ids = archive.memos.associate { memo ->
            val uid = memo.getString("uid"); val target = entries.getJSONObject(uid).getString("target")
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(target)) throw LocalUiFailure("导入目标标识无效")
            if (policy != "DUPLICATE" && target != uid) throw LocalUiFailure("导入恢复目标不匹配")
            uid to target
        }
        if (ids.values.toSet().size != ids.size) throw LocalUiFailure("导入恢复目标重复")
        val parents = archive.memos.associate { memo -> memo.getString("uid") to
            (memo.optJSONObject("echoAndroid")?.optString("parent") ?: memo.optString("parent")).takeIf { it.isNotBlank() } }
        fun cyclicParent(uid: String): Boolean {
            val visited = mutableSetOf<String>(); var next: String? = uid
            while (next != null && parents.containsKey(next)) {
                if (!visited.add(next)) return true
                next = parents[next]
            }
            return false
        }
        checkpoint(session)
        var created = 0; var updated = 0; var skipped = 0; val failures = JSONArray()
        archive.memos.forEach { memo ->
            val uid = memo.getString("uid"); val row = entries.getJSONObject(uid)
            if (row.optString("phase") == "done") {
                when (row.getString("result")) { "created" -> created++; "updated" -> updated++; "skipped" -> skipped++; else -> throw LocalUiFailure("导入恢复结果无效") }
                return@forEach
            }
            val id = ids.getValue(uid); val previous = repository.find("note", id, Note::class.java)
            try {
                if (previous != null && policy == "SKIP") {
                    row.put("phase", "done").put("result", "skipped"); checkpoint(session); skipped++; return@forEach
                }
                if (snapshot(previous) != row.getString("beforeHash")) throw LocalUiFailure("目标已变化或先前写入未确认，保留现有记录")
                if (previous?.frozen == true) throw LocalUiFailure("原版本已冻结，导入不能覆盖")
                if (previous?.deleted == true) throw LocalUiFailure("目标已移出本机列表，不自动恢复")
                if (cyclicParent(uid)) throw LocalUiFailure("归档父关系包含循环，未导入此条")
                val createdAt = Instant.parse(memo.getString("createTime")).toEpochMilli()
                val updatedAt = memo.optString("updateTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: createdAt
                val native = memo.optJSONObject("echoAndroid")
                if (native != null && native.optInt("version") != 1) throw LocalUiFailure("本机归档扩展版本不支持")
                fun relationTarget(source: String): String {
                    if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(source) || source == uid) throw LocalUiFailure("归档关联标识无效")
                    return ids[source] ?: repository.find("note", source, Note::class.java)?.takeIf { !it.deleted }?.id
                        ?: throw LocalUiFailure("关联目标不在档案或本机，请一并导入")
                }
                val parentSource = (native?.optString("parent") ?: memo.optString("parent")).takeIf { it.isNotBlank() }
                val parent = parentSource?.let(::relationTarget)
                val nativeReferences = native?.optJSONArray("referenceIds")
                val relations = memo.optJSONArray("relations") ?: JSONArray()
                val refs = if (nativeReferences != null) (0 until nativeReferences.length()).map { relationTarget(nativeReferences.getString(it)) }.distinct()
                    else (0 until relations.length()).map { i ->
                        val relation = relations.getJSONObject(i)
                        if (relation.optString("type", "REFERENCE") != "REFERENCE") throw LocalUiFailure("归档关系类型不支持")
                        relationTarget(relation.getString("memo"))
                    }.distinct()
                val files = memo.optJSONArray("attachments") ?: JSONArray()
                val attachmentTimes = (0 until files.length()).map { i ->
                    val file = files.getJSONObject(i); val filename = file.getString("filename")
                    if (filename.isBlank() || filename.length > 255 || filename.any { it.code < 32 || it in "/\\" }) throw LocalUiFailure("归档附件文件名无效")
                    file.optString("createTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: createdAt
                }
                row.put("phase", "writing"); checkpoint(session)
                var content = archive.entries.getValue(memo.getString("contentPath")).toString(Charsets.UTF_8)
                val attachments = memo.optJSONArray("attachments") ?: JSONArray()
                val imported = (0 until attachments.length()).map { i ->
                    val attachment = attachments.getJSONObject(i)
                    val ref = repository.importStream(archive.entries.getValue(attachment.getString("path")).inputStream(), attachment.getString("filename"), attachment.getString("type"), attachmentTimes[i])
                        .copy(name = attachment.getString("filename"))
                    if (!destinations.containsKey(memo.getString("uid"))) content = content.replace("/file/attachments/${attachment.getString("uid")}/${android.net.Uri.encode(attachment.getString("filename"))}", "echo-attachment:${ref.id}")
                    ref
                }
                destinations[memo.getString("uid")]?.let { spans ->
                    val mappings = (0 until attachments.length()).associate { i ->
                        val original = attachments.getJSONObject(i)
                        "attachments/${original.getString("uid")}" to (original.getString("filename") to "echo-attachment:${imported[i].id}")
                    }
                    content = ManagedAttachmentDestinations.rewrite(content, spans, mappings, LocalWebView.ORIGIN)
                }
                val generation = (repository.find("draft", id, Draft::class.java)?.generation ?: 0) + 1
                val draft = Draft(id = id, body = content, attachments = imported, created = createdAt, updatedOverride = updatedAt,
                    todo = native?.optBoolean("todo") ?: containsTasks(content), parentId = parent,
                    location = memo.optJSONObject("location")?.toString(), referenceIds = refs, generation = generation, baseRevision = previous?.revision ?: 0)
                val saved = repository.save(draft, authorizeDelivery = false) ?: throw LocalUiFailure("空白笔记未导入")
                afterSaved()
                repository.updateNote(saved.copy(pinned = memo.optBoolean("pinned"), archived = memo.optString("state") == "ARCHIVED"))
                row.put("phase", "done").put("result", if (previous == null) "created" else "updated"); checkpoint(session)
                if (previous == null) created++ else updated++
            } catch (_: Exception) {
                row.put("phase", "uncertain"); checkpoint(session)
                failures.put(JSONObject().put("memo", uid).put("message", "此条导入未确认，请检查本机记录；已有记录不会被自动覆盖"))
            }
        }
        return JSONObject().put("created", created).put("updated", updated).put("skipped", skipped).put("failed", failures.length()).put("failures", failures)
    }
}
