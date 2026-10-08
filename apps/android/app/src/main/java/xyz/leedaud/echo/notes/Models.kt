package xyz.leedaud.echo.notes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
fun timestamp(time: Long): String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(shanghai).format(Instant.ofEpochMilli(time))
fun displayTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(shanghai).format(Instant.ofEpochMilli(time))

data class AttachmentRef(val id: String, val file: String, val name: String, val mime: String, val size: Long,
                         val sha256: String, val created: Long, val extension: String)
data class Draft(val id: String = newId(), val parentId: String? = null, val body: String = "", val todo: Boolean = false,
                 val attachments: List<AttachmentRef> = emptyList(), val location: String? = null,
                 val created: Long = System.currentTimeMillis(), val generation: Long = 0, val baseRevision: Int = 0)
data class Note(val id: String, val created: Long, val updated: Long, val revision: Int, val body: String,
                val todo: Boolean, val attachments: List<AttachmentRef>, val parentId: String?, val location: String?,
                val file: String, val sha256: String, val frozen: Boolean = false, val pinned: Boolean = false,
                val archived: Boolean = false, val deleted: Boolean = false)
data class Target(val id: String, val owner: String, val repo: String, val branch: String, val repositoryId: Long,
                  val enabled: Boolean, val credential: String = id)
data class DeliveryFile(val path: String, val base64: String, val sha256: String, val localFile: String = "")
data class Attempt(val parent: String, val commit: String, val blobs: Map<String, String>)
data class Receipt(val commit: String, val path: String, val verifiedAt: Long, val hashes: Map<String, String>)
data class Delivery(val id: String, val noteId: String, val revision: Int, val target: Target,
                    val note: Note, val path: String = "", val files: List<DeliveryFile> = emptyList(),
                    val attempt: Attempt? = null, val receipt: Receipt? = null, val state: String = "pending",
                    val error: String? = null, val retryAt: Long = 0, val failures: Int = 0,
                    val lease: String? = null, val leaseUntil: Long = 0)

object NoteFormat {
    const val TEXT_LIMIT = 256 * 1024
    const val ATTACHMENT_LIMIT = 10 * 1024 * 1024
    const val TOTAL_ATTACHMENTS = 25 * 1024 * 1024

    fun toTodo(body: String): String = body.lines().joinToString("\n") {
        if (it.isBlank() || Regex("^\\s*- \\[[ xX]] ").containsMatchIn(it)) it else "- [ ] $it"
    }
    fun fromTodo(body: String): String = body.lines().joinToString("\n") { it.replace(Regex("^- \\[[ xX]] "), "") }
    fun validate(body: String, attachments: List<AttachmentRef>) {
        require(body.toByteArray(Charsets.UTF_8).size <= TEXT_LIMIT) { "正文不能超过 256 KiB，草稿仍保留" }
        require(attachments.size <= 100 && attachments.sumOf { it.size } <= TOTAL_ATTACHMENTS) { "附件总量不能超过 25 MiB 或 100 个，草稿仍保留" }
        require(attachments.all { it.size in 0..ATTACHMENT_LIMIT && Regex("[a-z0-9]{1,10}").matches(it.extension) }) { "附件超过 10 MiB 或格式无效" }
    }

    fun markdown(note: Note, path: String, parentPath: String? = null): String {
        require(Regex("00_Inbox/\\d{8}-\\d{6}\\.md").matches(path)) { "笔记路径无效" }
        val stem = path.substringAfter('/').removeSuffix(".md")
        var text = note.body
        val names = attachmentPaths(note.attachments, stem)
        note.attachments.forEach { attachment ->
            val relative = "../${names.getValue(attachment.id)}"
            text = text.replace("echo-attachment:${attachment.id}", relative)
            if (!text.contains(relative)) {
                val label = attachment.name.replace("]", "\\]").replace("\n", " ")
                text += if (attachment.mime.startsWith("image/")) "\n\n![]($relative)" else "\n\n[$label]($relative)"
            }
        }
        if (note.todo) {
            val done = note.body.trimStart().startsWith("- [x] ", true)
            text = "---\ntype: todo\nstatus: ${if (done) "done" else "open"}\ncreated: ${displayTime(note.created)}\nsource: echo-android\n---\n\n$text"
        }
        note.location?.let { text += "\n\n位置：$it" }
        if (parentPath != null) {
            require(Regex("00_Inbox/\\d{8}-\\d{6}\\.md").matches(parentPath)) { "父笔记路径无效" }
            text += "\n\n---\n续写自：[[${parentPath.substringAfter('/').removeSuffix(".md")}]]"
        }
        validate(text, note.attachments)
        return text
    }

    fun attachmentPaths(items: List<AttachmentRef>, stem: String): Map<String, String> {
        val counts = mutableMapOf<String, Int>()
        return items.sortedWith(compareBy<AttachmentRef> { it.created }.thenBy { it.id }).associate { a ->
            val stamp = timestamp(a.created)
            val key = "$stamp.${a.extension}"
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            val suffix = if (count == 1) "" else "-%02d".format(count)
            a.id to "attachments/$stem/$stamp$suffix.${a.extension}"
        }
    }
}
