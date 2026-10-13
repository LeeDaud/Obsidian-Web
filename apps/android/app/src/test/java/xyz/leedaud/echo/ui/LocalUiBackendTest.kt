package xyz.leedaud.echo.ui

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.leedaud.echo.storage.*
import xyz.leedaud.echo.notes.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalUiBackendTest {
    private lateinit var db: EchoDatabase
    private lateinit var repo: NoteRepository
    private lateinit var backend: LocalUiBackend
    private lateinit var root: File
    private var schedules = 0
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "local-ui-${newId()}").apply { mkdirs() }
        repo = NoteRepository(root, db)
        backend = LocalUiBackend(context, repo, { schedules++ }, File(root, "bridge"))
    }
    @After fun close() { db.close() }
    @Test fun reactionsSurviveReopenWithoutChangingFrozenNoteOrQueue() {
        val memo = create("心情合成笔记")
        val name = memo.getString("name")
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
        repo.enqueue(repo.notes().single().id)
        val note = repo.notes().single(); val job = repo.deliveries().single()
        val bytes = repo.file(note.file).readBytes(); val beforeSchedules = schedules
        val input = JSONObject().put("name", name).put("reaction", JSONObject().put("reactionType", "😄").put("creator", "users/other"))
        val reaction = backend.request("memos.api.v1.MemoService/UpsertMemoReaction", input) as JSONObject
        assertEquals("users/device", reaction.getString("creator"))
        assertEquals(reaction.toString(), backend.request("memos.api.v1.MemoService/UpsertMemoReaction", input).toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = LocalUiBackend(context, repo, { schedules++ }, File(root, "bridge"))
        val read = reopened.request("memos.api.v1.MemoService/GetMemo", JSONObject().put("name", name)) as JSONObject
        assertEquals(1, read.getJSONArray("reactions").length())
        assertEquals("😄", read.getJSONArray("reactions").getJSONObject(0).getString("reactionType"))
        assertEquals(note, repo.notes().single()); assertEquals(job, repo.deliveries().single())
        assertArrayEquals(bytes, repo.file(note.file).readBytes()); assertEquals(beforeSchedules, schedules)
        reopened.request("memos.api.v1.MemoService/DeleteMemoReaction", JSONObject().put("name", reaction.getString("name")))
        reopened.request("memos.api.v1.MemoService/DeleteMemoReaction", JSONObject().put("name", reaction.getString("name")))
        assertEquals(0, (backend.request("memos.api.v1.MemoService/GetMemo", JSONObject().put("name", name)) as JSONObject).getJSONArray("reactions").length())
    }
    @Test fun reactionPaletteAndInvalidResourcesAreProtected() {
        val settings = backend.request("memos.api.v1.InstanceService/BatchGetInstanceSettings", JSONObject().put("names",
            org.json.JSONArray().put("instance/settings/MEMO_RELATED"))) as JSONObject
        assertEquals(16, settings.getJSONArray("settings").getJSONObject(0).getJSONObject("memoRelatedSetting").getJSONArray("reactions").length())
        val memo = create("source")
        val input = JSONObject().put("name", memo.getString("name")).put("reaction", JSONObject().put("reactionType", "unknown"))
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/UpsertMemoReaction", input) }
        input.getJSONObject("reaction").put("reactionType", "❤️")
        input.put("name", "memos/r-remote")
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/UpsertMemoReaction", input) }
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/DeleteMemoReaction", JSONObject().put("name", "../../reactions/test")) }
        val note = repo.notes().single(); repo.updateNote(note.copy(archived = true)); input.put("name", memo.getString("name"))
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/UpsertMemoReaction", input) }
        assertFalse(File(root, "bridge/reactions.json").exists())
    }
    private fun create(body: String) = backend.request("memos.api.v1.MemoService/CreateMemo", JSONObject().put("memo", JSONObject().put("content", body).put("visibility", "PRIVATE"))) as JSONObject
    @Test fun localBootstrapContainsNoCredentialsAndNoQueue() {
        val data = backend.request("local.bootstrap", JSONObject()).toString()
        assertFalse(data.contains("accessToken")); assertFalse(data.contains("refresh")); assertTrue(repo.deliveries().isEmpty()); assertEquals(0, schedules)
    }
    @Test fun originalRpcSavesLocalMarkdownAndOptimisticUpdates() {
        val memo = create("synthetic original format **bold**")
        assertEquals("synthetic original format **bold**", repo.notes().single().body)
        assertTrue(repo.deliveries().isEmpty())
        val update = JSONObject().put("memo", JSONObject().put("name", memo.getString("name")).put("content", "changed"))
            .put("updateMask", "content").put("localRevision", 1)
        backend.request("memos.api.v1.MemoService/UpdateMemo", update)
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/UpdateMemo", update) }
        assertEquals("changed", repo.notes().single().body)
    }
    @Test fun nativeDraftMirrorNeverSavesOrSchedulesAndClearDoesNotResurrect() {
        val value = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", "synthetic draft").toString()
        backend.request("local.draft", JSONObject().put("key", "users/device-").put("value", value))
        assertTrue((backend.request("local.bootstrap", JSONObject()) as JSONObject).getJSONObject("drafts").getString("users/device-").contains("synthetic draft"))
        assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty()); assertEquals(0, schedules)
        backend.request("local.clear-draft", JSONObject().put("key", "users/device-"))
        assertFalse(backend.request("local.bootstrap", JSONObject()).toString().contains("synthetic draft"))
    }
    @Test fun frozenOriginalCannotBeOverwritten() {
        val memo = create("source")
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
        repo.enqueue(repo.notes().single().id)
        val original = repo.notes().single()
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.MemoService/UpdateMemo", JSONObject()
            .put("memo", JSONObject().put("name", memo.getString("name")).put("content", "overwrite"))
            .put("updateMask", "content").put("localRevision", 1)) }
        assertEquals(original, repo.notes().single())
    }
    @Test fun unknownCommandsAndArbitraryFilePathsAreRejected() {
        assertThrows(LocalUiFailure::class.java) { backend.request("execute", JSONObject().put("path", "secrets/anything")) }
        assertThrows(LocalUiFailure::class.java) { backend.attachmentFile("attachments/../../secrets") }
        assertFalse(LocalWebView.safePath(Uri.parse("https://echo-app.local/%2e%2e/secrets")))
        assertFalse(LocalWebView.safePath(Uri.parse("https://example.test/")))
        assertFalse(LocalWebView.safePath(Uri.parse("file:///data/user/0/secrets")))
        assertTrue(LocalWebView.safePath(Uri.parse("https://echo-app.local/memos/test")))
        assertFalse(LocalWebView.trustedDocument(Uri.parse("https://echo-app.local/file/attachments/test/evil.svg")))
        assertFalse(LocalWebView.trustedDocument(Uri.parse("https://echo-app.local/assets/evil.html")))
        assertTrue(LocalWebView.trustedDocument(Uri.parse("https://echo-app.local/memos/test")))
    }
    @Test fun privateResourcesRequireLocalReferrerAndRejectForeignOrigins() {
        assertTrue(LocalWebView.privateResourceReader(false, mapOf("Referer" to "${LocalWebView.ORIGIN}/memos/synthetic")))
        assertTrue(LocalWebView.privateResourceReader(false, mapOf("referer" to "${LocalWebView.ORIGIN}/", "origin" to LocalWebView.ORIGIN)))
        assertFalse(LocalWebView.privateResourceReader(true, mapOf("Referer" to LocalWebView.ORIGIN)))
        assertFalse(LocalWebView.privateResourceReader(false, emptyMap()))
        assertFalse(LocalWebView.privateResourceReader(false, mapOf("Referer" to "https://www.youtube.com/embed/synthetic")))
        assertFalse(LocalWebView.privateResourceReader(false, mapOf("Referer" to LocalWebView.ORIGIN, "Origin" to "https://example.test")))
        assertFalse(LocalWebView.privateResourceReader(false, mapOf("Referer" to "https://echo-app.local.evil.test/")))
    }
    @Test fun attachmentRangesAreBoundedAndPreserveSandboxHeaders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "range-${newId()}.bin").apply { writeText("0123456789") }
        val response = LocalWebView.attachmentResponse("audio/test", file, "bytes=2-4")
        assertEquals(206, response.statusCode)
        assertEquals("bytes 2-4/10", response.responseHeaders["Content-Range"])
        assertTrue(response.responseHeaders.getValue("Content-Security-Policy").startsWith("sandbox;"))
        response.data.use { assertEquals(10, it.available()); assertEquals(2L, it.skip(2)); assertEquals("234", it.readBytes().toString(Charsets.UTF_8)) }
        LocalWebView.attachmentResponse("audio/test", file, "bytes=-3").data.use { assertEquals(10, it.available()); it.skip(7); assertEquals("789", it.readBytes().toString(Charsets.UTF_8)) }
        LocalWebView.attachmentResponse("audio/test", file, "bytes=8-").data.use { it.skip(8); assertEquals("89", it.readBytes().toString(Charsets.UTF_8)) }
        for (range in listOf("bytes=10-", "bytes=-0", "bytes=5-2", "bytes=0-1,3-4", "bytes=999999999999999999999999-")) {
            val invalid = LocalWebView.attachmentResponse("audio/test", file, range)
            assertEquals(416, invalid.statusCode)
            invalid.data.use { assertEquals(0, it.readBytes().size) }
        }
        val full = LocalWebView.attachmentResponse("audio/test", file, null)
        assertEquals(200, full.statusCode)
        full.data.use { assertEquals("0123456789", it.readBytes().toString(Charsets.UTF_8)) }
    }
    @Test fun chunkedAttachmentRetryIsByteVerifiedAndNotQueued() {
        val started = backend.request("memos.api.v1.AttachmentService/UploadAttachment", JSONObject().put("spec", JSONObject()
            .put("attachment", JSONObject().put("filename", "fixture.txt").put("type", "text/plain")
                .put("motionMedia", JSONObject().put("groupId", "synthetic-motion").put("family", "APPLE_LIVE_PHOTO").put("role", "STILL"))
                .put("mediaMetadata", JSONObject().put("width", 8).put("height", 9))).put("totalSize", "3"))) as JSONObject
        val chunk = JSONObject().put("uploadId", started.getString("uploadId")).put("writeOffset", "0").put("data", java.util.Base64.getEncoder().encodeToString("abc".toByteArray())).put("finishWrite", true)
        val first = backend.request("memos.api.v1.AttachmentService/UploadAttachment", chunk) as JSONObject
        val again = backend.request("memos.api.v1.AttachmentService/UploadAttachment", chunk) as JSONObject
        assertEquals(first.getJSONObject("attachment").getString("name"), again.getJSONObject("attachment").getString("name"))
        assertEquals("synthetic-motion", again.getJSONObject("attachment").getJSONObject("motionMedia").getString("groupId"))
        val restored = backend.request("memos.api.v1.AttachmentService/GetAttachment", JSONObject().put("name", first.getJSONObject("attachment").getString("name"))) as JSONObject
        assertEquals(8, restored.getJSONObject("mediaMetadata").getInt("width"))
        assertEquals("abc", backend.attachmentFile(first.getJSONObject("attachment").getString("name")).second.readText())
        assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty()); assertEquals(0, schedules)
        assertThrows(LocalUiFailure::class.java) { backend.request("memos.api.v1.AttachmentService/UploadAttachment", chunk.put("data", java.util.Base64.getEncoder().encodeToString("bad".toByteArray()))) }
    }
    @Test fun recorderCodecMimeIsNormalizedWithoutChangingBytes() {
        val started = backend.request("memos.api.v1.AttachmentService/UploadAttachment", JSONObject().put("spec", JSONObject()
            .put("attachment", JSONObject().put("filename", "voice-fixture.webm").put("type", "audio/webm;codecs=opus")).put("totalSize", "3"))) as JSONObject
        val result = backend.request("memos.api.v1.AttachmentService/UploadAttachment", JSONObject().put("uploadId", started.getString("uploadId"))
            .put("writeOffset", "0").put("data", java.util.Base64.getEncoder().encodeToString("abc".toByteArray())).put("finishWrite", true)) as JSONObject
        val attachment = result.getJSONObject("attachment")
        assertEquals("audio/webm", attachment.getString("type"))
        assertEquals("abc", backend.attachmentFile(attachment.getString("name")).second.readText())
        assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun multireferencesPersistWithoutChangingLegacySha() {
        val a = create("reference A"); val b = create("reference B")
        val relations = org.json.JSONArray(listOf(a, b).map { JSONObject().put("type", "REFERENCE").put("relatedMemo", JSONObject().put("name", it.getString("name"))) })
        backend.request("memos.api.v1.MemoService/CreateMemo", JSONObject().put("memo", JSONObject().put("content", "linked").put("visibility", "PRIVATE").put("relations", relations)))
        val result = repo.notes().find { it.body == "linked" }!!
        assertEquals(2, result.referenceIds!!.size)
        assertEquals(2, Regex("echo://memos/").findAll(repo.file(result.file).readText()).count())
        assertNull(result.parentId)
        val legacy = repo.notes().find { it.body == "reference A" }!!
        assertEquals(digest("reference A".toByteArray()), legacy.sha256)
    }
    @Test fun savedDraftIsNotResurrectedByBootstrapAndRetriesAreIdempotent() {
        val key = "users/device-home-memo-editor"
        val value = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", "once").toString()
        backend.request("local.draft", JSONObject().put("key", key).put("value", value))
        val save = JSONObject().put("editorKey", key).put("changeToken", "synthetic-session").put("memo", JSONObject().put("content", "once").put("visibility", "PRIVATE"))
        val first = backend.request("memos.api.v1.MemoService/CreateMemo", save) as JSONObject
        val again = backend.request("memos.api.v1.MemoService/CreateMemo", save) as JSONObject
        assertEquals(first.getString("name"), again.getString("name")); assertEquals(1, repo.notes().size)
        val restored = backend.request("local.bootstrap", JSONObject()) as JSONObject
        assertFalse(restored.getJSONObject("drafts").toString().contains("once"))
    }
    @Test fun lateAutosaveOfConsumedVersionDoesNotResurrectButNewInputIsAllowed() {
        val key = "users/device-home-memo-editor"
        val value = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", "same content")
            .put("changeToken", "first-input").toString()
        backend.request("local.draft", JSONObject().put("key", key).put("value", value))
        backend.request("memos.api.v1.MemoService/CreateMemo", JSONObject().put("editorKey", key).put("changeToken", "first-input")
            .put("memo", JSONObject().put("content", "same content").put("visibility", "PRIVATE")))
        val count = repo.drafts().size
        val late = JSONObject(value).put("baseRevision", "1").toString()
        backend.request("local.draft", JSONObject().put("key", key).put("value", late))
        val restored = backend.request("local.bootstrap", JSONObject()) as JSONObject
        assertFalse(restored.getJSONObject("drafts").toString().contains("same content"))
        assertEquals(value, restored.getJSONObject("consumed").getString(key))
        assertEquals(count, repo.drafts().size)
        val next = JSONObject(value).put("changeToken", "second-input").toString()
        backend.request("local.draft", JSONObject().put("key", key).put("value", next))
        assertTrue((backend.request("local.bootstrap", JSONObject()) as JSONObject).getJSONObject("drafts").getString(key).contains("same content"))
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun interruptedSaveConsumesOnlyAnExactFormalSnapshot() {
        val key = "users/device-home-memo-editor"
        val draft = Draft(body = "checkpoint snapshot")
        repo.save(draft, authorizeDelivery = false)
        val value = JSONObject().put("kind", "memos.editor-cache").put("version", 4).put("content", draft.body)
            .put("baseRevision", "0").put("changeToken", "checkpoint-token").toString()
        val journal = File(root, "bridge/drafts/${digest(key.toByteArray())}.json").apply { parentFile!!.mkdirs() }
        val entry = JSONObject().put("key", key).put("id", draft.id).put("active", true).put("value", value)
            .put("saveCheckpoint", com.google.gson.Gson().toJson(draft.copy(referenceIds = listOf("missing-reference"))))
            .put("pendingConsumedValue", value).put("pendingSaveToken", "checkpoint-token")
        journal.writeText(entry.toString())
        var bootstrap = backend.request("local.bootstrap", JSONObject()) as JSONObject
        assertTrue(bootstrap.getJSONObject("drafts").has(key))
        assertFalse(bootstrap.getJSONObject("consumed").has(key))
        journal.writeText(entry.put("saveCheckpoint", com.google.gson.Gson().toJson(draft)).toString())
        bootstrap = backend.request("local.bootstrap", JSONObject()) as JSONObject
        assertFalse(bootstrap.getJSONObject("drafts").toString().contains("checkpoint snapshot"))
        assertEquals(value, bootstrap.getJSONObject("consumed").getString(key))
        backend.request("local.draft", JSONObject().put("key", key).put("value", JSONObject(value).put("baseRevision", "1").toString()))
        assertFalse((backend.request("local.bootstrap", JSONObject()) as JSONObject).getJSONObject("drafts").toString().contains("checkpoint snapshot"))
        assertEquals(1, repo.notes().size)
    }
    @Test fun exportArchiveContainsVerifiedOriginalMarkdownAndNoSecrets() {
        create("export **format**")
        val output = backend.request("local.export-archive", JSONObject()) as JSONObject
        val file = backend.attachmentFile(output.getString("name")).second
        java.util.zip.ZipFile(file).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals("memos-export", manifest.getString("format"))
            val content = zip.entries().asSequence().first { it.name.startsWith("memos/") && it.name.endsWith(".md") }
            assertEquals("export **format**", zip.getInputStream(content).bufferedReader().readText())
        }
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun inlineImageProjectionMarkdownAndArchiveRoundtripPreserveOneImage() {
        val started = backend.request("memos.api.v1.AttachmentService/UploadAttachment", JSONObject().put("spec", JSONObject()
            .put("attachment", JSONObject().put("filename", "图 片.webp").put("type", "image/webp")).put("totalSize", "3"))) as JSONObject
        val upload = backend.request("memos.api.v1.AttachmentService/UploadAttachment", JSONObject().put("uploadId", started.getString("uploadId"))
            .put("writeOffset", "0").put("data", java.util.Base64.getEncoder().encodeToString("abc".toByteArray())).put("finishWrite", true)) as JSONObject
        val attachment = upload.getJSONObject("attachment")
        val id = attachment.getString("name").removePrefix("attachments/")
        val original = "前文\n\n![图片](echo-attachment:$id)\n\n后文"
        val result = backend.request("memos.api.v1.MemoService/CreateMemo", JSONObject().put("memo", JSONObject().put("content", original)
            .put("visibility", "PRIVATE").put("attachments", org.json.JSONArray().put(attachment)))) as JSONObject
        assertEquals("前文\n\n![图片](/file/attachments/$id)\n\n后文", result.getString("content"))
        val note = repo.notes().single()
        val markdown = repo.file(note.file).readText()
        assertEquals(1, Regex("!\\[").findAll(markdown).count())
        assertFalse(markdown.contains("echo-attachment:")); assertFalse(markdown.contains("/file/attachments/"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exports = LocalUiExports(context, repo)
        val archive = exports.create(listOf(note), true)
        val importer = LocalUiImports(repo)
        val parsed = importer.read(exports.resolve(archive.getString("name")).second)
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
        val report = importer.commit(parsed, "DUPLICATE")
        assertEquals(1, report.getInt("created")); assertEquals(0, report.getInt("failed"))
        val copied = repo.notes().first { it.id != note.id }
        assertEquals(1, copied.attachments.size)
        assertEquals(original.replace(id, copied.attachments.single().id), copied.body)
        assertEquals("abc", repo.file(copied.attachments.single().file).readText())
        assertTrue(repo.deliveries().isEmpty())
        val destination = "/file/attachments/$id?download=1#view"
        val raw = "😀 ![图片]($destination \"标题\")\n\n`$destination`"
        val uid = parsed.memos.single().getString("uid")
        val path = parsed.memos.single().getString("contentPath")
        val modified = parsed.copy(entries = parsed.entries + (path to raw.toByteArray()))
        val spans = org.json.JSONArray().put(JSONObject().put("start", raw.indexOf(destination))
            .put("end", raw.indexOf(destination) + destination.length).put("name", "attachments/$id"))
        val parsedReport = importer.commit(modified, "DUPLICATE", mapOf(uid to spans))
        assertEquals(1, parsedReport.getInt("created")); assertEquals(0, parsedReport.getInt("failed"))
        val parsedCopy = repo.notes().first { it.id != note.id && it.id != copied.id }
        assertEquals("😀 ![图片](echo-attachment:${parsedCopy.attachments.single().id} \"标题\")\n\n`$destination`", parsedCopy.body)
        assertEquals("abc", repo.file(parsedCopy.attachments.single().file).readText())
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun stagedImportRequiresMatchingParsedSourceAndRetriesKeepOneReport() {
        create("parsed archive protocol")
        val exported = backend.request("local.export-archive", JSONObject()) as JSONObject
        val bytes = backend.attachmentFile(exported.getString("name")).second.readBytes()
        val start = backend.request("memos.api.v1.UserService/ImportMemos", JSONObject().put("spec", JSONObject().put("totalSize", bytes.size.toString()))) as JSONObject
        val id = start.getString("uploadId")
        backend.request("memos.api.v1.UserService/ImportMemos", JSONObject().put("uploadId", id).put("writeOffset", "0")
            .put("data", java.util.Base64.getEncoder().encodeToString(bytes)).put("finishWrite", true).put("validateOnly", true))
        val source = backend.request("local.import-source", JSONObject().put("uploadId", id).put("index", 0)) as JSONObject
        val input = JSONObject().put("uploadId", id).put("uid", source.getString("uid")).put("sha256", "wrong").put("destinations", org.json.JSONArray())
        assertThrows(LocalUiFailure::class.java) { backend.request("local.import-destinations", input) }
        input.put("sha256", source.getString("sha256")); backend.request("local.import-destinations", input)
        val finish = JSONObject().put("uploadId", id).put("writeOffset", bytes.size.toString()).put("finishWrite", true)
            .put("parsedDestinations", true).put("conflictPolicy", "DUPLICATE")
        val first = backend.request("memos.api.v1.UserService/ImportMemos", finish) as JSONObject
        val retry = backend.request("memos.api.v1.UserService/ImportMemos", finish) as JSONObject
        assertEquals(1, first.getJSONObject("report").getInt("created"))
        assertEquals(first.getJSONObject("report").toString(), retry.getJSONObject("report").toString())
        assertEquals(2, repo.notes().size); assertTrue(repo.deliveries().isEmpty())
        val journal = File(root, "bridge/uploads/$id.json")
        val interrupted = JSONObject(journal.readText())
        assertEquals("DUPLICATE", interrupted.getJSONObject("importSession").getString("policy"))
        interrupted.remove("report"); journal.writeText(interrupted.toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = LocalUiBackend(context, repo, {}, File(root, "bridge"))
        val recovered = reopened.request("memos.api.v1.UserService/ImportMemos", finish) as JSONObject
        assertEquals(first.getJSONObject("report").toString(), recovered.getJSONObject("report").toString())
        assertEquals(2, repo.notes().size)
        assertThrows(LocalUiFailure::class.java) { reopened.request("memos.api.v1.UserService/ImportMemos", JSONObject(finish.toString()).put("conflictPolicy", "REPLACE")) }
    }
    @Test fun markdownExportPreservesNotesCreatedInTheSameSecond() {
        repo.save(Draft(body = "same second A", created = 1000), authorizeDelivery = false)
        repo.save(Draft(body = "same second B", created = 1000), authorizeDelivery = false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exports = LocalUiExports(context, repo)
        val result = exports.create(repo.notes(), archive = false)
        java.util.zip.ZipFile(exports.resolve(result.getString("name")).second).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name.endsWith(".md") }.toList()
            assertEquals(2, entries.size)
            assertEquals(setOf("same second A", "same second B"), entries.map { zip.getInputStream(it).bufferedReader().readText() }.toSet())
        }
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun archiveImportNeverAutoDeliversEvenWithAnEnabledTarget() {
        create("archive roundtrip")
        val exported = backend.request("local.export-archive", JSONObject()) as JSONObject
        val archive = LocalUiImports(repo).read(backend.attachmentFile(exported.getString("name")).second)
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
        val result = LocalUiImports(repo).commit(archive, "DUPLICATE")
        assertEquals(1, result.getInt("created")); assertEquals(0, result.getInt("failed"))
        assertTrue(repo.deliveries().isEmpty())
        assertEquals(2, repo.notes().size)
    }
    @Test fun archiveTraversalIsRejectedBeforeWritingNotes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "unsafe-${newId()}.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry("../secrets")); zip.write("synthetic".toByteArray()); zip.closeEntry() }
        assertThrows(LocalUiFailure::class.java) { LocalUiImports(repo).read(file) }
        assertTrue(repo.notes().isEmpty())
    }
}
