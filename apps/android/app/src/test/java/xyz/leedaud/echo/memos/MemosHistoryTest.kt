package xyz.leedaud.echo.memos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemosHistoryTest {
    private val session = MemosSession("https://example.test", "users/1", "synthetic", "access", "refresh", Long.MAX_VALUE)
    private fun memo(owner: String = session.user, content: String = "synthetic body", attachment: Boolean = true): JSONObject =
        JSONObject().put("name", "memos/abc").put("creator", owner).put("content", content).put("state", "NORMAL")
            .put("createTime", "2026-10-08T00:00:00Z").put("updateTime", "2026-10-08T00:00:00Z")
            .put("attachments", org.json.JSONArray().apply { if (attachment) put(file()) })
    private fun file() = JSONObject().put("name", "attachments/a").put("memo", "memos/abc")
        .put("filename", "fixture.txt").put("type", "text/plain").put("size", 3)
    private fun response(json: JSONObject, cookies: List<String> = emptyList()) = MemosResponse(200, json.toString().toByteArray(), cookies)
    private fun store(): MemosHistoryStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MemosHistoryStore(File(context.cacheDir, "memos-test-${UUID.randomUUID()}"))
    }
    @Test fun httpsOriginAndAccountScopesAreStrict() {
        assertEquals("https://example.test", memosOrigin("https://EXAMPLE.test:443/"))
        listOf("http://example.test", "https://user@example.test", "https://example.test/path", "https://example.test?token=secret").forEach {
            assertThrows(MemosFailure::class.java) { memosOrigin(it) }
        }
        assertNotEquals(session.scope, session.copy(user = "users/2").scope)
        assertNotEquals(session.scope, session.copy(origin = "https://other.test").scope)
    }
    @Test fun otherAccountAndUnsafeResourceAreRejected() {
        assertThrows(MemosFailure::class.java) { MemosMemo.parse(memo("users/2"), session.user) }
        assertThrows(MemosFailure::class.java) { requireResource("memos/../secret", "memos") }
    }
    @Test fun loginVerifiesIdentityAndDoesNotPersistPassword() {
        val routes = mutableListOf<String>()
        var stored: MemosSession? = null
        val client = MemosClient(MemosTransport { _, method, path, _, _, body, _ ->
            routes += "$method $path"
            if (method == "POST") {
                assertEquals("synthetic-password", body!!.getJSONObject("passwordCredentials").getString("password"))
                response(JSONObject().put("user", JSONObject().put("name", "users/1")).put("accessToken", "access")
                    .put("accessTokenExpiresAt", "2030-01-01T00:00:00Z"), listOf("memos_refresh=refresh; Secure; HttpOnly; Path=/"))
            } else response(JSONObject().put("user", JSONObject().put("name", "users/1")))
        }, onSession = { stored = it })
        client.signIn(session.origin, "synthetic", "synthetic-password")
        assertEquals(listOf("POST /api/v1/auth/signin", "GET /api/v1/auth/me"), routes)
        assertNotNull(stored)
        assertFalse(stored.toString().contains("synthetic-password"))
    }
    @Test fun normalArchivedAndPagingOnlyReadOwnedMemos() {
        val paths = mutableListOf<String>()
        val client = MemosClient(MemosTransport { _, method, path, access, refresh, body, _ ->
            assertEquals("GET", method); assertEquals("access", access); assertEquals("", refresh); assertNull(body)
            paths += path
            response(JSONObject().put("memos", org.json.JSONArray().put(memo())).put("nextPageToken", "next page"))
        }, session)
        assertEquals("next page", client.list(false).next)
        client.list(true, "next page")
        assertTrue(paths[0].contains("state=NORMAL")); assertTrue(paths[1].contains("state=ARCHIVED"))
        assertTrue(paths[1].contains("pageToken=next%20page")); assertTrue(paths[0].contains("users%2F1"))
    }
    @Test fun completeCacheIsAccountIsolatedAndDetectsCorruptBytes() {
        val source = memo()
        val client = MemosClient(MemosTransport { _, method, path, _, _, _, _ ->
            assertEquals("GET", method)
            when (path) {
                "/api/v1/memos/abc" -> response(source)
                "/api/v1/attachments/a" -> response(file())
                else -> MemosResponse(200, "abc".toByteArray())
            }
        }, session)
        val store = store()
        val cached = store.cache(session, MemosMemo.parse(source, session.user), client)
        assertEquals("abc", cached.files.single().file.readText())
        assertEquals(1, store.list(session).size)
        assertTrue(store.list(session.copy(user = "users/2")).isEmpty())
        assertNull(store.load(session.copy(user = "users/2"), "memos/abc"))
        cached.files.single().file.writeText("bad")
        assertThrows(MemosFailure::class.java) { store.load(session, "memos/abc") }
        assertThrows(MemosFailure::class.java) { store.list(session.copy(active = false)) }
    }
    @Test fun corruptCacheDoesNotBlockOtherVerifiedRecordsAndIsNotDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "memos-test-${UUID.randomUUID()}")
        val store = MemosHistoryStore(root)
        val first = memo(attachment = false)
        val second = memo(content = "second verified record", attachment = false).put("name", "memos/second")
        val client = MemosClient(MemosTransport { _, _, path, _, _, _, _ ->
            response(if (path.endsWith("/second")) second else first)
        }, session)
        store.cache(session, MemosMemo.parse(first, session.user), client)
        store.cache(session, MemosMemo.parse(second, session.user), client)
        val pointer = File(root, "${session.scope}/index-${xyz.leedaud.echo.notes.digest("memos/abc".toByteArray())}.json")
        pointer.writeText("invalid synthetic index")
        assertEquals(listOf("memos/second"), store.list(session).map { it.memo.name })
        assertTrue(pointer.isFile)
        assertEquals("invalid synthetic index", pointer.readText())
        assertThrows(Exception::class.java) { store.load(session, "memos/abc") }
    }
    @Test fun interruptedAttachmentDoesNotCreateOfflineReceipt() {
        val source = memo()
        val client = MemosClient(MemosTransport { _, _, path, _, _, _, _ -> when(path) {
            "/api/v1/memos/abc" -> response(source)
            "/api/v1/attachments/a" -> response(file())
            else -> MemosResponse(200, "a".toByteArray())
        } }, session)
        val store = store()
        assertThrows(MemosFailure::class.java) { store.cache(session, MemosMemo.parse(source, session.user), client) }
        assertTrue(store.list(session).isEmpty())
    }
    @Test fun changedSourceDuringDownloadDoesNotAdvanceReceipt() {
        val source = memo(attachment = false)
        var reads = 0
        val client = MemosClient(MemosTransport { _, _, _, _, _, _, _ -> response(if (++reads == 1) source else memo(content = "changed", attachment = false)) }, session)
        val store = store()
        assertThrows(MemosFailure::class.java) { store.cache(session, MemosMemo.parse(source, session.user), client) }
        assertTrue(store.list(session).isEmpty())
    }
    @Test fun transportRejectsServerWritesBeforeNetwork() {
        assertThrows(MemosFailure::class.java) {
            MemosHttpTransport().request(session.origin, "DELETE", "/api/v1/memos/abc", "access", "", null, 1024)
        }
        assertThrows(MemosFailure::class.java) {
            MemosHttpTransport().request(session.origin, "POST", "/api/v1/memos", "access", "", JSONObject(), 1024)
        }
    }
    @Test fun refreshRotatesSessionAndChecksOriginalIdentity() {
        var saved: MemosSession? = null
        val calls = mutableListOf<String>()
        val client = MemosClient(MemosTransport { _, method, path, access, refresh, _, _ ->
            calls += "$method $path"
            when(path) {
                "/api/v1/auth/refresh" -> {
                    assertEquals("refresh", refresh)
                    response(JSONObject().put("accessToken", "new-access").put("accessTokenExpiresAt", "2030-01-01T00:00:00Z"), listOf("memos_refresh=new-refresh; Secure; Path=/"))
                }
                "/api/v1/auth/me" -> {
                    assertEquals("new-access", access)
                    response(JSONObject().put("user", JSONObject().put("name", session.user)))
                }
                else -> response(memo(attachment = false))
            }
        }, session.copy(expires = 0), now = { 1000 }, onSession = { saved = it })
        client.get("memos/abc")
        assertEquals("new-refresh", saved!!.refresh)
        assertEquals(listOf("POST /api/v1/auth/refresh", "GET /api/v1/auth/me", "GET /api/v1/memos/abc"), calls)
    }
    @Test fun failedCachePreservesPreviouslyVerifiedVersion() {
        var source = memo(attachment = false)
        val client = MemosClient(MemosTransport { _, _, _, _, _, _, _ -> response(source) }, session)
        val store = store()
        val original = MemosMemo.parse(source, session.user)
        store.cache(session, original, client)
        source = memo(content = "changed", attachment = false)
        assertThrows(MemosFailure::class.java) { store.cache(session, original, client) }
        assertEquals(original.version, store.load(session, original.name)!!.memo.version)
    }
}
