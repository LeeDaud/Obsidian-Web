package xyz.leedaud.echo.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LocalWebView(private val activity: Activity, private val backend: LocalUiBackend, private val platform: LocalUiPlatform, initialResource: String? = null) : WebView(activity) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val waiting = AtomicInteger()
    private var channel: WebMessagePort? = null
    private var generation = 0
    private fun reply(response: JSONObject, epoch: Int) {
        val raw = response.toString()
        main.post {
            if (epoch != generation) return@post
            if (raw.length <= 60000) channel?.postMessage(WebMessage(raw))
            else {
                val parts = mutableListOf<String>(); var start = 0
                while (start < raw.length) {
                    var end = minOf(start + 60000, raw.length)
                    if (end < raw.length && raw[end - 1].isHighSurrogate()) end--
                    parts.add(raw.substring(start, end)); start = end
                }
                if (parts.size > 512) { channel?.postMessage(WebMessage(JSONObject().put("id", response.optString("id")).put("error", "数据较大，请分批读取").toString())); return@post }
                parts.forEachIndexed { index, chunk -> channel?.postMessage(WebMessage(JSONObject().put("id", response.optString("id")).put("index", index).put("total", parts.size).put("chunk", chunk).toString())) }
            }
        }
    }
    companion object {
        const val ORIGIN = "https://echo-app.local"
        fun trusted(uri: Uri): Boolean = uri.scheme == "https" && uri.host == "echo-app.local" && uri.port == -1 && uri.userInfo == null
        fun safePath(uri: Uri): Boolean = trusted(uri) && !uri.path.orEmpty().contains('\\') && uri.path.orEmpty().split('/').none { it == "." || it == ".." }
        fun trustedDocument(uri: Uri): Boolean = safePath(uri) && !uri.path.orEmpty().startsWith("/file/")
            && !uri.path.orEmpty().startsWith("/assets/") && !uri.path.orEmpty().substringAfterLast('/').contains('.')
        internal fun attachmentResponse(mime: String, file: File, range: String?): WebResourceResponse {
            val size = file.length()
            val headers = mutableMapOf("Content-Security-Policy" to "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:;",
                "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store", "Accept-Ranges" to "bytes")
            var start = 0L
            var end = size - 1
            if (range != null) {
                val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim())
                val first = match?.groupValues?.get(1).orEmpty()
                val last = match?.groupValues?.get(2).orEmpty()
                val suffix = if (first.isEmpty()) last.toLongOrNull() else null
                start = if (first.isEmpty()) (size - (suffix ?: 0)).coerceAtLeast(0) else first.toLongOrNull() ?: -1
                end = if (first.isEmpty() || last.isEmpty()) size - 1 else (last.toLongOrNull() ?: -1).coerceAtMost(size - 1)
                if (match == null || size == 0L || (first.isEmpty() && (suffix == null || suffix <= 0)) || start < 0 || start >= size || end < start) {
                    headers["Content-Range"] = "bytes */$size"
                    headers["Content-Length"] = "0"
                    return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
                }
                headers["Content-Range"] = "bytes $start-$end/$size"
            }
            val length = (end - start + 1).coerceAtLeast(0)
            headers["Content-Length"] = length.toString()
            // Chromium seeks intercepted streams itself; retain the prefix and expose the original size until that seek.
            return WebResourceResponse(mime, null, if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content",
                headers, BoundedAttachmentStream(file.inputStream(), (end + 1).coerceAtLeast(0), size))
        }
    }
    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                if (trustedDocument(Uri.parse(view.url ?: ""))) platform.choose(params, callback) else false
            override fun onPermissionRequest(request: PermissionRequest) { platform.microphone(request) }
        }
        if (xyz.leedaud.echo.BuildConfig.DEBUG) setWebContentsDebuggingEnabled(true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!request.isForMainFrame) return true
                if (trustedDocument(uri)) return false
                if (uri.scheme == "echo" && uri.host == "memos") {
                    val id = uri.path.orEmpty().removePrefix("/")
                    if (Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(id)) loadUrl("$ORIGIN/memos/$id")
                    return true
                }
                if (trusted(uri) && uri.path.orEmpty().startsWith("/file/attachments/") && request.hasGesture()) {
                    val parts = uri.path.orEmpty().split('/')
                    if (parts.size == 5) platform.handle("platform.view", JSONObject().put("name", "attachments/${parts[3]}").put("filename", parts[4])) { _, _ -> }
                    return true
                }
                if (trusted(uri)) return true
                if (request.hasGesture() && uri.scheme in setOf("https", "mailto", "tel", "obsidian")) {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.scheme == "https" && request.url.userInfo == null && request.url.port == -1
                    && request.url.host in setOf("tiles.openfreemap.org", "tile.openstreetmap.org", "nominatim.openstreetmap.org") && request.method == "GET") return null
                if (!safePath(request.url) || request.method != "GET") return denied()
                val path = request.url.path.orEmpty().removePrefix("/")
                if (path.startsWith("file/attachments/")) {
                    val parts = path.split('/')
                    if (parts.size != 4) return denied()
                    return runCatching {
                        val (mime, file) = backend.attachmentFile("attachments/${parts[2]}")
                        attachmentResponse(mime, file, request.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value)
                    }.getOrElse { denied() }
                }
                val asset = if (path.isBlank() || !path.substringAfterLast('/').contains('.')) "index.html" else path
                return runCatching {
                    val mime = when (asset.substringAfterLast('.')) {
                        "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"
                        "webp" -> "image/webp"; "png" -> "image/png"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; "wasm" -> "application/wasm"; else -> "application/octet-stream"
                    }
                    WebResourceResponse(mime, "UTF-8", activity.assets.open("local-ui/$asset"))
                }.getOrElse { denied() }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                generation++; channel?.close(); channel = null
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!trustedDocument(Uri.parse(url)) || channel != null) return
                val epoch = generation
                val ports = createWebMessageChannel()
                channel = ports[0]
                ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        val raw = message.data ?: return
                        if (epoch != generation || raw.length > 2 * 1024 * 1024) return
                        if (waiting.incrementAndGet() > 64) { waiting.decrementAndGet(); return }
                        val decoded = runCatching { JSONObject(raw) }.getOrNull()
                        if (decoded?.optString("command")?.startsWith("platform.") == true && Regex("[0-9]{1,12}").matches(decoded.optString("id"))) {
                            waiting.decrementAndGet()
                            platform.handle(decoded.getString("command"), decoded.optJSONObject("input") ?: JSONObject()) { result, error ->
                                reply(JSONObject().put("id", decoded.optString("id")).apply { if (error != null) put("error", error) else put("result", result) }, epoch)
                            }
                            return
                        }
                        executor.execute {
                            if (epoch != generation) {
                                waiting.decrementAndGet()
                                return@execute
                            }
                            val response = JSONObject()
                            try {
                                val request = JSONObject(raw)
                                val id = request.getString("id")
                                require(Regex("[0-9]{1,12}").matches(id))
                                response.put("id", id)
                                response.put("result", backend.request(request.getString("command"), request.optJSONObject("input") ?: JSONObject()))
                            } catch (error: Exception) {
                                response.put("error", (error as? LocalUiFailure)?.message ?: "本机操作未完成，内容保留，请重试")
                            } finally { waiting.decrementAndGet() }
                            reply(response, epoch)
                        }
                    }
                })
                postWebMessage(WebMessage("echo-local-port", arrayOf(ports[1])), Uri.parse(ORIGIN))
            }
        }
        loadUrl(if (initialResource != null && Regex("memos/[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(initialResource)) "$ORIGIN/$initialResource" else "$ORIGIN/")
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    override fun destroy() { generation++; channel?.close(); channel = null; executor.shutdown(); super.destroy() }
}

private class BoundedAttachmentStream(input: InputStream, private var remaining: Long, private val originalSize: Long) : FilterInputStream(input) {
    private var started = false
    override fun read(): Int {
        started = true
        if (remaining <= 0) return -1
        return `in`.read().also { if (it >= 0) remaining-- else remaining = 0 }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return 0
        started = true
        if (remaining <= 0) return -1
        return `in`.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it >= 0) remaining -= it else remaining = 0 }
    }
    override fun skip(count: Long): Long = `in`.skip(count.coerceIn(0, remaining)).also { started = true; remaining -= it }
    override fun available(): Int = minOf(if (!started) originalSize else minOf(`in`.available().toLong(), remaining), Int.MAX_VALUE.toLong()).toInt()
}
