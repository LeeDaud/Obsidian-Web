package xyz.leedaud.echo.update

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*

class UpdatePolicyTest {
    @Test fun updateErrorsNeverExposeLocalFilesOrSignedDownloadUrls() {
        for (error in listOf(java.io.IOException("/data/user/0/private.apk PRIVATE"),
            IllegalArgumentException("https://release-assets.githubusercontent.com/PRIVATE?signature=SECRET"),
            IllegalStateException("C:\\private\\SECRET.apk"))) {
            assertFalse(UpdatePolicy.failure(error).contains("PRIVATE"))
            assertFalse(UpdatePolicy.failure(error).contains("SECRET"))
        }
        assertEquals("更新签名不匹配", UpdatePolicy.failure(IllegalArgumentException("更新签名不匹配")))
    }
    private val sha = "a".repeat(64)
    private fun release() = JSONObject().put("draft", false).put("prerelease", false).put("html_url", "${UpdatePolicy.PAGE}/tag/v1.0-build11")
        .put("assets", JSONArray().put(JSONObject().put("name", "Echo-build11.apk").put("size", 100)
            .put("browser_download_url", "https://github.com/LeeDaud/Obsidian-Web/releases/download/v1.0-build11/Echo-build11.apk")))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("applicationId", UpdatePolicy.PACKAGE)
        .put("versionCode", 11).put("versionName", "1.0").put("minSdk", 26).put("apkAssetName", "Echo-build11.apk")
        .put("size", 100).put("sha256", sha).put("releaseNotes", "合成更新")
    @Test fun stableManifestBindsExactReleaseAssetAndBytes() {
        val value = UpdatePolicy.parse(manifest(), release())
        assertEquals(11, value.code.toInt()); assertEquals(100L, value.size); assertEquals(sha, value.sha256)
        assertNull(UpdatePolicy.asset(release(), "echo-update.json"))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest(), release().put("prerelease", true)) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest(), release().put("draft", true)) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("size", 99), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("sha256", "invalid"), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("applicationId", "other"), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("schemaVersion", 2), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("size", UpdatePolicy.LIMIT + 1), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("versionCode", 11.5), release()) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest().put("versionCode", "11"), release()) }
    }
    @Test fun maliciousDownloadUrlsCannotEscapeTheOfficialChannel() {
        for (url in listOf("http://github.com/LeeDaud/Obsidian-Web/releases/download/x/a.apk", "https://github.com.evil.test/LeeDaud/Obsidian-Web/releases/download/x/a.apk",
            "https://evil@github.com/LeeDaud/Obsidian-Web/releases/download/x/a.apk", "https://github.com:8443/LeeDaud/Obsidian-Web/releases/download/x/a.apk",
            "https://github.com/LeeDaud/Echo/releases/download/x/a.apk", "https://github.com/LeeDaud/Obsidian-Web/releases/download/%2E%2E/a.apk",
            "https://release-assets.githubusercontent.com.evil.test/file", "https://objects.githubusercontent.com/file", "file:///private.apk")) {
            assertThrows(url, IllegalArgumentException::class.java) { UpdatePolicy.downloadUrl(url) }
        }
        assertEquals("release-assets.githubusercontent.com", UpdatePolicy.downloadUrl("https://release-assets.githubusercontent.com/file?signature=public-temporary", true).host)
        val wrong = release(); wrong.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/LeeDaud/Obsidian-Web/releases/download/other/Echo-build11.apk")
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parse(manifest(), wrong) }
    }
    @Test fun differentSignersDebugPackagesDowngradesAndManifestMismatchAreRejected() {
        val value = UpdatePolicy.parse(manifest(), release())
        fun verify(packageName: String = UpdatePolicy.PACKAGE, code: Long = 11, version: String = "1.0", sdk: Int = 35,
            debug: Boolean = false, signers: Set<String> = setOf(sha), installed: Long = 10) =
            UpdatePolicy.archive(value, packageName, code, version, 26, debug, signers, setOf(sha), installed, sdk)
        verify()
        assertThrows(IllegalArgumentException::class.java) { verify(packageName = "other") }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 12) }
        assertThrows(IllegalArgumentException::class.java) { verify(version = "different") }
        assertThrows(IllegalArgumentException::class.java) { verify(sdk = 25) }
        assertThrows(IllegalArgumentException::class.java) { verify(debug = true) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf("b".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verify(installed = 11) }
    }
}
