package xyz.leedaud.echo.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.leedaud.echo.notes.newId
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalUiPickedFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun store(limit: Long = 25L * 1024 * 1024): LocalUiPickedFiles = LocalUiPickedFiles(context,
        File(context.cacheDir, "picked-test-${newId()}"), limit)
    @Test fun copiedBytesUseOpaqueNamesAndAreVerifiedBeforeRead() {
        val store = store()
        val value = store.store(ByteArrayInputStream("synthetic".toByteArray()), "../选中.txt", "Text/Plain; charset=UTF-8")
        assertTrue(value.getString("name").startsWith("picked/"))
        assertFalse(value.toString().contains(context.filesDir.path)); assertFalse(value.has("sha256"))
        val (mime, file) = store.resolve(value.getString("name"))
        assertEquals("text/plain", mime); assertEquals("synthetic", file.readText())
        assertFalse(value.getString("filename").contains('/'))
        file.writeText("corrupted")
        assertThrows(LocalUiFailure::class.java) { store.resolve(value.getString("name")) }
    }
    @Test fun overLimitDoesNotReturnAReadableResource() {
        val store = store(3)
        assertThrows(LocalUiFailure::class.java) { store.store(ByteArrayInputStream("four".toByteArray()), "fixture.txt", "text/plain") }
    }
    @Test fun arbitraryUrisPathsAndExcessiveSelectionsAreRejected() {
        val store = store()
        for (uri in listOf("file:///private/secret", "https://example.test/file", "data:text/plain,x")) {
            assertThrows(LocalUiFailure::class.java) { store.copy(listOf(Uri.parse(uri))) }
        }
        assertThrows(LocalUiFailure::class.java) { store.copy(List(101) { Uri.parse("content://synthetic/file") }) }
        assertThrows(LocalUiFailure::class.java) { store.resolve("picked/../../secret") }
        assertEquals(0, store.copy(emptyList()).getJSONArray("files").length())
    }
}
