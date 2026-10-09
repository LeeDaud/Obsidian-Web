package xyz.leedaud.echo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import xyz.leedaud.echo.ui.*
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.memos.*

internal fun captureQaScreen(): android.graphics.Bitmap {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    return automation.takeScreenshot() ?: android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p"))
        .use { android.graphics.BitmapFactory.decodeStream(it) } ?: error("The emulator did not render a screen")
}

@RunWith(AndroidJUnit4::class)
class UnifiedHomeTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()
    @Test fun remoteHomeCardsAndContinuationPreserveLocalQueue() {
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        app.waitUntil(15000) { model.state.value.ready && !model.state.value.busy }
        assertNull(model.state.value.target)
        val before = model.state.value
        val source = MemosMemo.parse(JSONObject().put("name", "memos/home").put("creator", "users/qa").put("content", "原文短记录")
            .put("createTime", "2026-10-09T00:00:00Z").put("state", "NORMAL"), "users/qa")
        val local = Note("short-local", 1, 1, 1, "短笔记", false, emptyList(), null, null, "", "")
        val history = MemosState(ready = true, account = MemosAccount("https://example.test", "users/qa", "synthetic", "qa"), items = listOf(source))
        var opened: MemosMemo? = null
        for (dark in listOf(false, true)) for (width in listOf(320, 390, 430)) {
            app.activity.runOnUiThread { app.activity.setContent { key("$dark-$width") { MemosTheme(dark) {
                Column(Modifier.width(width.dp).fillMaxHeight().background(LocalMemosPalette.current.background)) {
                    MemosHeader(false, {}, {})
                    NoteList(UiState(ready = true, notes = listOf(local), draftSaved = true), model, {}, {}, {}, "首页", "", {}, {}, history,
                        { opened = it }, { model.continueMemos(it, history.account!!.origin) }, {})
                }
            } } } }
            app.waitForIdle()
            app.onNodeWithTag("remote-memo-memos/home").assertExists()
            app.onNodeWithTag("remote-body-memos/home").performScrollTo()
            val body = app.onNodeWithTag("remote-body-memos/home").fetchSemanticsNode().boundsInRoot
            assertEquals("Single-line CSS line box", 24f, body.height, 1f)
            val card = app.onNodeWithTag("remote-memo-memos/home").fetchSemanticsNode().boundsInRoot
            assertEquals("Web card body bottom inset", 13f, card.bottom - body.bottom, 1f)
            app.onNodeWithTag("memo-short-local").assertExists()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2000)
            val bitmap = captureQaScreen()
            File(app.activity.getExternalFilesDir(null), "unified-home-${if (dark) "dark" else "light"}-$width.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        app.onNodeWithTag("remote-detail-memos/home").performScrollTo().performClick()
        assertEquals(source.name, opened!!.name)
        app.onNodeWithTag("remote-body-memos/home").performTouchInput { doubleClick() }
        app.waitUntil(10000) { !model.state.value.busy && model.state.value.draft.body.contains("续写自") }
        assertFalse(model.state.value.draft.body.contains(source.body))
        assertTrue(model.state.value.draft.body.contains("https://example.test/memos/home"))
        assertNull(model.state.value.draft.parentId)
        assertEquals(before.jobs, model.state.value.jobs)
        assertEquals(before.notes, model.state.value.notes)
    }
}
