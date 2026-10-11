package xyz.leedaud.echo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.*
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalUiArchiveMetadataTest {
    private lateinit var database: EchoDatabase
    private lateinit var repo: NoteRepository
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java).allowMainThreadQueries().build()
        repo = NoteRepository(File(context.cacheDir, "archive-metadata-${newId()}").apply { mkdirs() }, database)
    }
    @After fun close() { database.close() }
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun archiveRoundtripPreservesTimesAttachmentNameTodoParentAndReferences() {
        val created = time("2020-01-02T03:04:05.123Z")
        val updated = time("2021-06-07T08:09:10.456Z")
        val attachmentTime = time("2019-02-03T04:05:06.789Z")
        val parent = repo.save(Draft(body = "parent", created = created, generation = 1), authorizeDelivery = false)!!
        val other = repo.save(Draft(body = "reference", created = created + 1000, generation = 1), authorizeDelivery = false)!!
        val attachment = repo.importStream("abc".byteInputStream(), "图 片.webp", "image/webp", attachmentTime).copy(name = "图 片.webp")
        val child = repo.save(Draft(body = "- [ ] child\n\n![图](echo-attachment:${attachment.id})", todo = true,
            parentId = parent.id, referenceIds = listOf(other.id), attachments = listOf(attachment), created = created + 2000,
            updatedOverride = updated, generation = 1), authorizeDelivery = false)!!
        repo.updateNote(child.copy(pinned = true, archived = true))
        val exports = LocalUiExports(context, repo)
        val exported = exports.create(listOf(parent, other, repo.notes().first { it.id == child.id }), true)
        val importer = LocalUiImports(repo)
        val archive = importer.read(exports.resolve(exported.getString("name")).second)
        val childRecord = archive.memos.first { it.getString("uid") == child.id }
        assertFalse(childRecord.has("parent"))
        assertEquals(parent.id, childRecord.getJSONObject("echoAndroid").getString("parent"))
        val session = JSONObject()
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
        val report = importer.commit(archive, "DUPLICATE", session = session)
        assertEquals(3, report.getInt("created")); assertEquals(0, report.getInt("failed"))
        val targets = session.getJSONObject("entries")
        val copied = repo.notes().first { it.id == targets.getJSONObject(child.id).getString("target") }
        assertEquals(child.created, copied.created); assertEquals(child.updated, copied.updated)
        assertTrue(copied.todo); assertTrue(copied.pinned); assertTrue(copied.archived)
        assertEquals(targets.getJSONObject(parent.id).getString("target"), copied.parentId)
        assertEquals(listOf(targets.getJSONObject(other.id).getString("target")), copied.referenceIds)
        assertEquals(attachmentTime, copied.attachments.single().created)
        assertEquals("图 片.webp", copied.attachments.single().name)
        assertEquals("abc", repo.file(copied.attachments.single().file).readText())
        val second = importer.read(exports.resolve(exports.create(listOf(copied), true).getString("name")).second).memos.single()
        assertEquals(childRecord.getString("updateTime"), second.getString("updateTime"))
        assertEquals(childRecord.getJSONArray("attachments").getJSONObject(0).getString("createTime"), second.getJSONArray("attachments").getJSONObject(0).getString("createTime"))
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun oldArchiveMissingOptionalTimesFallsBackToCreateTime() {
        val memo = JSONObject().put("uid", "old").put("createTime", "2020-01-02T03:04:05Z").put("contentPath", "memos/old.md")
        val archive = LocalUiImports.Archive(JSONObject(), listOf(memo), mapOf("memos/old.md" to "- [ ] old".toByteArray()))
        val report = LocalUiImports(repo).commit(archive, "SKIP")
        assertEquals(0, report.getInt("failed"))
        val note = repo.notes().single()
        assertEquals(note.created, note.updated); assertTrue(note.todo)
    }
    @Test fun invalidTimeMissingRelationAndUnknownExtensionFailWithoutSaving() {
        for (kind in listOf("time", "parent", "extension")) {
            val memo = JSONObject().put("uid", kind).put("createTime", "2020-01-02T03:04:05Z").put("contentPath", "memos/$kind.md")
            when (kind) {
                "time" -> memo.put("updateTime", "not-a-time")
                "parent" -> memo.put("parent", "missing")
                "extension" -> memo.put("echoAndroid", JSONObject().put("version", 2))
            }
            val archive = LocalUiImports.Archive(JSONObject(), listOf(memo), mapOf("memos/$kind.md" to "body".toByteArray()))
            assertEquals(1, LocalUiImports(repo).commit(archive, "SKIP").getInt("failed"))
        }
        assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun oldArchiveCodeExampleIsNotATodoAndAttachmentTimeFallsBack() {
        val created = "2020-01-02T03:04:05Z"
        val file = JSONObject().put("uid", "image").put("filename", "旧图.png").put("type", "image/png").put("size", 3).put("path", "attachments/image/old.png")
        val memo = JSONObject().put("uid", "old").put("createTime", created).put("contentPath", "memos/old.md").put("attachments", JSONArray().put(file))
        val archive = LocalUiImports.Archive(JSONObject(), listOf(memo), mapOf("memos/old.md" to "```md\n- [ ] example\n```".toByteArray(), "attachments/image/old.png" to "abc".toByteArray()))
        assertEquals(0, LocalUiImports(repo).commit(archive, "SKIP").getInt("failed"))
        val note = repo.notes().single()
        assertFalse(note.todo)
        assertEquals(time(created), note.attachments.single().created)
        assertEquals("旧图.png", note.attachments.single().name)
    }
    @Test fun cyclicParentsAreRejectedWithoutCreatingNotes() {
        val memos = listOf("a" to "b", "b" to "a").map { (uid, parent) -> JSONObject().put("uid", uid).put("parent", parent)
            .put("createTime", "2020-01-02T03:04:05Z").put("contentPath", "memos/$uid.md") }
        val archive = LocalUiImports.Archive(JSONObject(), memos, mapOf("memos/a.md" to "a".toByteArray(), "memos/b.md" to "b".toByteArray()))
        assertEquals(2, LocalUiImports(repo).commit(archive, "DUPLICATE").getInt("failed"))
        assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty())
    }
}
