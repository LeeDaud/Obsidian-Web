package xyz.leedaud.echo.update

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import org.junit.Test
import org.junit.Assert.*

class UpdateHttpTest {
    private class Response(url: String, private val status: Int, private val target: String? = null,
        private val body: ByteArray = "{}".toByteArray(), private val declared: Long = body.size.toLong()) : HttpURLConnection(URL(url)) {
        var closed = false
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = if (name == "Location") target else null
        override fun getContentLengthLong() = declared
        override fun getInputStream() = ByteArrayInputStream(body)
    }
    private val url = "https://github.com/LeeDaud/Obsidian-Web/releases/download/v1/Echo.apk"
    @Test fun followsOnlyBoundedOfficialAssetRedirectsWithoutCredentials() {
        val calls = mutableListOf<Response>()
        val reader = UpdateHttp { uri -> Response(uri.toString(), if (calls.isEmpty()) 302 else 200,
            "https://release-assets.githubusercontent.com/fixture?signature=temporary").also { calls += it } }
        assertEquals("{}", reader.json(url).toString())
        assertEquals(2, calls.size)
        calls.forEach { assertFalse(it.instanceFollowRedirects); assertNull(it.getRequestProperty("Authorization")); assertNull(it.getRequestProperty("Cookie")); assertTrue(it.closed) }
        val bad = UpdateHttp { uri -> Response(uri.toString(), 302, "https://evil.test/download.apk") }
        assertThrows(IllegalArgumentException::class.java) { bad.json(url) }
        val apiRedirect = UpdateHttp { uri -> Response(uri.toString(), 302, url) }
        assertThrows(IllegalArgumentException::class.java) { apiRedirect.json(UpdatePolicy.LATEST) }
        var hops = 0
        val loop = UpdateHttp { uri -> hops++; Response(uri.toString(), 302, url) }
        assertThrows(IllegalStateException::class.java) { loop.json(url) }
        assertEquals(5, hops)
    }
    @Test fun cancelledRequestsOversizedAndTruncatedMetadataCannotSucceed() {
        var opened = false
        val cancelled = UpdateHttp { uri -> opened = true; Response(uri.toString(), 200) }
        assertThrows(IllegalStateException::class.java) { cancelled.json(url) { true } }
        assertFalse(opened)
        val huge = UpdateHttp { uri -> Response(uri.toString(), 200, declared = 256 * 1024 + 1L) }
        assertThrows(IllegalArgumentException::class.java) { huge.json(url) }
        val unbounded = UpdateHttp { uri -> Response(uri.toString(), 200, body = ByteArray(256 * 1024 + 1), declared = -1) }
        assertThrows(IllegalArgumentException::class.java) { unbounded.json(url) }
        val invalid = UpdateHttp { uri -> Response(uri.toString(), 200, body = "{".toByteArray()) }
        assertThrows(org.json.JSONException::class.java) { invalid.json(url) }
        val unavailable = UpdateHttp { uri -> Response(uri.toString(), 429) }
        assertThrows(IllegalArgumentException::class.java) { unavailable.json(url) }
    }
}
