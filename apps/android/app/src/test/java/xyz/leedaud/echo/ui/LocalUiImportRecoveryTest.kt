package xyz.leedaud.echo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalUiImportRecoveryTest {
    private lateinit var database: EchoDatabase
    private lateinit var repo: NoteRepository
    private lateinit var root: File
    private class Interrupted : Error()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "import-recovery-${newId()}").apply { mkdirs() }
        repo = NoteRepository(root, database)
        repo.setTarget(Target("synthetic", "owner", "repo", "main", 1, true))
    }
    @After fun close() { database.close() }
    private fun archive(vararg bodies: Pair<String, String>): LocalUiImports.Archive {
        val entries = bodies.associate { (id, body) -> "memos/$id.md" to body.toByteArray() }
        val memos = bodies.map { (id, _) -> JSONObject().put("uid", id).put("contentPath", "memos/$id.md").put("createTime", "2026-10-11T00:00:00Z") }
        return LocalUiImports.Archive(JSONObject(), memos, entries)
    }
    @Test fun completedItemSurvivesReopenWithoutDuplicateOrOverwrite() {
        val source = archive("a" to "first", "b" to "second")
        val journal = File(root, "session.json")
        val session = JSONObject()
        assertThrows(Interrupted::class.java) {
            LocalUiImports(repo).commit(source, "DUPLICATE", session = session) { checkpoint ->
                journal.writeText(checkpoint.toString())
                if (checkpoint.getJSONObject("entries").getJSONObject("a").optString("phase") == "done") throw Interrupted()
            }
        }
        assertEquals(1, repo.notes().size)
        val first = repo.notes().single()
        repo.save(repo.edit(first).copy(body = "user edited", generation = 2), authorizeDelivery = false)
        val reopened = NoteRepository(root, database)
        val report = LocalUiImports(reopened).commit(source, "DUPLICATE", session = JSONObject(journal.readText())) { journal.writeText(it.toString()) }
        assertEquals(2, report.getInt("created")); assertEquals(0, report.getInt("failed"))
        assertEquals(2, reopened.notes().size)
        assertEquals("user edited", reopened.notes().first { it.id == first.id }.body)
        assertTrue(reopened.deliveries().isEmpty())
    }
    @Test fun saveBeforeCheckpointDoesNotCreateAnotherCopyOnRetry() {
        val source = archive("a" to "first")
        val journal = File(root, "session.json")
        assertThrows(Interrupted::class.java) {
            LocalUiImports(repo) { throw Interrupted() }.commit(source, "DUPLICATE", session = JSONObject()) { journal.writeText(it.toString()) }
        }
        val first = repo.notes().single()
        val report = LocalUiImports(NoteRepository(root, database)).commit(source, "DUPLICATE", session = JSONObject(journal.readText()))
        assertEquals(1, report.getInt("failed")); assertEquals(0, report.getInt("created"))
        assertEquals(listOf(first), repo.notes()); assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun changedReplacementTargetIsProtectedAfterPreparation() {
        val original = repo.save(Draft(id = "a", body = "old", generation = 1), authorizeDelivery = false)!!
        val source = archive("a" to "imported")
        val session = JSONObject()
        assertThrows(Interrupted::class.java) { LocalUiImports(repo).commit(source, "REPLACE", session = session) { throw Interrupted() } }
        repo.save(repo.edit(original).copy(body = "new user input", generation = 2), authorizeDelivery = false)
        val report = LocalUiImports(repo).commit(source, "REPLACE", session = session)
        assertEquals(1, report.getInt("failed"))
        assertEquals("new user input", repo.notes().single().body)
        assertTrue(repo.deliveries().isEmpty())
    }
    @Test fun startedSessionRejectsChangedPolicyAndArchive() {
        val source = archive("a" to "first")
        val session = JSONObject()
        assertThrows(Interrupted::class.java) { LocalUiImports(repo).commit(source, "DUPLICATE", session = session) { throw Interrupted() } }
        assertThrows(LocalUiFailure::class.java) { LocalUiImports(repo).commit(source, "REPLACE", session = session) }
        assertThrows(LocalUiFailure::class.java) { LocalUiImports(repo).commit(archive("a" to "changed"), "DUPLICATE", session = session) }
        assertTrue(repo.notes().isEmpty())
    }
}
