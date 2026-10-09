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
