package xyz.leedaud.echo.update

import java.net.HttpURLConnection
import java.net.URI
import java.io.InputStream

/** No authentication, cookies or notebook credentials are ever attached to update requests. */
class UpdateHttp(private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }
    fun read(url: String, max: Long, cancelled: () -> Boolean = { false }, consume: (InputStream, Long) -> Unit) {
        var address = url
        val deadline = System.nanoTime() + (if (max > 256 * 1024) 600 else 45) * 1_000_000_000L
        try {
            for (hop in 0..4) {
                check(!cancelled()) { "已取消更新下载" }
                val uri = if (address == UpdatePolicy.LATEST && hop == 0) URI(address) else UpdatePolicy.downloadUrl(address, hop > 0)
                val current = open(uri)
                connection = current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15000; current.readTimeout = 15000
                current.setRequestProperty("User-Agent", "Echo-Android-Updater")
                current.setRequestProperty("Accept", if (address == UpdatePolicy.LATEST) "application/vnd.github+json" else "application/octet-stream")
                val status = current.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(url != UpdatePolicy.LATEST) { "拒绝更新接口重定向" }
                    val target = current.getHeaderField("Location") ?: error("下载重定向缺少目标")
                    address = uri.resolve(target).toString()
                    UpdatePolicy.downloadUrl(address, true)
                    current.disconnect(); continue
                }
                require(status == 200) { if (status == 404) "尚无可用的正式更新发布" else "更新服务暂不可用（$status），请重试" }
                require(current.contentLengthLong <= max) { "更新响应超过容量限制" }
                current.inputStream.use { original ->
                    val bounded = object : java.io.FilterInputStream(original) {
                        var total = 0L
                        private fun allowed() { check(!cancelled()) { "已取消更新下载" }; check(System.nanoTime() < deadline) { "更新读取超时，请重试" } }
                        override fun read(): Int { allowed(); val value = `in`.read(); if (value >= 0) { total++; require(total <= max) { "更新响应超过容量限制" } }; return value }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            allowed(); val count = `in`.read(buffer, offset, length)
                            if (count > 0) { total += count; require(total <= max) { "更新响应超过容量限制" } }
                            return count
                        }
                    }
                    consume(bounded, current.contentLengthLong)
                }
                check(!cancelled()) { "已取消更新下载" }
                return
            }
            error("下载重定向次数过多")
        } finally { connection?.disconnect(); connection = null }
    }
    fun json(url: String, cancelled: () -> Boolean = { false }): org.json.JSONObject {
        val output = java.io.ByteArrayOutputStream()
        read(url, 256 * 1024, cancelled) { input, _ ->
            val buffer = ByteArray(16384)
            while (true) {
                check(!cancelled()) { "已取消更新检查" }
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 256 * 1024) { "更新清单过大" }
                output.write(buffer, 0, count)
            }
        }
        return org.json.JSONObject(output.toString("UTF-8"))
    }
}
