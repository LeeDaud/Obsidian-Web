package xyz.leedaud.echo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.leedaud.echo.ui.NoteViewModel

@RunWith(AndroidJUnit4::class)
class MemosInteractionTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()

    @Test fun menuAndDoubleTapEditSavedNoteWithoutDelivery() {
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        app.waitUntil(15000) { model.state.value.ready }
        assertNull("Only run on an unconfigured QA package", model.state.value.target)
        assertTrue("Protect unknown records", model.state.value.notes.all { it.body.startsWith("UI-QA") || it.body == "Android 合成离线记录" })
        assertTrue(model.state.value.jobs.isEmpty())
        model.newNote()
        app.waitUntil(10000) { !model.state.value.busy && model.state.value.draft.body.isBlank() }
        app.onNodeWithTag("note-editor").performTextInput("UI-QA：中文记录")
        app.onNodeWithTag("stash").performScrollTo().performClick()
        app.waitUntil(10000) { model.state.value.draftSaved && !model.state.value.busy }
        app.onNodeWithTag("save").performScrollTo().performClick()
        app.waitUntil(10000) { model.state.value.notes.any { it.body == "UI-QA：中文记录" } && !model.state.value.busy }
        val note = model.state.value.notes.first { it.body == "UI-QA：中文记录" }
        app.onNodeWithTag("memo-menu-${note.id}").performScrollTo().performClick()
        app.onNodeWithText("编辑", useUnmergedTree = true).performClick()
        app.waitUntil(10000) { model.state.value.draft.id == note.id && !model.state.value.busy }
        app.onNodeWithTag("note-editor").assertTextEquals(note.body)
        app.onNodeWithText("取消").performScrollTo().performClick()
        app.waitUntil(10000) { model.state.value.draft.id != note.id && !model.state.value.busy }
        app.onNodeWithTag("memo-body-${note.id}").performScrollTo().performTouchInput { doubleClick() }
        app.waitUntil(10000) { model.state.value.draft.id == note.id && !model.state.value.busy }
        app.onNodeWithTag("note-editor").assertTextEquals(note.body)
        assertTrue(model.state.value.jobs.isEmpty())
    }
}
