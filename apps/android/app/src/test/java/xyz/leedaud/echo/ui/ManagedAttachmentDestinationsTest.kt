package xyz.leedaud.echo.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedAttachmentDestinationsTest {
    private val origin = "https://memos.example.test"
    private val mapping = mapOf("attachments/source_1" to ("图 片.png" to "echo-attachment:copied"))
    private fun spans(source: String, destination: String) = JSONArray().put(JSONObject()
        .put("start", source.indexOf(destination)).put("end", source.indexOf(destination) + destination.length).put("name", "attachments/source_1"))

    @Test fun parsedSpansPreserveCodeTitlesAndUnicodeOffsets() {
        val destination = "/file/attachments/source_1?download=1#view"
        val source = "😀 ![图]($destination \"题目\")\n\n`$destination`"
        assertEquals("😀 ![图](echo-attachment:copied \"题目\")\n\n`$destination`",
            ManagedAttachmentDestinations.rewrite(source, spans(source, destination), mapping, origin))
    }
    @Test fun namedAbsoluteAndEscapedDestinationsAreVerified() {
        val destination = "$origin/file/attachments/source_1/%E5%9B%BE%20%E7%89%87.png?x=1&amp;y=2"
        val source = "![图]($destination)"
        assertEquals("![图](echo-attachment:copied)", ManagedAttachmentDestinations.rewrite(source, spans(source, destination), mapping, origin))
        val defaultPort = "https://MEMOS.EXAMPLE.TEST:443/file/attachments/source_1"
        assertEquals("![图](echo-attachment:copied)", ManagedAttachmentDestinations.rewrite("![图]($defaultPort)", spans("![图]($defaultPort)", defaultPort), mapping, origin))
    }
    @Test fun foreignUnknownWrongFilenameAndOverlappingSpansAreRejected() {
        for (destination in listOf("https://foreign.test/file/attachments/source_1", "/file/attachments/source_12", "/file/attachments/source_1/wrong.png", "https://user:pass@memos.example.test/file/attachments/source_1")) {
            val source = "![图]($destination)"
            assertThrows(LocalUiFailure::class.java) { ManagedAttachmentDestinations.rewrite(source, spans(source, destination), mapping, origin) }
        }
        val source = "![图](/file/attachments/source_1)"
        val overlapping = spans(source, "/file/attachments/source_1")
        overlapping.put(overlapping.getJSONObject(0))
        assertThrows(LocalUiFailure::class.java) { ManagedAttachmentDestinations.rewrite(source, overlapping, mapping, origin) }
        assertThrows(LocalUiFailure::class.java) { ManagedAttachmentDestinations.rewrite(source, spans(source, "/file/attachments/source_1"), emptyMap(), origin) }
    }
}
