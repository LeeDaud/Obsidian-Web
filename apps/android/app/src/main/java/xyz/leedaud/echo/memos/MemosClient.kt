package xyz.leedaud.echo.memos

import org.json.JSONObject
import java.net.URI
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.time.Instant
import xyz.leedaud.echo.notes.digest

class MemosFailure(val publicMessage: String) : Exception(publicMessage)
data class MemosSession(val origin: String, val user: String, val username: String, val access: String,
    val refresh: String, val expires: Long, val active: Boolean = true) {
    val scope: String get() = digest("$origin\n$user".toByteArray(Charsets.UTF_8))
}
data class MemosAttachment(val name: String, val filename: String, val mime: String, val size: Long, val memo: String, val external: String)
data class MemosMemo(val name: String, val creator: String, val body: String, val created: String, val updated: String,
    val archived: Boolean, val attachments: List<MemosAttachment>, val raw: String) {
    val version: String get() = digest(canonical(JSONObject(raw)).toByteArray(Charsets.UTF_8))
    fun url(origin: String) = "$origin/$name"
    companion object {
        fun parse(json: JSONObject, owner: String): MemosMemo {
            val name = json.optString("name")
            requireResource(name, "memos")
            if (json.optString("creator") != owner) throw MemosFailure("服务器返回了其他账号的备忘录，已拒绝读取")
            val array = json.optJSONArray("attachments")
            val files = (0 until (array?.length() ?: 0)).map { i ->
                val a = array!!.getJSONObject(i)
                MemosAttachment(a.optString("name"), a.optString("filename"), a.optString("type"), a.optLong("size", -1),
                    a.optString("memo"), a.optString("externalLink", a.optString("external_link")))
            }
            return MemosMemo(name, owner, json.optString("content"), json.optString("createTime", json.optString("create_time")),
                json.optString("updateTime", json.optString("update_time")), json.optString("state") == "ARCHIVED", files, json.toString())
        }
    }
}
data class MemosPage(val memos: List<MemosMemo>, val next: String)
data class MemosResponse(val status: Int, val bytes: ByteArray, val cookies: List<String> = emptyList())
fun interface MemosTransport {
    fun request(origin: String, method: String, path: String, access: String, refresh: String, body: JSONObject?, limit: Int): MemosResponse
}
fun requireResource(name: String, kind: String) {
    if (!Regex("${kind}/[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(name)) throw MemosFailure("服务器资源标识无效")
}
fun memosOrigin(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrElse { throw MemosFailure("请输入有效的 HTTPS 实例地址") }
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null
        || uri.path !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0) throw MemosFailure("实例须为 HTTPS 域名，不含账号、路径或参数")
    return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toString()
}
private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

class MemosHttpTransport : MemosTransport {
    override fun request(origin: String, method: String, path: String, access: String, refresh: String, body: JSONObject?, limit: Int): MemosResponse {
        val base = memosOrigin(origin)
        if (!path.startsWith('/') || path.startsWith("//") || path.contains('\\')) throw MemosFailure("请求地址无效")
        val route = path.substringBefore('?')
        val permitted = if (method == "POST") route in listOf("/api/v1/auth/signin", "/api/v1/auth/refresh", "/api/v1/auth/signout")
            else method == "GET" && (route == "/api/v1/auth/me" || route == "/api/v1/memos"
                || Regex("/api/v1/(memos|attachments)/[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(route)
                || Regex("/file/attachments/[A-Za-z0-9][A-Za-z0-9_-]{0,119}/[^/]+").matches(route))
        if (!permitted || (method == "GET" && body != null)) throw MemosFailure("历史连接只允许读取，已拒绝写入请求")
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (access.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $access")
            if (refresh.isNotEmpty()) connection.setRequestProperty("Cookie", "memos_refresh=$refresh")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) return MemosResponse(status, ByteArray(0))
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > limit) throw MemosFailure("读取内容超过限制，请拆分后重试")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val cookies = connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten()
            return MemosResponse(status, bytes, cookies)
        } finally { connection.disconnect() }
    }
}

class MemosClient(private val transport: MemosTransport, initial: MemosSession? = null,
    private val now: () -> Long = System::currentTimeMillis, private val onSession: (MemosSession) -> Unit = {}) {
    var session: MemosSession? = initial
        private set
    private fun checked(response: MemosResponse): MemosResponse {
        if (response.status in 200..299) return response
        throw MemosFailure(when (response.status) {
            401 -> "登录失效或账号密码错误，请重新登录"
            403 -> "当前账号没有读取权限"
            404 -> "备忘录或接口不存在，请核对实例和版本"
            429 -> "请求过于频繁，请稍后重试"
            in 300..399 -> "拒绝携带登录会话跳转到其他地址"
            else -> "Memos 暂不可用 (${response.status})，离线副本保留"
        })
    }
    private fun json(response: MemosResponse): JSONObject = runCatching { JSONObject(checked(response).bytes.toString(Charsets.UTF_8)) }
        .getOrElse { if (it is MemosFailure) throw it else throw MemosFailure("服务器返回格式无效，请核对 Memos 版本") }
    private fun cookie(response: MemosResponse, origin: String, previous: String = ""): String {
        val host = URI(origin).host
        val cookies = response.cookies.flatMap { runCatching { HttpCookie.parse(it) }.getOrDefault(emptyList()) }
        val value = cookies.firstOrNull { it.name == "memos_refresh" }?.let {
            if (it.domain != null && !HttpCookie.domainMatches(it.domain, host)) throw MemosFailure("刷新会话域名不匹配")
            it.value
        } ?: previous
        if (value.length > 8192 || value.any { it.isWhitespace() || it.code < 32 || it in ";\r\n" }) throw MemosFailure("服务器刷新会话无效")
        return value
    }
    private fun token(data: JSONObject): String {
        val value = data.optString("accessToken", data.optString("access_token"))
        if (value.isBlank() || value.length > 8192 || value.any { it.isWhitespace() || it.code < 32 }) throw MemosFailure("服务器未返回有效登录凭证")
        return value
    }
    private fun expiry(data: JSONObject): Long {
        val text = data.optString("accessTokenExpiresAt", data.optString("access_token_expires_at", data.optString("expiresAt", data.optString("expires_at"))))
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrElse { throw MemosFailure("登录凭证有效期无效") }
    }
    fun signIn(origin: String, username: String, password: String): MemosSession {
        val base = memosOrigin(origin)
        if (username.isBlank() || username.length > 200 || password.isBlank() || password.length > 4096) throw MemosFailure("请输入账号和密码")
        val response = checked(transport.request(base, "POST", "/api/v1/auth/signin", "", "",
            JSONObject().put("passwordCredentials", JSONObject().put("username", username).put("password", password)), JSON_LIMIT))
        val data = json(response); val user = data.optJSONObject("user") ?: throw MemosFailure("登录身份缺失")
        val name = user.optString("name"); requireResource(name, "users")
        val next = MemosSession(base, name, user.optString("username", username), token(data), cookie(response, base), expiry(data))
        verifyIdentity(next)
        session = next; onSession(next); return next
    }
    private fun verifyIdentity(value: MemosSession) {
        val user = json(transport.request(value.origin, "GET", "/api/v1/auth/me", value.access, "", null, JSON_LIMIT)).optJSONObject("user")
        if (user?.optString("name") != value.user) throw MemosFailure("登录账号发生变化，已拒绝读取历史")
    }
    private fun renew() {
        val previous = session?.takeIf { it.active } ?: throw MemosFailure("请先连接 Memos")
        if (previous.refresh.isEmpty()) throw MemosFailure("登录已过期，请重新登录")
        val response = checked(transport.request(previous.origin, "POST", "/api/v1/auth/refresh", "", previous.refresh, JSONObject(), JSON_LIMIT))
        val data = json(response)
        val next = previous.copy(access = token(data), refresh = cookie(response, previous.origin, previous.refresh), expires = expiry(data))
        verifyIdentity(next); session = next; onSession(next)
    }
    private fun read(path: String, limit: Int = JSON_LIMIT): MemosResponse {
        var account = session?.takeIf { it.active } ?: throw MemosFailure("请先连接 Memos")
        if (account.expires <= now() + 30000) { renew(); account = session!! }
        var response = transport.request(account.origin, "GET", path, account.access, "", null, limit)
        if (response.status == 401 && account.refresh.isNotEmpty()) {
            renew(); account = session!!; response = transport.request(account.origin, "GET", path, account.access, "", null, limit)
        }
        return checked(response)
    }
    fun list(archived: Boolean, page: String = ""): MemosPage {
        val owner = session?.user ?: throw MemosFailure("请先连接 Memos")
        requireResource(owner, "users")
        val filter = "creator == \"$owner\""
        val data = json(read("/api/v1/memos?pageSize=30&state=${if (archived) "ARCHIVED" else "NORMAL"}&filter=${encode(filter)}&pageToken=${encode(page)}&orderBy=${encode("create_time desc")}"))
        val items = data.optJSONArray("memos")
        return MemosPage((0 until (items?.length() ?: 0)).map { MemosMemo.parse(items!!.getJSONObject(it), owner) },
            data.optString("nextPageToken", data.optString("next_page_token")))
    }
    fun get(name: String): MemosMemo {
        requireResource(name, "memos")
        return MemosMemo.parse(json(read("/api/v1/$name")), session?.user ?: throw MemosFailure("请先连接 Memos"))
    }
    fun attachment(file: MemosAttachment, memo: String): ByteArray {
        requireResource(file.name, "attachments")
        if (file.filename.isBlank() || file.filename.length > 255 || file.filename.any { it.code < 32 || it in "/\\" }
            || !Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(file.mime)) throw MemosFailure("附件名称或类型无效")
        if (file.external.isNotBlank() || file.memo != memo || file.size !in 0..FILE_LIMIT) throw MemosFailure("附件归属、来源或大小不符合离线保存要求")
        val metadata = json(read("/api/v1/${file.name}"))
        if (metadata.optString("name") != file.name || metadata.optString("memo") != memo || metadata.optLong("size", -1) != file.size
            || metadata.optString("filename") != file.filename || metadata.optString("type") != file.mime
            || metadata.optString("externalLink", metadata.optString("external_link")).isNotBlank()) throw MemosFailure("附件版本或归属发生变化，原副本保留")
        val bytes = read("/file/${file.name}/${encode(file.filename)}", FILE_LIMIT).bytes
        if (bytes.size.toLong() != file.size) throw MemosFailure("附件下载不完整，请重试")
        return bytes
    }
    fun signOut() {
        val account = session?.takeIf { it.active } ?: return
        try { checked(transport.request(account.origin, "POST", "/api/v1/auth/signout", account.access, account.refresh, null, JSON_LIMIT)) }
        finally { session = null }
    }
    companion object { const val JSON_LIMIT = 16 * 1024 * 1024; const val FILE_LIMIT = 10 * 1024 * 1024 }
}
