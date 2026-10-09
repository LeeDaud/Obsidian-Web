package xyz.leedaud.echo.memos

import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import xyz.leedaud.echo.notes.digest

data class MemosCachedFile(val attachment: MemosAttachment, val file: File, val sha256: String)
data class MemosCachedMemo(val memo: MemosMemo, val files: List<MemosCachedFile>)

/** Immutable source-version directories; only an explicitly completed download advances its pointer. */
class MemosHistoryStore(private val root: File) {
    private fun scope(session: MemosSession): File {
        if (!session.active) throw MemosFailure("请先登录原账号")
        memosOrigin(session.origin); requireResource(session.user, "users")
        return File(root, session.scope)
    }
    private fun id(name: String): String { requireResource(name, "memos"); return digest(name.toByteArray(Charsets.UTF_8)) }
    private fun write(file: File, bytes: ByteArray) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建历史缓存目录" }
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun cache(session: MemosSession, expected: MemosMemo, client: MemosClient): MemosCachedMemo {
        if (client.session?.scope != session.scope) throw MemosFailure("账号已变化，未保存历史")
        val memo = client.get(expected.name)
        if (memo.version != expected.version) throw MemosFailure("备忘录已更新，请刷新后再离线保存")
        if (memo.attachments.distinctBy { it.name }.size != memo.attachments.size) throw MemosFailure("附件标识重复，未保存离线副本")
        if (memo.body.toByteArray(Charsets.UTF_8).size > 256 * 1024 || memo.attachments.size > 100
            || memo.attachments.sumOf { it.size.coerceAtLeast(0) } > 25 * 1024 * 1024) throw MemosFailure("正文或附件超过单条离线保存限制，服务器原记录保留")
        val directory = File(scope(session), "${id(memo.name)}/${memo.version}")
        val files = memo.attachments.map { attachment ->
            val bytes = client.attachment(attachment, memo.name)
            val sha = digest(bytes)
            val target = File(directory, "$sha.bin")
            write(target, bytes)
            if (digest(target.readBytes()) != sha) throw MemosFailure("附件本机核验失败，未标记离线保存")
            JSONObject().put("name", attachment.name).put("sha256", sha).put("size", bytes.size)
        }
        if (client.get(memo.name).version != memo.version) throw MemosFailure("下载期间备忘录发生变化，旧副本保留，请刷新重试")
        val manifest = JSONObject().put("scope", session.scope).put("raw", memo.raw).put("version", memo.version).put("files", JSONArray(files))
        write(File(directory, "memo.json"), manifest.toString().toByteArray(Charsets.UTF_8))
        write(File(scope(session), "index-${id(memo.name)}.json"), JSONObject().put("name", memo.name).put("version", memo.version).toString().toByteArray(Charsets.UTF_8))
        return load(session, memo.name) ?: throw MemosFailure("缓存核验失败")
    }
    fun load(session: MemosSession, name: String): MemosCachedMemo? {
        val account = scope(session)
        val pointer = File(account, "index-${id(name)}.json")
        if (!pointer.isFile) return null
        val index = JSONObject(AtomicFile(pointer).readFully().toString(Charsets.UTF_8))
        val version = index.getString("version")
        if (!Regex("[a-f0-9]{64}").matches(version) || index.getString("name") != name) throw MemosFailure("缓存索引无效")
        val directory = File(account, "${id(name)}/$version")
        val manifest = JSONObject(AtomicFile(File(directory, "memo.json")).readFully().toString(Charsets.UTF_8))
        if (manifest.getString("scope") != session.scope) throw MemosFailure("缓存账号不匹配")
        val memo = MemosMemo.parse(JSONObject(manifest.getString("raw")), session.user)
        if (memo.name != name || memo.version != version || manifest.getString("version") != version) throw MemosFailure("缓存版本不匹配")
        val entries = manifest.getJSONArray("files")
        if (entries.length() != memo.attachments.size) throw MemosFailure("缓存缺少附件")
        val files = memo.attachments.map { attachment ->
            val entry = (0 until entries.length()).map { entries.getJSONObject(it) }.singleOrNull { it.optString("name") == attachment.name }
                ?: throw MemosFailure("附件清单不完整")
            val sha = entry.getString("sha256")
            if (!Regex("[a-f0-9]{64}").matches(sha)) throw MemosFailure("附件摘要无效")
            val file = File(directory, "$sha.bin")
            if (!file.isFile || file.length() != attachment.size || digest(file.readBytes()) != sha) throw MemosFailure("离线附件损坏，请重新保存")
            MemosCachedFile(attachment, file, sha)
        }
        return MemosCachedMemo(memo, files)
    }
    fun list(session: MemosSession): List<MemosCachedMemo> = scope(session).listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("index-") && it.name.endsWith(".json") }.mapNotNull {
            // An invalid record must not prevent other independently verified caches from loading.
            runCatching {
                val data = JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))
                load(session, data.getString("name"))
            }.getOrNull()
        }.sortedByDescending { it.memo.created }
}
