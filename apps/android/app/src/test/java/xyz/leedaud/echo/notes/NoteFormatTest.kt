package xyz.leedaud.echo.notes

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NoteFormatTest {
    private val time = Instant.parse("2026-10-08T06:30:00Z").toEpochMilli()
    private fun note(body: String, todo: Boolean = false, attachments: List<AttachmentRef> = emptyList()) =
        Note("id", time, time, 1, body, todo, attachments, null, null, "notes/id/1.md", "")
    @Test fun timestampUsesShanghai() { assertEquals("20261008-143000", timestamp(time)) }
    @Test fun checkboxConversionPreservesTextAndCheckedItems() {
        val text = "第一行\n- [x] 已完成\n\n第二行"
        assertEquals("- [ ] 第一行\n- [x] 已完成\n\n- [ ] 第二行", NoteFormat.toTodo(text))
        assertEquals("第一行\n已完成\n\n第二行", NoteFormat.fromTodo(NoteFormat.toTodo(text)))
    }
    @Test fun plainTextDoesNotGetMetadata() {
        assertEquals("正文\n**加粗**", NoteFormat.markdown(note("正文\n**加粗**"), "00_Inbox/20261008-143000.md"))
    }
    @Test fun exportsTodoAndSingleParentLink() {
        val result = NoteFormat.markdown(note("- [x] 已完成", true), "00_Inbox/20261008-143000.md", "00_Inbox/20261007-120000.md")
        assertTrue(result.startsWith("---\ntype: todo\nstatus: done\n"))
        assertTrue(result.contains("source: echo-android"))
        assertEquals(1, Regex("续写自：").findAll(result).count())
        assertTrue(result.endsWith("续写自：[[20261007-120000]]"))
    }
    @Test fun attachmentsFollowFinalNameAndStayStable() {
        val a = AttachmentRef("a", "attachments/a.png", "相片.png", "image/png", 1, "h", time, "png")
        val b = a.copy(id = "b")
        val result = NoteFormat.markdown(note("![](echo-attachment:a)", attachments = listOf(b, a)), "00_Inbox/20261008-143001.md")
        assertTrue(result.contains("../attachments/20261008-143001/20261008-143000.png"))
        assertTrue(result.contains("../attachments/20261008-143001/20261008-143000-02.png"))
        assertFalse(result.contains("echo-attachment:"))
        assertEquals(2, Regex("!\\[\\]").findAll(result).count())
    }
    @Test fun invalidPathsAndOverLimitTextFailBeforePublishing() {
        assertThrows(IllegalArgumentException::class.java) { NoteFormat.markdown(note("text"), "../secrets.md") }
        assertThrows(IllegalArgumentException::class.java) { NoteFormat.validate("中".repeat(100000), emptyList()) }
    }
}
