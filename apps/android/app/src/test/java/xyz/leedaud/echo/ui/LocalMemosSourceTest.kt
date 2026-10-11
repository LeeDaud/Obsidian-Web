package xyz.leedaud.echo.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.leedaud.echo.memos.*
import xyz.leedaud.echo.notes.newId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalMemosSourceTest {
    @Test fun offlineCopyUsesVersionBoundParsedSpansWithoutDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = MemosSession("https://example.test", "users/${newId()}", "synthetic", "synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
        val destination = "/file/attachments/source_1?download=1"
        val body = "😀 ![图片]($destination)\n\n`$destination`"
        val source = LocalMemosSource(context, { session }, { active ->
            MemosClient(MemosTransport { _, method, path, _, _, _, _ ->
                assertEquals("GET", method)
                val attachment = JSONObject().put("name", "attachments/source_1").put("filename", "图 片.png")
                    .put("type", "image/png").put("size", "3").put("memo", "memos/source")
                val memo = JSONObject().put("name", "memos/source").put("creator", active.user).put("content", body)
                    .put("createTime", "2026-10-09T00:00:00Z").put("attachments", JSONArray().put(attachment))
                val bytes = if (path.startsWith("/file/")) "abc".toByteArray()
                    else if (path.startsWith("/api/v1/attachments/")) attachment.toString().toByteArray()
                    else if (path.startsWith("/api/v1/memos?")) JSONObject().put("memos", JSONArray().apply { if (!path.contains("ARCHIVED")) put(memo) }).toString().toByteArray()
                    else memo.toString().toByteArray()
                MemosResponse(200, bytes)
            }, active)
        }, { true })
        val name = source.snapshot().single().getString("name")
        source.saveOffline(name)
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, xyz.leedaud.echo.storage.EchoDatabase::class.java).allowMainThreadQueries().build()
        try {
            val root = java.io.File(context.cacheDir, "copy-test-${newId()}").apply { mkdirs() }
            val repo = xyz.leedaud.echo.storage.NoteRepository(root, database)
            repo.setTarget(xyz.leedaud.echo.notes.Target("synthetic", "owner", "repo", "main", 1, true))
            val backend = LocalUiBackend(context, repo, {}, java.io.File(root, "local-ui-state"), source)
            val original = backend.request("local.memos-copy-source", JSONObject().put("name", name)) as JSONObject
            val spans = JSONArray().put(JSONObject().put("start", body.indexOf(destination)).put("end", body.indexOf(destination) + destination.length).put("name", "attachments/source_1"))
            val input = JSONObject().put("name", name).put("version", "wrong").put("destinations", spans)
            assertThrows(LocalUiFailure::class.java) { backend.request("local.memos-copy", input) }
            assertTrue(repo.drafts().isEmpty())
            input.put("version", original.getString("version"))
            val copied = backend.request("local.memos-copy", input) as JSONObject
            val draft = JSONObject(copied.getString("value"))
            assertTrue(draft.getString("content").contains("`$destination`"))
            assertFalse(draft.getString("content").contains("![图片]($destination)"))
            assertEquals(1, draft.getJSONArray("attachments").length())
            assertTrue(repo.notes().isEmpty()); assertTrue(repo.deliveries().isEmpty())
            assertEquals(body, source.get(name).getString("content"))
        } finally { database.close() }
    }
    @Test fun historyProjectsOwnedPublicMappingsWithoutRewritingRawMarkdown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = MemosSession("https://example.test", "users/${newId()}", "synthetic", "synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
        val body = "![图片](/file/attachments/source_1?download=1)\n\n`/file/attachments/source_1`"
        val source = LocalMemosSource(context, { session }, { active ->
            MemosClient(MemosTransport { _, method, path, _, _, _, _ ->
                assertEquals("GET", method)
                val attachment = JSONObject().put("name", "attachments/source_1").put("filename", "图 片.png")
                    .put("type", "image/png").put("size", "3").put("memo", "memos/source")
                val memo = JSONObject().put("name", "memos/source").put("creator", active.user).put("content", body)
                    .put("createTime", "2026-10-09T00:00:00Z").put("attachments", JSONArray().put(attachment))
                MemosResponse(200, JSONObject().put("memos", JSONArray().apply { if (!path.contains("ARCHIVED")) put(memo) }).toString().toByteArray())
            }, active)
        }, { true })
        val memo = source.snapshot().single()
        assertEquals(body, memo.getString("content"))
        val metadata = memo.getJSONObject("localMetadata")
        assertEquals(session.origin, metadata.getString("sourceOrigin"))
        val mapping = metadata.getJSONArray("attachmentMappings").getJSONObject(0)
        assertEquals("attachments/source_1", mapping.getString("name"))
        assertEquals("图 片.png", mapping.getString("filename"))
        assertEquals("/file/${memo.getJSONArray("attachments").getJSONObject(0).getString("name")}", mapping.getString("replacement"))
        assertFalse(memo.toString().contains("synthetic-access"))
        assertFalse(memo.toString().contains("synthetic-refresh"))
    }
    @Test fun sourceIdsAreScopedAndExitHidesOtherAccountData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var session: MemosSession? = MemosSession("https://example.test", "users/${newId()}", "synthetic", "synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
        val source = LocalMemosSource(context, { session }, { active ->
            MemosClient(MemosTransport { _, method, path, _, _, _, _ ->
                assertEquals("GET", method)
                val memo = JSONObject().put("name", "memos/source").put("creator", active.user).put("content", "owned synthetic content")
                    .put("createTime", "2026-10-09T00:00:00Z").put("state", "NORMAL")
                    .put("accessToken", "synthetic-access").put("unknownServerField", "not-for-web")
                val body = if (path.startsWith("/api/v1/memos?")) JSONObject().put("memos", JSONArray().apply { if (!path.contains("ARCHIVED")) put(memo) }) else memo
                MemosResponse(200, body.toString().toByteArray())
            }, active)
        }, { true })
        val a = source.snapshot().single()
        assertTrue(a.getString("name").startsWith("memos/r-"))
        assertTrue(a.getJSONObject("localMetadata").getBoolean("remote"))
        assertFalse(a.toString().contains("synthetic-access"))
        assertFalse(a.has("unknownServerField"))
        assertEquals("https://example.test/memos/source", source.sourceUrl(a.getString("name")))
        session = session!!.copy(user = "users/${newId()}")
        val b = source.snapshot().single()
        assertNotEquals(a.getString("name"), b.getString("name"))
        assertThrows(LocalUiFailure::class.java) { source.get(a.getString("name")) }
        session = null
        assertTrue(source.snapshot().isEmpty()); assertNull(source.account())
    }
}
