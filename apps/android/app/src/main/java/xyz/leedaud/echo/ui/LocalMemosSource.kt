package xyz.leedaud.echo.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.memos.*
import xyz.leedaud.echo.notes.digest
import java.io.File

/** Only owner-checked Memos reads. Projected IDs include the authenticated account scope. */
class LocalMemosSource(private val context: Context,
    private val sessionProvider: () -> MemosSession? = { MemosSessionStore(context).read() },
    private val factory: (MemosSession) -> MemosClient = { MemosClient(MemosHttpTransport(), it, onSession = MemosSessionStore(context)::save) },
    private val connected: () -> Boolean = {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }) {
    private val cache = MemosHistoryStore(File(context.filesDir, "memos-history"))
    private var session: MemosSession? = null
    private var client: MemosClient? = null
    private val known = mutableMapOf<String, MemosMemo>()
    private var loadedAt = 0L
    private var offline = false
    @Synchronized private fun sync(): MemosSession? {
        val active = runCatching { sessionProvider() }.getOrNull()
        if (active?.scope != session?.scope) { known.clear(); loadedAt = 0 }
        if (active != session) client = active?.let(factory)
        session = active
        return active
    }
    @Synchronized fun account(): JSONObject? = sync()?.let { JSONObject().put("origin", it.origin).put("username", it.username).put("scope", it.scope) }
    private fun resource(kind: String, name: String, scope: String) = "$kind/r-${digest("$scope\n$name".toByteArray())}"
    @Synchronized fun snapshot(): List<JSONObject> {
        val active = sync() ?: return emptyList()
        if (System.currentTimeMillis() - loadedAt > 30000) {
            val all = mutableListOf<MemosMemo>()
            if (connected()) runCatching {
                for (archived in listOf(false, true)) {
                    var next = ""
                    val tokens = mutableSetOf<String>()
                    do {
                        val page = client!!.list(archived, next)
                        all += page.memos
                        if (all.size > 1000 || all.sumOf { it.raw.toByteArray().size.toLong() } > 16 * 1024 * 1024) throw MemosFailure("历史较多，请分批读取")
                        next = page.next
                        if (next.isNotBlank() && !tokens.add(next)) throw MemosFailure("分页未推进")
                    } while (next.isNotBlank())
                }
                offline = false
            }.onFailure { all.clear(); offline = true }
            else offline = true
            if (offline) all += cache.list(active).map { it.memo }
            known.clear()
            all.forEach { known[resource("memos", it.name, active.scope)] = it }
            loadedAt = System.currentTimeMillis()
        }
        return known.map { (name, memo) -> project(name, memo, active) }
    }
    private fun project(name: String, memo: MemosMemo, active: MemosSession): JSONObject {
        val raw = JSONObject(memo.raw)
        val data = JSONObject()
        for (key in listOf("state", "createTime", "updateTime", "tags", "pinned", "relations", "property", "parent", "snippet", "location")) {
            raw.opt(key)?.let { data.put(key, it) }
        }
        data.put("name", name).put("creator", "users/device").put("visibility", "PRIVATE")
        var content = memo.body
        val files = memo.attachments.map { file ->
            val projected = resource("attachments", file.name, active.scope)
            val old = "/file/${file.name}/${android.net.Uri.encode(file.filename)}"
            val local = "/file/$projected/${android.net.Uri.encode(file.filename)}"
            content = content.replace("${active.origin}$old", local).replace(old, local)
            JSONObject().put("name", projected).put("filename", file.filename).put("type", file.mime).put("size", file.size.toString()).put("memo", name)
        }
        data.put("content", content).put("attachments", JSONArray(files))
        val relations = data.optJSONArray("relations") ?: JSONArray()
        for (i in 0 until relations.length()) {
            val relation = relations.getJSONObject(i)
            for (key in listOf("memo", "relatedMemo")) relation.optJSONObject(key)?.let { related ->
                val original = related.optString("name")
                if (original.startsWith("memos/")) related.put("name", resource("memos", original, active.scope)).put("snippet", "")
            }
        }
        data.put("relations", relations)
        data.optString("parent").takeIf { it.startsWith("memos/") }?.let { data.put("parent", resource("memos", it, active.scope)) }
        return data.put("localMetadata", JSONObject().put("remote", true).put("frozen", true).put("offline", offline).put("scope", active.scope))
    }
    @Synchronized fun get(name: String): JSONObject {
        snapshot()
        val active = sync() ?: throw LocalUiFailure("请连接原账号")
        val memo = known[name] ?: throw LocalUiFailure("此备忘录不在当前账号的可读记录中")
        return project(name, memo, active)
    }
    @Synchronized fun sourceUrl(name: String): String {
        get(name)
        val active = session ?: throw LocalUiFailure("原账号已断开")
        return known.getValue(name).url(active.origin)
    }
    @Synchronized fun saveOffline(name: String): MemosCachedMemo {
        get(name)
        val active = session ?: throw LocalUiFailure("原账号已断开")
        val result = cache.cache(active, known.getValue(name), client!!)
        loadedAt = 0
        return result
    }
    @Synchronized fun cached(name: String): MemosCachedMemo {
        get(name)
        val active = session ?: throw LocalUiFailure("原账号已断开")
        val source = known.getValue(name)
        val cached = cache.load(active, source.name) ?: throw LocalUiFailure("请先离线保存正文和附件")
        if (cached.memo.version != source.version) throw LocalUiFailure("当前版本尚未完整缓存")
        return cached
    }
    @Synchronized fun attachmentFile(name: String): Pair<String, File> {
        snapshot()
        val active = sync() ?: throw LocalUiFailure("原账号已断开")
        val source = known.values.firstOrNull { memo -> memo.attachments.any { resource("attachments", it.name, active.scope) == name } }
            ?: throw LocalUiFailure("附件不属于当前账号记录")
        val attachment = source.attachments.first { resource("attachments", it.name, active.scope) == name }
        cache.load(active, source.name)?.takeIf { it.memo.version == source.version }?.files?.find { it.attachment.name == attachment.name }?.let { return attachment.mime to it.file }
        if (offline) throw LocalUiFailure("此附件尚未离线保存")
        val bytes = client!!.attachment(attachment, source.name)
        val file = File(context.cacheDir, "memos-view/${active.scope}/${digest(bytes)}.bin")
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        return attachment.mime to file
    }
    @Synchronized fun invalidate() { loadedAt = 0 }
    @Synchronized fun disconnect() {
        val active = sync()
        if (active != null) MemosSessionStore(context).disconnect(active)
        runCatching { client?.signOut() }
        session = null; client = null; known.clear(); loadedAt = 0
    }
}
