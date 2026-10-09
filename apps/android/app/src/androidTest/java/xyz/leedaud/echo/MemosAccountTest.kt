package xyz.leedaud.echo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.leedaud.echo.memos.*
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import xyz.leedaud.echo.ui.*
import org.json.JSONObject

/** Isolated QA only; synthetic session, never a real server request. */
@RunWith(AndroidJUnit4::class)
class MemosAccountTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()
    @Test fun optionalHistoryAndEncryptedDisconnectLeaveLocalNotesAlone() {
        app.waitUntil(15000) { app.onAllNodesWithTag("note-editor").fetchSemanticsNodes().isNotEmpty() }
        val session = MemosSession("https://example.test", "users/qa", "synthetic", "synthetic-access", "synthetic-refresh", Long.MAX_VALUE)
        val store = MemosSessionStore(app.activity)
        assertNull("QA session must not be configured", store.read())
        store.save(session)
        assertEquals(session, store.read())
        val encrypted = File(app.activity.filesDir, "secrets/memos-session-v1").readBytes().toString(Charsets.UTF_8)
        assertFalse(encrypted.contains("synthetic-access")); assertFalse(encrypted.contains("synthetic-refresh"))
        store.disconnect(session)
        assertNull(store.read())
        app.onNodeWithTag("navigation").performClick()
        app.onNodeWithTag("tab-memos-history").assertDoesNotExist()
        app.onNodeWithTag("tab-设置").performClick()
        app.onNodeWithText("连接原 Memos").performClick()
        app.onNodeWithTag("memos-username").assertExists()
        app.onNodeWithTag("memos-password").assertExists()
        app.onNodeWithText("登录并读取历史").assertIsNotEnabled()
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        val before = model.state.value
        val source = MemosMemo.parse(JSONObject().put("name", "memos/fixture").put("creator", "users/qa")
            .put("content", "UI-QA history source").put("state", "NORMAL").put("createTime", "2026-10-08T00:00:00Z"), "users/qa")
        model.adoptMemosCopy(MemosCachedMemo(source, emptyList()), session.origin)
        app.waitUntil(10000) { !model.state.value.busy && model.state.value.draft.id != before.draft.id && model.state.value.draft.body.startsWith("UI-QA history source") }
        assertNotEquals(before.draft.id, model.state.value.draft.id)
        assertTrue(model.state.value.draft.body.contains("https://example.test/memos/fixture"))
        assertEquals(before.jobs, model.state.value.jobs)
        assertEquals(before.notes, model.state.value.notes)
        assertNull(model.state.value.draft.parentId)
        val history = androidx.lifecycle.ViewModelProvider(app.activity)[MemosViewModel::class.java]
        for (width in listOf(320, 390)) {
            app.activity.runOnUiThread { app.activity.setContent { MemosTheme(false) {
                Column(Modifier.width(width.dp).fillMaxHeight()) { MemosHeader(false, {}, {}); MemosHistoryPage(MemosState(ready = true), history, copy = { _, _ -> }) }
            } } }
            app.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2000)
            val bitmap = captureQaScreen()
            assertNotNull(bitmap)
            File(app.activity.getExternalFilesDir(null), "memos-login-$width.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap!!.recycle()
        }
    }
}
