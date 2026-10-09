package xyz.leedaud.echo.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import xyz.leedaud.echo.memos.*
import xyz.leedaud.echo.notes.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnifiedFeedTest {
    private val account = MemosAccount("https://example.test", "users/1", "synthetic", "scope-one")
    private fun memo(id: String, creator: String = "users/1", archived: Boolean = false, pinned: Boolean = false) =
        MemosMemo.parse(JSONObject().put("name", "memos/$id").put("creator", creator).put("content", "same body")
            .put("createTime", "2026-10-09T00:00:00Z").put("pinned", pinned).put("state", if (archived) "ARCHIVED" else "NORMAL"), creator)
    private val local = Note("abc", 1, 1, 1, "same body", false, emptyList(), null, null, "", "")
    @Test fun ownedRemoteAndLocalRemainSeparateAndPinnedSortsFirst() {
        val history = MemosState(account = account, items = listOf(memo("abc"), memo("abc"), memo("pin", pinned = true), memo("foreign", "users/2"), memo("arch", archived = true)))
        val feed = unifiedFeed(listOf(local), history, false)
        assertEquals(3, feed.size)
        assertEquals("memos/pin", feed.first().remote!!.name)
        assertEquals(local, feed.last().local)
        assertEquals(3, feed.map { it.key }.distinct().size)
    }
    @Test fun disconnectedAccountCannotExposeAnyRemoteCache() {
        val history = MemosState(items = listOf(memo("abc")), cached = listOf(MemosCachedMemo(memo("cache"), emptyList())), offline = true)
        assertEquals(listOf("local:abc"), unifiedFeed(listOf(local), history, false).map { it.key })
    }
    @Test fun offlineOnlyShowsCompletedOwnedSnapshots() {
        val history = MemosState(account = account, items = listOf(memo("online")), offline = true,
            cached = listOf(MemosCachedMemo(memo("cache"), emptyList()), MemosCachedMemo(memo("foreign", "users/2"), emptyList())))
        assertEquals(listOf("memos/cache"), unifiedFeed(emptyList(), history, false).map { it.remote!!.name })
    }
    @Test fun archivedFeedDoesNotLeakNormalRecordsAndIdentityIncludesAccount() {
        val history = MemosState(account = account, items = listOf(memo("abc"), memo("arch", archived = true)))
        val first = unifiedFeed(emptyList(), history, true).single()
        assertEquals("memos/arch", first.remote!!.name)
        assertNotEquals(first.key, unifiedFeed(emptyList(), history.copy(account = account.copy(scope = "scope-two")), true).single().key)
    }
}
