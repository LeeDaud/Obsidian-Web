package xyz.leedaud.echo.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import com.google.gson.Gson
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64

/** Room indexes durable private Markdown snapshots. All filesystem changes have a recovery journal. */
class NoteRepository(val root: File, val database: EchoDatabase) {
    private val gson = Gson()
    private val dao = database.records()
    private data class Journal(val id: String, val destination: String, val text: String, val note: Note,
                               val delivery: Delivery?, val draftGeneration: Long, val complete: Boolean = false)
    private data class Selection(val id: String)

    companion object {
        @Volatile private var instance: NoteRepository? = null
        fun get(context: Context): NoteRepository = instance ?: synchronized(this) {
            instance ?: NoteRepository(context.applicationContext.filesDir,
                Room.databaseBuilder(context.applicationContext, EchoDatabase::class.java, "echo-native.db").build())
                .also { it.recover(); instance = it }
        }
    }
    fun <T> find(kind: String, id: String, type: Class<T>): T? = dao.find("$kind:$id")?.let { gson.fromJson(it.payload, type) }
    fun <T> list(kind: String, type: Class<T>): List<T> = dao.list(kind).map { gson.fromJson(it.payload, type) }
    private fun put(kind: String, id: String, value: Any, version: Long = System.currentTimeMillis()) =
        dao.put(StoredRecord("$kind:$id", kind, gson.toJson(value), version))
    fun notes(): List<Note> = list("note", Note::class.java).filter { !it.deleted }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.created })
    fun drafts(): List<Draft> = list("draft", Draft::class.java).filter { it.body.isNotBlank() || it.attachments.isNotEmpty() }
    fun deliveries(): List<Delivery> = list("delivery", Delivery::class.java)
    fun target(): Target? = find("target", "current", Target::class.java)
    @Synchronized fun setTarget(target: Target) { put("target", "current", target) }
    fun file(relative: String): File {
        require(!relative.startsWith('/') && !relative.contains('\\')) { "本地路径无效" }
        val resolved = File(root, relative).canonicalFile
        require(resolved.path.startsWith(root.canonicalPath + File.separator)) { "本地路径越界" }
        return resolved
    }

    @Synchronized fun activeDraft(): Draft {
        val id = find("selection", "active", Selection::class.java)?.id
        return id?.let { find("draft", it, Draft::class.java) } ?: Draft().also { select(it) }
    }
    @Synchronized fun select(draft: Draft): Draft {
        saveDraft(draft)
        put("selection", "active", Selection(draft.id))
        return find("draft", draft.id, Draft::class.java) ?: draft
    }
    @Synchronized fun edit(note: Note): Draft {
        val current = find("note", note.id, Note::class.java) ?: error("笔记不存在")
        if (current.frozen) return select(Draft(parentId = current.id))
        val cached = find("draft", current.id, Draft::class.java)
        // The completed save journal identifies the empty reset, not a user's later edit.
        val savedPlaceholder = cached != null && list("journal", Journal::class.java).any {
            it.complete && it.note.id == current.id && it.note.revision == current.revision && it.draftGeneration == cached.generation
        }
        return select(cached?.takeIf { it.baseRevision == current.revision && !savedPlaceholder } ?: Draft(current.id, current.parentId,
            current.body, current.todo, current.attachments, current.location, current.created,
            (cached?.generation ?: 0) + 1, current.revision))
    }
    @Synchronized fun saveDraft(draft: Draft): Boolean {
        var accepted = false
        database.runInTransaction {
            val record = dao.find("draft:${draft.id}")
            if (record == null || record.version < draft.generation) {
                put("draft", draft.id, draft, draft.generation)
                accepted = true
            }
        }
        return accepted
    }

    @Synchronized fun save(draft: Draft): Note? {
        saveDraft(draft)
        if (draft.body.isBlank() && draft.attachments.isEmpty()) return null
        NoteFormat.validate(draft.body, draft.attachments)
        draft.attachments.forEach { verifyAttachment(it) }
        val previous = find("note", draft.id, Note::class.java)
        require(previous?.frozen != true) { "原笔记已入队，请创建关联续写" }
        require(previous == null || previous.revision == draft.baseRevision) { "原版本已变化，草稿保留，请重新打开" }
        if (previous != null && previous.body == draft.body && previous.todo == draft.todo
            && previous.attachments == draft.attachments && previous.location == draft.location) return previous
        val revision = (previous?.revision ?: 0) + 1
        val configured = target()?.takeIf { it.enabled }
        if (configured != null && draft.parentId != null) {
            val parent = deliveries().lastOrNull { it.noteId == draft.parentId }
            require(parent != null && parent.target.id == configured.id) { "父笔记尚未投递到当前目标，请先投递父笔记" }
        }
        val provisional = Note(draft.id, previous?.created ?: draft.created, System.currentTimeMillis(), revision,
            draft.body, draft.todo, draft.attachments, draft.parentId, draft.location,
            "notes/${draft.id}/$revision.md", "", configured != null, previous?.pinned ?: false, previous?.archived ?: false)
        val markdown = NoteFormat.markdown(provisional, "00_Inbox/${timestamp(provisional.created)}.md")
        val note = provisional.copy(sha256 = digest(markdown.toByteArray(Charsets.UTF_8)))
        val delivery = configured?.let { Delivery("${note.id}-${note.revision}", note.id, note.revision, it, note) }
        val journal = Journal(newId(), note.file, markdown, note, delivery, draft.generation + 1)
        put("journal", journal.id, journal)
        commitJournal(journal)
        return note
    }

    private fun atomicWrite(relative: String, bytes: ByteArray) {
        val destination = file(relative)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "无法创建本地目录" }
        val temporary = file("$relative.part")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(destination)) { "本地文件落盘失败，草稿保留" }
    }
    private fun commitJournal(journal: Journal) {
        val existing = file(journal.destination)
        val bytes = journal.text.toByteArray(Charsets.UTF_8)
        if (existing.exists()) require(digest(existing.readBytes()) == journal.note.sha256) { "本地版本文件冲突，保留日志" }
        else atomicWrite(journal.destination, bytes)
        require(digest(file(journal.destination).readBytes()) == journal.note.sha256) { "本地保存核验失败" }
        database.runInTransaction {
            put("revision", "${journal.note.id}-${journal.note.revision}", journal.note)
            put("note", journal.note.id, journal.note)
            journal.delivery?.let { put("delivery", it.id, it) }
            put("draft", journal.note.id, Draft(id = journal.note.id, created = journal.note.created,
                generation = journal.draftGeneration, baseRevision = journal.note.revision), journal.draftGeneration)
            put("journal", journal.id, journal.copy(complete = true))
        }
    }
    @Synchronized fun recover() {
        list("journal", Journal::class.java).filter { !it.complete }.forEach { commitJournal(it) }
    }

    @Synchronized fun enqueue(noteId: String): Delivery {
        val note = find("note", noteId, Note::class.java) ?: error("笔记不存在")
        require(!note.deleted) { "笔记已移入回收记录" }
        val id = "${note.id}-${note.revision}"
        find("delivery", id, Delivery::class.java)?.let { return it }
        require(!note.frozen) { "原版本已冻结但队列缺失，不能重新发布；请保留记录并核查" }
        val target = target()?.takeIf { it.enabled } ?: error("请配置仓库并开启投递")
        note.parentId?.let { parentId ->
            require(deliveries().any { it.noteId == parentId && it.target.id == target.id }) { "请先向当前仓库投递父笔记" }
        }
        require(digest(file(note.file).readBytes()) == note.sha256) { "本地 Markdown 核验失败" }
        note.attachments.forEach { verifyAttachment(it) }
        val frozen = note.copy(frozen = true)
        val job = Delivery(id, note.id, note.revision, target, frozen)
        database.runInTransaction { put("note", note.id, frozen); put("delivery", id, job) }
        return job
    }
    @Synchronized fun updateNote(note: Note) {
        val current = find("note", note.id, Note::class.java) ?: error("笔记不存在")
        if (note.deleted) require(deliveries().none { it.noteId == note.id && it.state != "verified" }) { "投递尚未确认，请先保留笔记" }
        put("note", note.id, current.copy(pinned = note.pinned, archived = note.archived, deleted = note.deleted))
    }

    fun verifyAttachment(attachment: AttachmentRef) {
        val data = file(attachment.file)
        require(data.isFile && data.length() == attachment.size && digest(data.readBytes()) == attachment.sha256) { "附件未完整保存，保留草稿" }
    }
    @Synchronized fun importStream(input: InputStream, name: String, mime: String, created: Long = System.currentTimeMillis()): AttachmentRef {
        val id = newId()
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { Regex("[a-z0-9]{1,10}").matches(it) }
            ?: when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "audio/mp4" -> "m4a"; else -> "bin" }
        val relative = "attachments/$id.$extension"
        val temporary = file("$relative.part")
        temporary.parentFile!!.mkdirs()
        var total = 0L
        input.use { source -> FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                require(total <= NoteFormat.ATTACHMENT_LIMIT) { "附件不能超过 10 MiB，原文件未修改" }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
        check(temporary.renameTo(file(relative))) { "附件保存失败" }
        return AttachmentRef(id, relative, name, mime, total, digest(file(relative).readBytes()), created, extension)
    }
    fun importUri(context: Context, uri: Uri): AttachmentRef {
        require(uri.scheme == "content") { "只接受系统选择的附件" }
        var name = "attachment.bin"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return importStream(context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件"), name,
            context.contentResolver.getType(uri) ?: "application/octet-stream")
    }
    fun bundle(note: Note, path: String, parentPath: String?): List<DeliveryFile> {
        require(digest(file(note.file).readBytes()) == note.sha256 &&
            digest(NoteFormat.markdown(note, "00_Inbox/${timestamp(note.created)}.md").toByteArray(Charsets.UTF_8)) == note.sha256) { "正式 Markdown 与本地索引不一致，停止投递" }
        val text = NoteFormat.markdown(note, path, parentPath).toByteArray(Charsets.UTF_8)
        val names = NoteFormat.attachmentPaths(note.attachments, path.substringAfter('/').removeSuffix(".md"))
        return listOf(DeliveryFile(path, Base64.getEncoder().encodeToString(text), digest(text))) + note.attachments.map {
            verifyAttachment(it)
            val bytes = file(it.file).readBytes()
            DeliveryFile(names.getValue(it.id), Base64.getEncoder().encodeToString(bytes), digest(bytes))
        }
    }

    fun persistBundle(id: String, files: List<DeliveryFile>): List<DeliveryFile> = files.map { entry ->
        require(Regex("[a-zA-Z0-9-]{1,120}").matches(id))
        val relative = "outbox/$id/${entry.path}"
        val bytes = Base64.getDecoder().decode(entry.base64)
        require(digest(bytes) == entry.sha256)
        if (file(relative).exists()) require(digest(file(relative).readBytes()) == entry.sha256) { "投递文件检查点冲突" }
        else atomicWrite(relative, bytes)
        entry.copy(base64 = "", localFile = relative)
    }
    fun hydrate(files: List<DeliveryFile>): List<DeliveryFile> = files.map { entry ->
        val bytes = file(entry.localFile).readBytes()
        require(digest(bytes) == entry.sha256) { "投递快照字节核验失败" }
        entry.copy(base64 = Base64.getEncoder().encodeToString(bytes))
    }

    @Synchronized fun claim(id: String, now: Long = System.currentTimeMillis()): Delivery? {
        var claimed: Delivery? = null
        database.runInTransaction {
            val job = find("delivery", id, Delivery::class.java) ?: return@runInTransaction
            if (job.state in setOf("verified", "conflict", "auth_required") || job.retryAt > now || job.leaseUntil > now) return@runInTransaction
            val current = target()
            if (current?.id != job.target.id || !current.enabled) { put("delivery", id, job.copy(state = "paused")); return@runInTransaction }
            if (deliveries().any { it.id != id && it.target.id == job.target.id && it.leaseUntil > now }) return@runInTransaction
            val next = job.copy(state = "running", lease = newId(), leaseUntil = now + 10 * 60 * 1000)
            put("delivery", id, next)
            claimed = next
        }
        return claimed
    }
    @Synchronized fun checkpoint(job: Delivery): Boolean {
        var accepted = false
        database.runInTransaction {
            val current = find("delivery", job.id, Delivery::class.java)
            if (current != null && current.lease == job.lease && current.leaseUntil > System.currentTimeMillis()) {
                put("delivery", job.id, job); accepted = true
            }
        }
        return accepted
    }
    @Synchronized fun finish(job: Delivery) {
        require(job.state != "verified" || (job.receipt != null && job.attempt != null)) { "已投递状态需要核验凭证" }
        check(checkpoint(job.copy(leaseUntil = System.currentTimeMillis() + 1000))) { "执行租约已变化，等待结果核查" }
        val current = find("delivery", job.id, Delivery::class.java)!!
        put("delivery", job.id, current.copy(lease = null, leaseUntil = 0))
    }
    @Synchronized fun retry(id: String) {
        val job = find("delivery", id, Delivery::class.java) ?: return
        require(job.state != "verified" && job.leaseUntil <= System.currentTimeMillis()) { "当前任务仍在处理或已投递" }
        require(job.state != "conflict") { "目标路径冲突，请保留原记录并续写，不覆盖远端" }
        put("delivery", id, job.copy(state = "pending", error = null, retryAt = 0))
    }
}
