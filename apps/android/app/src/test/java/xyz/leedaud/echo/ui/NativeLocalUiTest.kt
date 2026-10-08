package xyz.leedaud.echo.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import xyz.leedaud.echo.NativeActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import xyz.leedaud.echo.notes.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLocalUiTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()
    @Test fun editingChildDoesNotReplacePinnedParentCard() {
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        // This fixture checks card placement only; it does not read or mutate repository records.
        val parent = Note("pinned-parent", 1, 1, 1, "父笔记", false, emptyList(), null, null, "", "", frozen = true, pinned = true)
        val child = parent.copy(id = "editing-child", body = "子笔记", parentId = parent.id, frozen = false, pinned = false)
        val state = UiState(ready = true, draft = Draft(id = child.id, parentId = parent.id, body = child.body, baseRevision = 1),
            notes = listOf(parent, child), draftSaved = true)
        app.activity.runOnUiThread { app.activity.setContent { MemosTheme(false) {
            NoteList(state, model, {}, {}, {}, "首页", "", {}, {})
        } } }
        app.waitForIdle()
        app.onNodeWithTag("memo-${parent.id}").assertExists()
        app.onNodeWithTag("editor-${child.id}").assertExists()
        app.onNodeWithTag("editor-${parent.id}").assertDoesNotExist()
        app.onNodeWithText("更新").assertExists()
    }
    @Test @Config(sdk = [35]) fun headerReservesTallStatusBarInset() {
        app.activity.runOnUiThread {
            app.activity.setContent {
                MemosTheme(false) { Column { MemosHeader(false, {}, {}, safeInsets = WindowInsets(top = 52.dp)) } }
            }
        }
        app.waitForIdle()
        val density = app.activity.resources.displayMetrics.density
        org.junit.Assert.assertTrue("Navigation must remain below a tall status bar/cutout",
            app.onNodeWithTag("navigation").fetchSemanticsNode().boundsInWindow.top >= 52f * density)
        org.junit.Assert.assertEquals("Safe padding must be counted once", 100f * density,
            app.onNodeWithTag("header-safe-area").fetchSemanticsNode().boundsInWindow.height, .5f)
    }
    @Test fun opensLocalEditorAndSavesWithoutServerOrAccount() {
        app.waitUntil(15000) { app.onAllNodesWithTag("note-editor").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithTag("note-editor").performTextInput("原生合成记录，不需要服务器")
        app.onNodeWithTag("stash").performScrollTo().performClick()
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        app.waitUntil(10000) { model.state.value.draftSaved && !model.state.value.busy }
        app.waitUntil(10000) { !app.onNodeWithTag("save").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        app.onNodeWithTag("save").performScrollTo().performClick()
        app.waitUntil(10000) { model.state.value.notes.any { it.body == "原生合成记录，不需要服务器" } }
        org.junit.Assert.assertEquals(0, model.state.value.jobs.size)
        app.onNodeWithTag("navigation").performClick()
        app.onNodeWithText("主页").performClick()
        app.onNodeWithTag("tab-笔记").performClick()
        val note = model.state.value.notes.single { it.body == "原生合成记录，不需要服务器" }
        app.onNodeWithTag("memo-${note.id}").assertExists()
        app.onNodeWithTag("memo-menu-${note.id}").performClick()
        app.onNodeWithText("编辑", useUnmergedTree = true).onParent().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        app.waitForIdle()
        app.waitUntil(10000) { model.state.value.draft.id == note.id }
        app.waitUntil(10000) { !model.state.value.busy }
        app.onNodeWithTag("note-editor").assertTextEquals(note.body)
        app.onNodeWithTag("kind-待办").performScrollTo().performClick()
        org.junit.Assert.assertTrue(model.state.value.draft.todo)
        app.onNodeWithTag("stash").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        app.waitForIdle()
        app.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
            model.state.value.draftSaved && !model.state.value.busy
        }
        org.junit.Assert.assertTrue(model.state.value.draft.body.startsWith("- [ ] "))
        org.junit.Assert.assertEquals(0, model.state.value.jobs.size)
        org.junit.Assert.assertEquals(note.body, model.state.value.notes.single { it.id == note.id }.body)
    }
}
