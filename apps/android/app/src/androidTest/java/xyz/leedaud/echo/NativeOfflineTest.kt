package xyz.leedaud.echo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import xyz.leedaud.echo.ui.NoteViewModel
import xyz.leedaud.echo.storage.CredentialStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated test device. No GitHub configuration, login or delivery. */
@RunWith(AndroidJUnit4::class)
class NativeOfflineTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()
    @Test fun localEditorCanStashAndSaveWithoutAccount() {
        app.waitUntil(timeoutMillis = 15000) { app.onAllNodesWithTag("note-editor").fetchSemanticsNodes().isNotEmpty() }
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        org.junit.Assert.assertNull("Only run on an unconfigured isolated test device", model.state.value.target)
        org.junit.Assert.assertTrue("Do not modify existing records or drafts", model.state.value.notes.isEmpty()
            && model.state.value.jobs.isEmpty() && model.state.value.draft.body.isBlank() && model.state.value.draft.attachments.isEmpty())
        val secrets = CredentialStore(app.activity)
        secrets.save("qa-synthetic-credential", "synthetic_not_a_github_token")
        org.junit.Assert.assertEquals("synthetic_not_a_github_token", secrets.read("qa-synthetic-credential"))
        org.junit.Assert.assertFalse(java.io.File(app.activity.filesDir, "secrets/qa-synthetic-credential")
            .readBytes().toString(Charsets.UTF_8).contains("synthetic_not_a_github_token"))
        app.onNodeWithTag("note-editor").performTextInput("Android 合成离线记录")
        app.onNodeWithTag("stash").performScrollTo().performClick()
        app.waitUntil(timeoutMillis = 5000) { model.state.value.draftSaved && !model.state.value.busy }
        app.waitUntil(timeoutMillis = 5000) { !app.onNodeWithTag("save").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        app.onNodeWithTag("save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        app.waitUntil(timeoutMillis = 5000) { model.state.value.notes.any { it.body == "Android 合成离线记录" } }
        org.junit.Assert.assertEquals(0, model.state.value.jobs.size)
    }
}
