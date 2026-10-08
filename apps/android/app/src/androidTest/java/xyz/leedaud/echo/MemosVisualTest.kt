package xyz.leedaud.echo

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import xyz.leedaud.echo.ui.*
import java.io.File
import java.time.Instant
import java.time.YearMonth

/** Synthetic UI snapshots only; no credentials, repository writes or production calls. */
@RunWith(AndroidJUnit4::class)
class MemosVisualTest {
    @get:Rule val app = createAndroidComposeRule<NativeActivity>()

    @Test fun sidebarCalendarUsesFixedChipsAndKeepsSystemSafeArea() {
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        app.waitUntil(15000) { model.state.value.ready }
        assertNull("Only use an unconfigured isolated QA package", model.state.value.target)
        val month = YearMonth.now(shanghai)
        val notes = (1..4).flatMap { day -> (1..day).map { i ->
            val created = month.atDay(day).atStartOfDay(shanghai).toInstant().toEpochMilli()
            Note("calendar-$day-$i", created, created, 1, "合成笔记", false, emptyList(), null, null, "", "")
        } }
        var picked = ""
        for (dark in listOf(false, true)) {
            app.activity.runOnUiThread {
                app.activity.setContent {
                    key(dark) { MemosTheme(dark) {
                        Column(Modifier.fillMaxSize().background(LocalMemosPalette.current.background)) {
                            MemosHeader(false, {}, {})
                            Box(Modifier.width(288.dp).padding(12.dp)) {
                                MemosCalendar(notes, "", compact = true) { picked = it }
                            }
                        }
                    } }
                }
            }
            app.waitForIdle()
            val baseline = app.onNodeWithTag("calendar-chip-${month.atDay(5)}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
            for (day in 1..4) assertEquals("Notes must not grow a date row", baseline,
                app.onNodeWithTag("calendar-chip-${month.atDay(day)}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, .5f)
            app.onNodeWithText("·").assertDoesNotExist()
            val statusInset = androidx.core.view.ViewCompat.getRootWindowInsets(app.activity.window.decorView)!!
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            assertTrue("The test device must expose its status bar", statusInset > 0)
            assertTrue(app.onNodeWithTag("navigation").fetchSemanticsNode().boundsInWindow.top >= statusInset)
            app.onNodeWithContentDescription("${month.atDay(1)}，1条笔记").performClick()
            assertEquals(month.atDay(1).toString(), picked)
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2000)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            val width = app.activity.resources.configuration.screenWidthDp
            val file = File(app.activity.externalCacheDir, "ui-visual/detail-calendar-${if (dark) "dark" else "light"}-$width.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun originalMemosLayoutAndStatusSnapshots() {
        val model = androidx.lifecycle.ViewModelProvider(app.activity)[NoteViewModel::class.java]
        app.waitUntil(15000) { model.state.value.ready }
        assertNull("Only use an unconfigured isolated QA package", model.state.value.target)
        val time = Instant.parse("2026-10-08T02:00:00Z").toEpochMilli()
        val bodies = listOf("今天的想法\n\n记录一个清晰的念头。 #记录", "- [ ] 整理今天的笔记\n- [x] 留下一个想法", "**一条已经投递的记录**\n\n内容仍保留在本机。")
        val notes = bodies.mapIndexed { i, body -> Note("visual-$i", time, time, 1, body, i == 1, emptyList(), null, null, "", "", frozen = i > 0) }
        val target = Target("visual-only", "qa", "synthetic", "main", 1, false)
        val jobs = listOf(Delivery("visual-pending", notes[1].id, 1, target, notes[1]),
            Delivery("visual-verified", notes[2].id, 1, target, notes[2], state = "verified",
                receipt = Receipt("a".repeat(40), "00_Inbox/20261008-100000.md", time, emptyMap())))
        val state = UiState(ready = true, draft = Draft(id = "visual-draft"), notes = notes, jobs = jobs, draftSaved = true)
        fun display(dark: Boolean, page: String = "首页") {
            app.activity.runOnUiThread {
                app.activity.setContent {
                    key(dark, page) { MemosTheme(dark) {
                        Column(Modifier.fillMaxSize().background(LocalMemosPalette.current.background)) {
                            MemosHeader(false, {}, {})
                            NoteList(state, model, {}, {}, {}, page, "", {}, {})
                        }
                    }
                    }
                }
            }
            app.waitForIdle()
        }
        fun snapshot(name: String) {
            app.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2000)
            val width = app.activity.resources.configuration.screenWidthDp
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("The emulator must render a real screen", bitmap)
            val file = File(app.activity.externalCacheDir, "ui-visual/native-$name-$width.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        display(false)
        app.onNodeWithTag("save").assertIsNotEnabled()
        app.onNodeWithText("已保存").assertExists()
        app.onNodeWithText("投递中").assertExists()
        app.onNodeWithText("已投递").assertExists()
        snapshot("home")
        app.onNodeWithTag("memo-menu-visual-2").performClick()
        app.onNodeWithText("续写", useUnmergedTree = true).assertExists()
        app.onNodeWithText("投递当前版本", useUnmergedTree = true).assertDoesNotExist()
        snapshot("verified-menu")
        display(true)
        snapshot("home-dark")
        display(false, "日历")
        snapshot("calendar")
        assertEquals(0, model.state.value.jobs.size)
    }
}
