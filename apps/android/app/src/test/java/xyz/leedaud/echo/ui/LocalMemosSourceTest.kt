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
