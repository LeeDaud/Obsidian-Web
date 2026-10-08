package xyz.leedaud.echo.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NoteRepositoryTest {
    private lateinit var database: EchoDatabase
    private lateinit var repository: NoteRepository
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java).allowMainThreadQueries().build()
        repository = NoteRepository(File(context.cacheDir, "native-test-${newId()}").apply { mkdirs() }, database)
    }
    @After fun close() { database.close() }
    @Test fun firstLaunchAndLocalSaveNeedNoTargetOrNetwork() {
        val draft = repository.activeDraft().copy(body = "离线中文", generation = 1)
        repository.saveDraft(draft)
        val note = repository.save(draft)!!
        assertTrue(repository.file(note.file).isFile)
        assertEquals("离线中文", repository.file(note.file).readText())
        assertEquals(0, repository.deliveries().size)
        assertFalse(note.frozen)
    }
    @Test fun staleDraftCannotReplaceNewerPersistedInput() {
        val draft = Draft(id = "draft", body = "新内容", generation = 8)
        assertTrue(repository.saveDraft(draft))
        assertFalse(repository.saveDraft(draft.copy(body = "旧内容", generation = 7)))
        assertEquals("新内容", repository.drafts().single().body)
    }
    @Test fun editingSavedNoteRestoresBodyAndPreservesLaterBlankDraft() {
        val note = repository.save(Draft(body = "保存后重新编辑", generation = 1))!!
        val edit = repository.edit(note)
        assertEquals(note.body, edit.body)
        assertEquals(note.revision, edit.baseRevision)
        val blank = edit.copy(body = "", generation = edit.generation + 1)
        repository.saveDraft(blank)
        assertEquals("", repository.edit(note).body)
        assertEquals(note.body, repository.notes().single().body)
        assertEquals(0, repository.deliveries().size)
    }
    @Test fun blankDoesNotCreateMarkdownOrDelivery() {
        assertNull(repository.save(Draft(body = "  \n ", generation = 1)))
        assertEquals(0, repository.notes().size)
        assertEquals(0, repository.deliveries().size)
        assertFalse(File(repository.root, "notes").exists())
    }
    @Test fun queuedSnapshotIsImmutableAndRetryDoesNotDuplicate() {
        repository.setTarget(Target("target", "owner", "repo", "main", 1, true))
        val draft = Draft(id = "queued", body = "原文", generation = 1)
        val note = repository.save(draft)!!
        val first = repository.enqueue(note.id)
        assertEquals(first.id, repository.enqueue(note.id).id)
        assertEquals(1, repository.deliveries().size)
        assertThrows(IllegalArgumentException::class.java) { repository.save(draft.copy(body = "不能覆盖", generation = 3, baseRevision = 1)) }
        assertEquals("原文", repository.deliveries().single().note.body)
        val continuation = repository.edit(note)
        assertNotEquals(note.id, continuation.id)
        assertEquals(note.id, continuation.parentId)
    }
    @Test fun attachmentsAreCopiedAndVerifiedAfterOriginalStreamIsGone() {
        val attachment = repository.importStream(ByteArrayInputStream("image".toByteArray()), "图片.png", "image/png")
        val note = repository.save(Draft(body = "有图片", attachments = listOf(attachment), generation = 1))!!
        val bundle = repository.bundle(note, "00_Inbox/20261008-143000.md", null)
        assertEquals(2, bundle.size)
        repository.verifyAttachment(attachment)
        repository.file(attachment.file).writeText("damaged")
        assertThrows(IllegalArgumentException::class.java) { repository.bundle(note, "00_Inbox/20261008-143000.md", null) }
    }
    @Test fun pendingAndVerifiedContinuationsNeverOverwriteParent() {
        repository.setTarget(Target("target", "owner", "repo", "main", 1, true))
        for (verified in listOf(false, true)) {
            val parent = repository.save(Draft(body = "父笔记-$verified", generation = 1))!!
            val originalBytes = repository.file(parent.file).readBytes()
            if (verified) {
                val job = repository.claim(repository.deliveries().single { it.noteId == parent.id }.id)!!
                repository.finish(job.copy(state = "verified", attempt = Attempt("synthetic-parent", "synthetic-commit", emptyMap()),
                    receipt = Receipt("synthetic-commit", "00_Inbox/20261008-100000.md", parent.created, emptyMap())))
            }
            val draft = repository.edit(parent)
            assertNotEquals(parent.id, draft.id)
            assertEquals(parent.id, draft.parentId)
            val child = repository.save(draft.copy(body = "续写-$verified", generation = draft.generation + 1))!!
            assertEquals(parent.id, child.parentId)
            assertEquals("父笔记-$verified", repository.notes().single { it.id == parent.id }.body)
            assertArrayEquals(originalBytes, repository.file(parent.file).readBytes())
            assertEquals("父笔记-$verified", repository.deliveries().single { it.noteId == parent.id }.note.body)
        }
    }
    @Test fun changingTargetPausesOriginalJobWithoutRebinding() {
        repository.setTarget(Target("old", "owner", "repo", "main", 1, true))
        val note = repository.save(Draft(body = "queued", generation = 1))!!
        val job = repository.deliveries().single()
        repository.setTarget(Target("new", "owner", "another", "main", 2, true))
        assertNull(repository.claim(job.id))
        assertEquals("old", repository.deliveries().single().target.id)
        assertEquals("paused", repository.deliveries().single().state)
        assertThrows(IllegalArgumentException::class.java) { repository.updateNote(note.copy(deleted = true)) }
    }
    @Test fun claimSerializesSameTargetAndRejectsStaleLease() {
        repository.setTarget(Target("target", "owner", "repo", "main", 1, true))
        repository.save(Draft(body = "first", generation = 1))
        repository.save(Draft(body = "second", generation = 1))
        val jobs = repository.deliveries()
        val first = repository.claim(jobs[0].id)!!
        assertNull(repository.claim(jobs[1].id))
        assertFalse(repository.checkpoint(first.copy(lease = "stale")))
        repository.finish(first.copy(state = "paused"))
        assertNotNull(repository.claim(jobs[1].id))
    }
    @Test fun corruptedMarkdownPreventsEnqueueWithoutDestroyingSavedRecord() {
        val note = repository.save(Draft(body = "stable", generation = 1))!!
        repository.file(note.file).writeText("corrupted")
        repository.setTarget(Target("target", "owner", "repo", "main", 1, true))
        assertThrows(IllegalArgumentException::class.java) { repository.enqueue(note.id) }
        assertEquals(0, repository.deliveries().size)
        assertEquals("stable", repository.notes().single().body)
    }
    @Test fun largeBinarySnapshotIsStoredAsFilesNotCursorPayload() {
        repository.setTarget(Target("target", "owner", "repo", "main", 1, true))
        val attachment = repository.importStream(ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 42 }), "large.bin", "application/octet-stream")
        val note = repository.save(Draft(body = "大附件", attachments = listOf(attachment), generation = 1))!!
        val job = repository.claim(repository.deliveries().single().id)!!
        val bytes = repository.bundle(note, "00_Inbox/20261008-143000.md", null)
        val manifest = repository.persistBundle(job.id, bytes)
        assertTrue(manifest.all { it.base64.isEmpty() && it.localFile.isNotBlank() })
        assertTrue(repository.checkpoint(job.copy(files = manifest)))
        assertTrue(database.records().find("delivery:${job.id}")!!.payload.length < 4096)
        assertEquals(bytes.map { it.base64 }, repository.hydrate(manifest).map { it.base64 })
    }
    @Test fun fileWriteFailureLeavesRecoverableJournalAndNoFalseSave() {
        val blocker = File(repository.root, "notes").apply { writeText("synthetic blocker") }
        assertThrows(IllegalStateException::class.java) { repository.save(Draft(id = "recover", body = "恢复正文", generation = 1)) }
        assertEquals(0, repository.notes().size)
        assertTrue(blocker.renameTo(File(repository.root, "synthetic-marker")))
        repository.recover()
        assertEquals("恢复正文", repository.notes().single().body)
        assertEquals("恢复正文", repository.file(repository.notes().single().file).readText())
    }
    @Test fun diskReopenRestoresAuthorizedSnapshotAndIndependentLaterDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "native-disk-test-${newId()}.db"
        var disk = Room.databaseBuilder(context, EchoDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val first = NoteRepository(repository.root, disk)
            first.setTarget(Target("target", "owner", "repo", "main", 1, true))
            val note = first.save(Draft(body = "授权的版本", generation = 1))!!
            val job = first.claim(first.deliveries().single().id)!!
            val manifest = first.persistBundle(job.id, first.bundle(note, "00_Inbox/20261008-143000.md", null))
            first.checkpoint(job.copy(path = "00_Inbox/20261008-143000.md", files = manifest,
                attempt = Attempt("parent", "candidate", mapOf(manifest.single().path to "blob"))))
            first.select(Draft(id = "later-draft", body = "没有授权上传的新草稿", generation = 4))
            disk.close()
            disk = Room.databaseBuilder(context, EchoDatabase::class.java, name).allowMainThreadQueries().build()
            val reopened = NoteRepository(repository.root, disk)
            reopened.recover()
            assertEquals("没有授权上传的新草稿", reopened.activeDraft().body)
            assertEquals("授权的版本", reopened.deliveries().single().note.body)
            assertEquals("candidate", reopened.deliveries().single().attempt!!.commit)
            assertEquals(manifest.single().sha256, reopened.hydrate(reopened.deliveries().single().files).single().sha256)
        } finally { disk.close() }
    }
}
