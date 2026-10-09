package xyz.leedaud.echo.ui

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.NoteRepository
import xyz.leedaud.echo.delivery.DeliveryWorker
import java.io.File
import java.time.Instant
import com.google.gson.Gson
import java.io.RandomAccessFile
import java.util.Base64

class LocalUiFailure(message: String) : Exception(message)

/** Called only by the trusted local main-frame port, on a serial background executor. */
class LocalUiBackend(private val context: Context, private val repository: NoteRepository,
    private val schedule: () -> Unit = { DeliveryWorker.schedule(context, repository) },
    private val root: File = File(context.filesDir, "local-ui-state"),
    private val remote: LocalMemosSource? = null) {
    private val gson = Gson()
    private val exports = LocalUiExports(context, repository)
    private val imports = LocalUiImports(repository)
    private fun collection(kind: String) = json(File(root, "$kind.json"))
    private fun collectionWrite(kind: String, data: JSONObject) = write(File(root, "$kind.json"), data)
    private fun space(name: String): JSONObject {
        if (!Regex("spaces/[a-f0-9-]{36}").matches(name)) throw LocalUiFailure("分组标识无效")
        return collection("spaces").optJSONObject(name) ?: throw LocalUiFailure("本机分组不存在")
    }
    private fun json(file: File): JSONObject = if (file.isFile) JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) else JSONObject()
    private fun write(file: File, data: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun user() = JSONObject().put("name", "users/device").put("username", "device").put("displayName", remote?.account()?.optString("username") ?: "本机")
        .put("role", "USER").put("state", "NORMAL")
    private fun time(value: Long) = Instant.ofEpochMilli(value).toString()
    private fun tags(body: String) = Regex("(?<!\\w)#([\\p{L}\\p{N}_/-]+)").findAll(body).map { it.groupValues[1] }.distinct().toList()
    private fun name(note: Note) = "memos/${note.id}"
    private fun note(resource: String): Note {
        val id = resource.removePrefix("memos/")
        if (!resource.startsWith("memos/") || !Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(id)) throw LocalUiFailure("笔记标识无效")
        if (id.startsWith("r-")) throw LocalUiFailure("原 Memos 记录只读，请创建本机续写")
        return repository.notes().find { it.id == id } ?: throw LocalUiFailure("本机笔记不存在")
    }
    private fun attachment(ref: AttachmentRef, memo: String? = null) = JSONObject().put("name", "attachments/${ref.id}")
        .put("filename", ref.name).put("type", ref.mime).put("size", ref.size.toString()).put("createTime", time(ref.created))
        .apply {
            if (memo != null) put("memo", memo)
            val extras = json(File(root, "attachments/${ref.id}.json")).optJSONObject("extra")
            for (key in listOf("motionMedia", "mediaMetadata")) extras?.opt(key)?.let { put(key, it) }
        }
    private fun upload(input: JSONObject): JSONObject {
        val spec = input.optJSONObject("spec")
        if (spec != null) {
            val data = spec.getJSONObject("attachment")
            val size = spec.optString("totalSize", "0").toLongOrNull() ?: throw LocalUiFailure("附件大小无效")
            val filename = data.optString("filename")
            val mime = data.optString("type").ifBlank { "application/octet-stream" }
            val limit = if (spec.optString("purpose") == "import") 25 * 1024 * 1024 else NoteFormat.ATTACHMENT_LIMIT
            if (size !in 0..limit || filename.isBlank() || filename.length > 255 || filename.any { it.code < 32 || it in "/\\" }
                || !Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(mime) || data.optString("externalLink").isNotBlank()) throw LocalUiFailure("附件名称、来源或大小无效，单文件最多 10 MiB")
            val directory = File(root, "uploads").apply { mkdirs() }
            if (directory.listFiles().orEmpty().count { it.extension == "json" && !json(it).optBoolean("complete") } >= 8) throw LocalUiFailure("待完成附件过多，请完成当前附件后再试")
            val id = newId()
            write(File(directory, "$id.json"), JSONObject().put("filename", filename).put("mime", mime).put("size", size).put("extra", data).put("offset", 0).put("purpose", spec.optString("purpose", "attachment")))
            return JSONObject().put("uploadId", id).put("committedSize", "0").put("maxChunkSize", 262144)
        }
        val id = input.optString("uploadId")
        if (!Regex("[a-f0-9-]{36}").matches(id)) throw LocalUiFailure("附件上传标识无效")
        val journal = File(root, "uploads/$id.json")
        if (!journal.isFile) throw LocalUiFailure("附件暂存不存在")
        val state = json(journal)
        val offset = input.optString("writeOffset", "0").toLongOrNull() ?: throw LocalUiFailure("附件偏移无效")
        val encoded = input.optString("data")
        if (encoded.length > 350000) throw LocalUiFailure("附件分块过大")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { throw LocalUiFailure("附件分块无效") }
        if (bytes.size > 262144 || offset < 0 || offset + bytes.size > state.getLong("size")) throw LocalUiFailure("附件偏移或大小越界")
        val file = File(root, "uploads/$id.bin")
        if (offset < state.getLong("offset")) {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(offset); val old = ByteArray(bytes.size); reader.readFully(old)
                if (!old.contentEquals(bytes)) throw LocalUiFailure("重试分块内容不同")
            }
        } else {
            if (offset != state.getLong("offset")) throw LocalUiFailure("附件分块顺序不正确")
            RandomAccessFile(file, "rw").use { writer -> writer.seek(offset); writer.write(bytes); writer.setLength(offset + bytes.size); writer.fd.sync() }
            state.put("offset", offset + bytes.size)
            write(journal, state)
        }
        val reply = JSONObject().put("uploadId", id).put("committedSize", state.getLong("offset").toString()).put("maxChunkSize", 262144)
        if (input.optBoolean("finishWrite")) {
            if (state.getLong("offset") != state.getLong("size")) throw LocalUiFailure("附件未完整暂存")
            if (state.optString("purpose") == "import") return reply
            val ref = state.optString("reference").takeIf { it.isNotBlank() }?.let { gson.fromJson(it, AttachmentRef::class.java) }
                ?: file.inputStream().use { repository.importStream(it, state.getString("filename"), state.getString("mime")) }
            repository.verifyAttachment(ref)
            write(File(root, "attachments/${ref.id}.json"), JSONObject().put("reference", gson.toJson(ref)).put("extra", state.getJSONObject("extra")).put("hidden", state.optString("purpose") == "export"))
            write(journal, state.put("complete", true).put("reference", gson.toJson(ref)))
            reply.put("attachment", attachment(ref))
        }
        return reply
    }
    private fun attachmentRef(resource: String): AttachmentRef {
        if (!Regex("attachments/[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(resource)) throw LocalUiFailure("附件标识无效")
        val id = resource.substringAfter('/')
        val existing = (repository.notes().flatMap { it.attachments } + repository.drafts().flatMap { it.attachments }).find { it.id == id }
        if (existing != null) return existing
        val file = File(root, "attachments/$id.json")
        if (!file.isFile) throw LocalUiFailure("本机附件不存在")
        return gson.fromJson(json(file).getString("reference"), AttachmentRef::class.java)
    }
    fun attachmentFile(resource: String): Pair<String, File> {
        if (resource.startsWith("exports/")) return exports.resolve(resource)
        if (resource.startsWith("attachments/r-")) return remote?.attachmentFile(resource) ?: throw LocalUiFailure("请连接原 Memos 账号")
        val ref = attachmentRef(resource); repository.verifyAttachment(ref)
        return ref.mime to repository.file(ref.file)
    }
    private fun memo(value: Note, related: List<Note> = repository.notes()): JSONObject {
        val relations = JSONArray()
        (listOfNotNull(value.parentId) + value.referenceIds.orEmpty()).distinct().forEach { parent -> related.find { it.id == parent }?.let { original ->
            relations.put(JSONObject().put("type", "REFERENCE").put("memo", JSONObject().put("name", name(value)))
                .put("relatedMemo", JSONObject().put("name", name(original)).put("snippet", original.body.take(200))))
        } }
        related.filter { it.parentId == value.id || value.id in it.referenceIds.orEmpty() }.forEach { child ->
            relations.put(JSONObject().put("type", "REFERENCE").put("memo", JSONObject().put("name", name(child)).put("snippet", child.body.take(200)))
                .put("relatedMemo", JSONObject().put("name", name(value)).put("snippet", value.body.take(200))))
        }
        val body = value.attachments.fold(value.body) { text, ref -> text.replace("echo-attachment:${ref.id}", "/file/attachments/${ref.id}/${android.net.Uri.encode(ref.name)}") }
        return JSONObject().put("name", name(value)).put("creator", "users/device").put("content", body).put("snippet", body.take(200))
            .put("createTime", time(value.created)).put("updateTime", time(value.updated)).put("state", if (value.archived) "ARCHIVED" else "NORMAL")
            .put("visibility", "PRIVATE").put("pinned", value.pinned).put("tags", JSONArray(tags(body)))
            .put("attachments", JSONArray(value.attachments.map { attachment(it, name(value)) })).put("relations", relations)
            .put("property", JSONObject().put("hasTaskList", Regex("(?m)^\\s*[-*+] \\[[ xX]] ").containsMatchIn(body))
                .put("hasIncompleteTasks", Regex("(?m)^\\s*[-*+] \\[ ] ").containsMatchIn(body)).put("hasLink", body.contains("https://") || body.contains("http://"))
                .put("hasCode", body.contains('`')))
            .put("localRevision", value.revision).put("localMetadata", JSONObject().put("frozen", value.frozen).put("remote", false))
            .apply { value.spaceName?.let { put("space", it) } }
            .apply { value.location?.let { location -> runCatching { put("location", JSONObject(location)) } } }
    }
    private fun draftKey(key: String): File {
        if (!key.startsWith("users/device-") || key.length > 300) throw LocalUiFailure("草稿会话标识无效")
        return File(root, "drafts/${digest(key.toByteArray(Charsets.UTF_8))}.json")
    }
    private fun storeDraft(input: JSONObject): JSONObject {
        val key = input.getString("key"); val raw = input.getString("value"); val data = JSONObject(raw)
        if (data.optString("kind") != "memos.editor-cache" || data.optInt("version") != 4) throw LocalUiFailure("草稿格式无效")
        val refs = data.optJSONArray("attachments") ?: JSONArray()
        val attachments = (0 until refs.length()).map { attachmentRef(refs.getJSONObject(it).getString("name")) }
        val content = data.optString("content")
        NoteFormat.validate(content, attachments)
        val file = draftKey(key)
        val entry = json(file)
        val token = data.optString("changeToken")
        if (!entry.optBoolean("active", true) && (entry.optString("lastConsumedValue") == raw
            || (token.isNotBlank() && token == entry.optString("lastSavedToken")))) {
            return JSONObject().put("saved", true).put("generation", entry.optLong("generation"))
        }
        val id = entry.optString("id").ifBlank { newId() }
        val previous = repository.find("draft", id, Draft::class.java)
        val next = Draft(id = id, body = content, attachments = attachments,
            generation = (previous?.generation ?: 0) + 1, created = previous?.created ?: System.currentTimeMillis())
        repository.saveDraft(next)
        write(file, entry.put("id", id).put("key", key).put("value", raw).put("active", content.isNotBlank() || attachments.isNotEmpty()).put("generation", next.generation))
        return JSONObject().put("saved", true).put("generation", next.generation)
    }
    private fun bootstrap(): JSONObject {
        val drafts = JSONObject()
        val consumed = JSONObject()
        File(root, "drafts").listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach {
            val entry = json(it); val key = entry.getString("key"); draftKey(key)
            if (entry.optBoolean("active", true)) {
                val saved = repository.notes().find { note -> note.id == entry.optString("id") }
                val checkpoint = entry.optString("saveCheckpoint").takeIf { value -> value.isNotBlank() }
                    ?.let { value -> runCatching { gson.fromJson(value, Draft::class.java) }.getOrNull() }
                if (saved != null && checkpoint != null && checkpoint.body == saved.body && checkpoint.attachments == saved.attachments
                    && checkpoint.parentId == saved.parentId && checkpoint.location == saved.location
                    && checkpoint.referenceIds.orEmpty() == saved.referenceIds.orEmpty() && checkpoint.spaceName == saved.spaceName
                    && checkpoint.created == saved.created && (checkpoint.updatedOverride == null || checkpoint.updatedOverride == saved.updated)) {
                    val value = entry.optString("pendingConsumedValue").ifBlank { entry.optString("value") }
                    consumed.put(key, value)
                    write(it, entry.put("active", false).put("id", newId()).put("lastSavedName", name(saved)).put("lastSavedContent", saved.body)
                        .put("lastConsumedValue", value).put("lastSavedToken", entry.optString("pendingSaveToken")))
                } else drafts.put(key, entry.getString("value"))
            } else entry.optString("lastConsumedValue").takeIf { it.isNotBlank() }?.let { consumed.put(key, it) }
        }
        if (!drafts.has("users/device-home-memo-editor")) {
            val active = repository.activeDraft()
            drafts.put("users/device-home-memo-editor", JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", active.body)
                .put("attachments", JSONArray(active.attachments.map { attachment(it) })).put("location", JSONObject.NULL).toString())
        }
        return JSONObject().put("drafts", drafts).put("consumed", consumed).put("account", remote?.account() ?: JSONObject.NULL)
    }
    private fun save(input: JSONObject, update: Boolean): JSONObject {
        val data = input.getJSONObject("memo")
        val editorKey = input.optString("editorKey", "users/device-home-memo-editor")
        val entryFile = draftKey(editorKey)
        val entry = json(entryFile)
        val draftId = entry.optString("id").ifBlank { newId() }
        val previous = if (update) note(data.getString("name")) else null
        if (update) {
            val mask = input.optString("updateMask").split(',').toSet()
            if (mask.all { it in setOf("pinned", "state", "space") }) {
                val group = if ("space" in mask) data.optString("space").takeIf { it.isNotBlank() }?.also { space(it) } else previous!!.spaceName
                repository.updateNote(previous!!.copy(pinned = if ("pinned" in mask) data.optBoolean("pinned") else previous.pinned,
                    archived = if ("state" in mask) data.optString("state") == "ARCHIVED" else previous.archived, spaceName = group))
                return memo(note(data.getString("name")))
            }
            if (input.optInt("localRevision", -1) != previous!!.revision) throw LocalUiFailure("原版本已变化，草稿保留，请重新打开")
        }
        if (previous?.frozen == true) throw LocalUiFailure("原版本已入队，请使用关联续写")
        val rawBody = data.optString("content", previous?.body ?: "")
        val refs = data.optJSONArray("attachments")
        val attachments = if (refs == null) previous?.attachments ?: emptyList() else (0 until refs.length()).map { attachmentRef(refs.getJSONObject(it).getString("name")) }
        val body = attachments.fold(rawBody) { text, ref -> text.replace("${LocalWebView.ORIGIN}/file/attachments/${ref.id}/${android.net.Uri.encode(ref.name)}", "echo-attachment:${ref.id}")
            .replace("/file/attachments/${ref.id}/${android.net.Uri.encode(ref.name)}", "echo-attachment:${ref.id}") }
        val location = if (data.has("location")) data.optJSONObject("location")?.let {
            val latitude = it.optDouble("latitude", Double.NaN); val longitude = it.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) throw LocalUiFailure("位置坐标无效")
            it.toString()
        } else previous?.location
        val relations = data.optJSONArray("relations") ?: JSONArray()
        if (relations.length() > 100) throw LocalUiFailure("引用最多 100 条")
        val names = (0 until relations.length()).mapNotNull { relations.getJSONObject(it).optJSONObject("relatedMemo")?.optString("name") }.distinct()
        val references = names.filterNot { it.startsWith("memos/r-") }.map { note(it).id }.filterNot { it == previous?.id }
        val remoteLinks = names.filter { it.startsWith("memos/r-") }.map { remote?.sourceUrl(it) ?: throw LocalUiFailure("原账号已断开，草稿保留") }
        val linkedBody = body + remoteLinks.joinToString("") { "\n\n续写自：[Memos 原记录]($it)" }
        val parent = if (editorKey.contains("-continuation-memos/") && references.isNotEmpty()) references.first() else previous?.parentId
        val group = data.optString("space").takeIf { it.isNotBlank() }?.also { space(it) } ?: previous?.spaceName
        if (data.optString("visibility", "PRIVATE") != "PRIVATE") throw LocalUiFailure("本机笔记不提供公开访问")
        val existingSave = if (!update) repository.notes().find { it.id == draftId || (!entry.optBoolean("active", true) && input.optString("changeToken").isNotBlank()
            && input.optString("changeToken") == entry.optString("lastSavedToken") && name(it) == entry.optString("lastSavedName") && linkedBody == entry.optString("lastSavedContent")) } else null
        if (existingSave != null) {
            if (existingSave.body != linkedBody || existingSave.attachments != attachments) throw LocalUiFailure("保存会话已变化，请保留草稿并刷新")
            return memo(existingSave)
        }
        val draft = Draft(id = previous?.id ?: draftId, body = linkedBody, todo = Regex("(?m)^\\s*- \\[[ xX]] ").containsMatchIn(body),
            attachments = attachments, parentId = parent, location = location, referenceIds = references, spaceName = group,
            created = data.optString("createTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: previous?.created ?: System.currentTimeMillis(),
            updatedOverride = data.optString("updateTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() },
            generation = (repository.find("draft", previous?.id ?: draftId, Draft::class.java)?.generation ?: 0) + 1,
            baseRevision = previous?.revision ?: 0)
        val recoverable = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", rawBody)
            .put("attachments", JSONArray(attachments.map { attachment(it) })).put("location", data.optJSONObject("location") ?: JSONObject.NULL).put("relations", relations)
            .put("changeToken", input.optString("changeToken"))
        val consumedValue = entry.optString("value").ifBlank { recoverable.toString() }
        write(entryFile, entry.put("id", draft.id).put("key", editorKey).put("savingContent", linkedBody).put("saveCheckpoint", gson.toJson(draft))
            .put("pendingConsumedValue", consumedValue).put("pendingSaveToken", input.optString("changeToken")).put("value", recoverable.toString()).put("active", true))
        val saved = repository.save(draft) ?: throw LocalUiFailure("空白笔记无需保存")
        write(entryFile, entry.put("active", false).put("lastSavedName", name(saved)).put("lastSavedContent", linkedBody).put("lastSavedToken", input.optString("changeToken")).put("lastConsumedValue", consumedValue).put("id", newId()))
        if (!update) repository.select(Draft())
        schedule()
        return memo(saved)
    }
    fun request(command: String, input: JSONObject): Any = when (command) {
        "local.bootstrap" -> bootstrap()
        "local.draft" -> storeDraft(input)
        "local.clear-draft" -> {
            val key = input.getString("key"); val file = draftKey(key)
            val entry = json(file)
            val id = entry.optString("id")
            entry.remove("lastConsumedValue")
            write(file, entry.put("key", key).put("active", false).put("id", newId()))
            repository.find("draft", id, Draft::class.java)?.let { repository.saveDraft(it.copy(body = "", attachments = emptyList(), generation = it.generation + 1)) }
            JSONObject().put("cleared", true)
        }
        "local.list" -> {
            val notes = repository.notes()
            val all = notes.map { memo(it, notes) } + remote?.snapshot().orEmpty()
            val offset = input.optInt("offset")
            if (offset !in 0..all.size) throw LocalUiFailure("列表已变化，请刷新")
            JSONObject().put("memos", JSONArray(all.drop(offset).take(50))).put("next", if (offset + 50 < all.size) offset + 50 else -1)
        }
        "local.account" -> remote?.account() ?: JSONObject()
        "local.export-note" -> exports.create(listOf(note(input.getString("name"))), false)
        "local.export-archive" -> exports.create(repository.notes(), true)
        "memos.api.v1.UserService/ImportMemos" -> {
            val request = JSONObject(input.toString())
            input.optJSONObject("spec")?.let { spec -> request.put("spec", JSONObject().put("attachment", JSONObject().put("filename", "memos-import.zip").put("type", "application/zip")).put("totalSize", spec.getString("totalSize")).put("purpose", "import")) }
            val result = upload(request)
            if (input.optBoolean("finishWrite")) {
                val id = input.getString("uploadId"); val source = File(root, "uploads/$id.bin")
                val archive = imports.read(source)
                if (input.optBoolean("validateOnly")) result.put("plan", imports.plan(archive))
                else {
                    val journal = File(root, "uploads/$id.json"); val state = json(journal)
                    val report = state.optJSONObject("report") ?: imports.commit(archive, input.optString("conflictPolicy", "SKIP"))
                    write(journal, state.put("complete", true).put("report", report)); result.put("report", report)
                }
            }
            result
        }
        "local.memos-cache" -> { remote?.saveOffline(input.getString("name")) ?: throw LocalUiFailure("原账号已断开"); JSONObject().put("saved", true) }
        "local.memos-copy" -> {
            val name = input.getString("name"); val cached = remote?.cached(name) ?: throw LocalUiFailure("请先离线保存")
            val imported = cached.files.map { it.file.inputStream().use { stream -> repository.importStream(stream, it.attachment.filename, it.attachment.mime) } }
            var body = cached.memo.body
            cached.files.forEachIndexed { index, original ->
                val old = "/file/${original.attachment.name}/${android.net.Uri.encode(original.attachment.filename)}"
                body = body.replace("${remote.account()?.optString("origin")}$old", "echo-attachment:${imported[index].id}").replace(old, "echo-attachment:${imported[index].id}")
            }
            body += "\n\n---\n[Memos 原记录](${remote.sourceUrl(name)})"
            NoteFormat.validate(body, imported)
            val publicBody = imported.fold(body) { content, ref -> content.replace("echo-attachment:${ref.id}", "/file/attachments/${ref.id}/${android.net.Uri.encode(ref.name)}") }
            val value = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", publicBody).put("attachments", JSONArray(imported.map { attachment(it) })).put("location", JSONObject.NULL)
            storeDraft(JSONObject().put("key", "users/device-home-memo-editor").put("value", value.toString()))
            JSONObject().put("key", "users/device-home-memo-editor").put("value", value.toString())
        }
        "memos.api.v1.AttachmentService/UploadAttachment" -> upload(input)
        "memos.api.v1.AttachmentService/GetAttachment" -> attachment(attachmentRef(input.getString("name")))
        "memos.api.v1.AttachmentService/ListAttachments" -> {
            val records = repository.notes().flatMap { it.attachments } + repository.drafts().flatMap { it.attachments } +
                File(root, "attachments").listFiles().orEmpty().filter { it.extension == "json" && !json(it).optBoolean("hidden") }
                    .map { gson.fromJson(json(it).getString("reference"), AttachmentRef::class.java) }
            JSONObject().put("attachments", JSONArray(records.distinctBy { it.id }.map { attachment(it) }))
        }
        "memos.api.v1.AttachmentService/DeleteAttachment" -> {
            val ref = attachmentRef(input.getString("name"))
            if (repository.notes().any { note -> note.attachments.any { it.id == ref.id } }) throw LocalUiFailure("附件被笔记引用，请先在草稿中移除；原版本保留")
            val file = File(root, "attachments/${ref.id}.json")
            write(file, json(file).put("hidden", true)); JSONObject()
        }
        "memos.api.v1.AuthService/GetCurrentUser" -> JSONObject().put("user", user())
        "memos.api.v1.AuthService/SignOut" -> { remote?.disconnect(); JSONObject() }
        "memos.api.v1.UserService/GetUser" -> { if (input.optString("name", "users/device") != "users/device") throw LocalUiFailure("此客户端只访问本人记录"); user() }
        "memos.api.v1.UserService/ListUsers" -> JSONObject().put("users", JSONArray().put(user()))
        "memos.api.v1.UserService/ListUserSettings" -> {
            val settings = json(File(root, "preferences.json"))
            val general = settings.optJSONObject("generalSetting") ?: JSONObject().put("locale", "zh-Hans").put("theme", "default").put("memoVisibility", "PRIVATE")
            JSONObject().put("settings", JSONArray().put(JSONObject().put("name", "users/device/settings/GENERAL").put("generalSetting", general)))
                .apply { settings.optJSONObject("tagsSetting")?.let { getJSONArray("settings").put(JSONObject().put("name", "users/device/settings/TAGS").put("tagsSetting", it)) } }
        }
        "memos.api.v1.UserService/UpdateUserSetting" -> {
            val setting = input.getJSONObject("setting")
            val kind = setting.optString("name").substringAfterLast('/')
            val field = when (kind) { "GENERAL" -> "generalSetting"; "TAGS" -> "tagsSetting"; else -> throw LocalUiFailure("该设备设置不支持") }
            if (setting.toString().length > 65536 || !setting.optString("name").startsWith("users/device/settings/")) throw LocalUiFailure("设备设置无效")
            val file = File(root, "preferences.json"); val settings = json(file)
            settings.put(field, setting.getJSONObject(field)); write(file, settings); setting
        }
        "memos.api.v1.InstanceService/GetInstanceProfile" -> JSONObject().put("version", "echo-local").put("admin", user()).put("accessMode", "PRIVATE")
        "memos.api.v1.InstanceService/GetInstanceSetting" -> JSONObject().put("name", input.optString("name"))
        "memos.api.v1.InstanceService/BatchGetInstanceSettings" -> JSONObject().put("settings", JSONArray())
        "memos.api.v1.SpaceService/ListSpaces" -> collection("spaces").let { groups -> JSONObject().put("spaces", JSONArray(groups.keys().asSequence().map { groups.getJSONObject(it) }.toList())) }
        "memos.api.v1.SpaceService/GetSpace" -> space(input.getString("name"))
        "memos.api.v1.SpaceService/CreateSpace", "memos.api.v1.SpaceService/UpdateSpace" -> {
            val group = input.getJSONObject("space"); val groups = collection("spaces")
            val name = if (command.endsWith("CreateSpace")) "spaces/${newId()}" else group.getString("name").also { space(it) }
            if (group.optString("title").isBlank() || group.optString("title").length > 200 || group.toString().length > 8192) throw LocalUiFailure("分组名称或配置无效")
            group.put("name", name).put("currentUserRole", "ADMIN").put("memberCount", 1)
            groups.put(name, group); collectionWrite("spaces", groups); group
        }
        "memos.api.v1.SpaceService/DeleteSpace" -> {
            val name = input.getString("name"); space(name)
            repository.notes().filter { it.spaceName == name }.forEach { repository.updateNote(it.copy(spaceName = null)) }
            val groups = collection("spaces"); groups.remove(name); collectionWrite("spaces", groups); JSONObject()
        }
        "memos.api.v1.SpaceService/ListSpaceMembers" -> JSONObject().put("members", JSONArray().put(JSONObject().put("name", "${input.optString("parent")}/members/device").put("user", "users/device").put("role", "ADMIN")))
        "memos.api.v1.SpaceService/ListSpaceInvitations" -> JSONObject().put("invitations", JSONArray())
        "memos.api.v1.UserService/ListMemoViews" -> collection("views").let { views -> JSONObject().put("views", JSONArray(views.keys().asSequence().map { views.getJSONObject(it) }.toList())) }
        "memos.api.v1.UserService/CreateMemoView", "memos.api.v1.UserService/UpdateMemoView" -> {
            val view = input.getJSONObject("view"); val views = collection("views")
            val name = if (command.endsWith("CreateMemoView")) "users/device/views/${newId()}" else view.getString("name")
            if (!Regex("users/device/views/[a-f0-9-]{36}").matches(name) || view.optString("title").isBlank() || view.optString("title").length > 200 || view.optString("filter").length > 4096) throw LocalUiFailure("视图配置无效")
            view.put("name", name); views.put(name, view); collectionWrite("views", views); view
        }
        "memos.api.v1.UserService/DeleteMemoView" -> {
            val name = input.getString("name"); if (!Regex("users/device/views/[a-f0-9-]{36}").matches(name)) throw LocalUiFailure("视图标识无效")
            val views = collection("views"); views.remove(name); collectionWrite("views", views); JSONObject()
        }
        "memos.api.v1.UserService/ListUserNotifications" -> JSONObject().put("notifications", JSONArray())
        "memos.api.v1.UserService/ListAllUserStats" -> JSONObject().put("stats", JSONArray())
        "memos.api.v1.UserService/GetUserStats" -> {
            val notes = repository.notes(); val tagCount = JSONObject()
            notes.filter { !it.archived }.flatMap { tags(it.body) }.groupingBy { it }.eachCount().forEach { (key, count) -> tagCount.put(key, count) }
            JSONObject().put("name", "users/device").put("memoCount", notes.size).put("memoDisplayTimestamps", JSONArray(notes.filter { !it.archived }.map { time(it.created) })).put("tagCount", tagCount)
        }
        "memos.api.v1.MemoService/ListMemos" -> {
            val filter = input.optString("filter")
            if (filter.contains("content.contains") || filter.contains("tag in") || filter.contains("_ts") || filter.contains("has_")) throw LocalUiFailure("该筛选适配尚未完成")
            val notes = repository.notes().filter { it.archived == (input.optString("state") == "ARCHIVED" || filter.contains("ARCHIVED")) }
                .filter { !filter.contains("pinned") || it.pinned }
            val start = input.optString("pageToken").ifBlank { "0" }.toIntOrNull() ?: throw LocalUiFailure("分页标识无效")
            val count = input.optInt("pageSize", 30).coerceIn(1, 100)
            if (start !in 0..notes.size) throw LocalUiFailure("列表已变化，请刷新")
            JSONObject().put("memos", JSONArray(notes.drop(start).take(count).map { memo(it).put("localRevision", it.revision) }))
                .put("nextPageToken", if (start + count < notes.size) (start + count).toString() else "")
        }
        "memos.api.v1.MemoService/GetMemo" -> input.getString("name").let { if (it.startsWith("memos/r-")) remote?.get(it) ?: throw LocalUiFailure("原账号已断开") else memo(note(it)) }
        "memos.api.v1.MemoService/CreateMemo" -> save(input, false)
        "memos.api.v1.MemoService/UpdateMemo" -> save(input, true)
        "memos.api.v1.MemoService/DeleteMemo" -> { repository.updateNote(note(input.getString("name")).copy(deleted = true)); JSONObject() }
        "memos.api.v1.MemoService/ListMemoComments" -> JSONObject().put("memos", JSONArray())
        "memos.api.v1.MemoService/ListMemoRelations" -> JSONObject().put("relations", memo(note(input.getString("name"))).getJSONArray("relations"))
        "echo.submit" -> {
            val value = note(input.getString("name")); val existing = repository.deliveries().find { it.noteId == value.id && it.revision == value.revision }
            val job = existing?.also { if (it.state != "verified") repository.retry(it.id) } ?: repository.enqueue(value.id)
            schedule(); JSONObject().put("submissionId", job.id).put("state", "pending").put("revision", value.updated / 1000)
        }
        "echo.status" -> JSONObject().put("enabled", true)
        "echo.memo-statuses" -> JSONArray(repository.notes().map { value ->
            val job = repository.deliveries().find { it.noteId == value.id && it.revision == value.revision }
            JSONObject().put("memo", name(value)).put("state", when { job?.state == "verified" && job.receipt != null -> "verified"; job?.state == "waiting_parent" -> "waiting_parent"; job != null -> "pending"; else -> "unknown" })
                .put("revision", value.updated / 1000).put("sourceHash", digest(memo(value).getString("content").toByteArray())).put("attachmentNames", JSONArray(value.attachments.map { "attachments/${it.id}" }))
                .apply { job?.error?.let { put("error", it) } }
                .apply { job?.receipt?.let { put("path", it.path); put("commit", it.commit) } }
        })
        else -> throw LocalUiFailure("该功能尚未适配，不会修改数据")
    }
}
