package xyz.leedaud.echo.delivery

import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class DeliveryFailure(val kind: String, message: String, val retryAt: Long = 0) : Exception(message)
data class ApiResponse(val status: Int, val body: JSONObject, val headers: Map<String, String> = emptyMap())
fun interface GitHubTransport { fun request(method: String, path: String, body: JSONObject?): ApiResponse }
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

object TargetValidation {
    fun validate(owner: String, repo: String, branch: String) {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(owner)) { "仓库所有者格式无效" }
        require(Regex("[A-Za-z0-9._-]{1,100}").matches(repo) && repo !in setOf(".", "..")) { "仓库名格式无效" }
        require(branch.isNotBlank() && branch.length <= 200 && !branch.startsWith('/') && !branch.endsWith('/')
            && !branch.contains("..") && !branch.contains("@{") && !branch.contains("//") && branch != "@"
            && branch.split('/').all { !it.startsWith('.') && !it.endsWith('.') && !it.endsWith(".lock") }
            && branch.none { it.isWhitespace() || it in "~^:?*[\\" || it.code < 32 || it.code == 127 }) { "分支名格式无效" }
    }
}

class NetworkTransport(target: Target, private val token: String) : GitHubTransport {
    private val base: String
    init {
        TargetValidation.validate(target.owner, target.repo, target.branch)
        require(token.isNotBlank() && token.none { it.isWhitespace() }) { "请重新配置授权" }
        base = "https://api.github.com/repos/${encode(target.owner)}/${encode(target.repo)}"
    }
    override fun request(method: String, path: String, body: JSONObject?): ApiResponse {
        require(path.startsWith('/') || path.isEmpty())
        val uri = URI(base + path)
        require(uri.host == "api.github.com" && uri.scheme == "https")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Echo-Android/0.2.0")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 48 * 1024 * 1024) { "GitHub 响应超过安全读取限制" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            } ?: "{}"
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull() ?: "" }
            return ApiResponse(status, runCatching { JSONObject(text) }.getOrElse { JSONObject() }, headers)
        } finally { connection.disconnect() }
    }
}

/** Creates one immutable bundle commit, checkpoints before publishing, then verifies every byte. */
class GitHubClient(private val transport: GitHubTransport) {
    private fun api(path: String, method: String = "GET", data: JSONObject? = null): JSONObject {
        val response = transport.request(method, path, data)
        if (response.status in 200..299) return response.body
        val limited = response.status == 429 || (response.status == 403 &&
            (response.headers["x-ratelimit-remaining"] == "0" || response.headers.containsKey("retry-after")
                || response.body.optString("message").contains("rate limit", true)))
        if (limited) {
            val now = System.currentTimeMillis()
            val seconds = response.headers["retry-after"]?.toLongOrNull()
            val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()?.times(1000)
            throw DeliveryFailure("retryable_error", "GitHub 限流，任务保留并等待重试", maxOf(now + 60000, seconds?.let { now + it * 1000 } ?: reset ?: now))
        }
        throw when (response.status) {
            401, 403, 404 -> DeliveryFailure("auth_required", "GitHub 授权、仓库访问或分支规则需要处理 (${response.status})")
            409 -> DeliveryFailure("retryable_error", "分支已变化，先核查本次提交再重试")
            422 -> DeliveryFailure("conflict", "GitHub 拒绝更新，请检查分支规则与提交状态")
            in 300..399 -> DeliveryFailure("auth_required", "拒绝携带授权跟随 GitHub 重定向，请核对目标仓库")
            else -> DeliveryFailure("retryable_error", "GitHub 暂不可用 (${response.status})，本机内容保留")
        }
    }
    fun check(target: Target): Long {
        TargetValidation.validate(target.owner, target.repo, target.branch)
        val id = api("").getLong("id")
        api("/git/ref/heads/${encode(target.branch)}")
        if (target.repositoryId != 0L && target.repositoryId != id) throw DeliveryFailure("conflict", "仓库身份已变化，原任务不会转投")
        return id
    }
    fun head(target: Target): String = api("/git/ref/heads/${encode(target.branch)}").getJSONObject("object").getString("sha")
    private fun tree(commit: String): Map<String, JSONObject> {
        val root = api("/git/commits/${encode(commit)}").getJSONObject("tree").getString("sha")
        val recursive = api("/git/trees/${encode(root)}?recursive=1")
        val result = linkedMapOf<String, JSONObject>()
        if (!recursive.optBoolean("truncated")) {
            recursive.getJSONArray("tree").objects().forEach { result[it.getString("path")] = it }
        } else {
            fun walk(sha: String, prefix: String, depth: Int) {
                if (depth > 32 || result.size > 100000) throw DeliveryFailure("conflict", "仓库目录过深或过大，不能安全核验")
                val branch = api("/git/trees/${encode(sha)}")
                if (branch.optBoolean("truncated")) throw DeliveryFailure("conflict", "目录仍被截断，不能按不存在处理")
                branch.getJSONArray("tree").objects().forEach {
                    val path = prefix + it.getString("path")
                    result[path] = it
                    if (it.getString("type") == "tree") walk(it.getString("sha"), "$path/", depth + 1)
                }
            }
            walk(root, "", 0)
        }
        return result
    }
    fun available(target: Target, candidate: String, attachments: List<String>, reserved: Set<String>): Boolean {
        val entries = tree(head(target))
        val proposed = listOf(candidate) + attachments
        return proposed.all { path ->
            validatePath(path)
            entries.keys.none { it.equals(path, true) } && reserved.none { it.equals(path, true) }
                && path.split('/').dropLast(1).indices.all { index ->
                    val ancestor = path.split('/').take(index + 1).joinToString("/")
                    entries.entries.none { it.key.equals(ancestor, true) && (it.key != ancestor || it.value.optString("type") != "tree") }
                }
        }
    }
    private fun isPublished(attempt: Attempt, current: String): Boolean {
        if (current == attempt.commit) return true
        val status = api("/compare/${encode(attempt.commit)}...${encode(current)}").getString("status")
        return status == "ahead" || status == "identical"
    }

    fun publish(target: Target, files: List<DeliveryFile>, previous: Attempt?, checkpoint: (Attempt) -> Unit): Attempt {
        check(target)
        require(files.isNotEmpty() && files.map { it.path.lowercase() }.distinct().size == files.size)
        files.forEach { validatePath(it.path); require(digest(Base64.getDecoder().decode(it.base64)) == it.sha256) { "快照摘要无效" } }
        val current = head(target)
        if (previous != null) {
            if (isPublished(previous, current)) {
                verify(files, previous)
                if (!isPublished(previous, head(target))) throw DeliveryFailure("conflict", "核验期间目标历史已变化")
                return previous
            }
            if (current != previous.parent) throw DeliveryFailure("conflict", "原尝试未能确认，分支已变化；保留快照，禁止自动重复发布")
            api("/git/refs/heads/${encode(target.branch)}", "PATCH", JSONObject().put("sha", previous.commit).put("force", false))
            if (!isPublished(previous, head(target))) throw DeliveryFailure("retryable_error", "提交发布待确认")
            verify(files, previous)
            if (!isPublished(previous, head(target))) throw DeliveryFailure("conflict", "核验期间目标历史已变化")
            return previous
        }
        val existing = tree(current)
        files.forEach { file ->
            if (existing.keys.any { it.equals(file.path, true) }) throw DeliveryFailure("conflict", "远端路径已存在，不能覆盖")
            val pieces = file.path.split('/')
            for (index in 1 until pieces.size) {
                val ancestor = pieces.take(index).joinToString("/")
                if (existing.entries.any { it.key.equals(ancestor, true) && (it.key != ancestor || it.value.optString("type") != "tree") }) {
                    throw DeliveryFailure("conflict", "远端祖先路径或大小写冲突")
                }
            }
        }
        val baseTree = api("/git/commits/${encode(current)}").getJSONObject("tree").getString("sha")
        val blobs = files.associate { it.path to api("/git/blobs", "POST", JSONObject().put("content", it.base64).put("encoding", "base64")).getString("sha") }
        val newTree = api("/git/trees", "POST", JSONObject().put("base_tree", baseTree).put("tree", JSONArray(files.map {
            JSONObject().put("path", it.path).put("mode", "100644").put("type", "blob").put("sha", blobs.getValue(it.path))
        }))).getString("sha")
        val commit = api("/git/commits", "POST", JSONObject().put("message", "capture: android ${files.first().path}")
            .put("tree", newTree).put("parents", JSONArray(listOf(current)))).getString("sha")
        val attempt = Attempt(current, commit, blobs)
        checkpoint(attempt)
        api("/git/refs/heads/${encode(target.branch)}", "PATCH", JSONObject().put("sha", commit).put("force", false))
        if (!isPublished(attempt, head(target))) throw DeliveryFailure("retryable_error", "提交发布待确认")
        verify(files, attempt)
        if (!isPublished(attempt, head(target))) throw DeliveryFailure("conflict", "核验期间目标历史已变化")
        return attempt
    }
    private fun verify(files: List<DeliveryFile>, attempt: Attempt) {
        val entries = tree(attempt.commit)
        files.forEach { file ->
            val sha = attempt.blobs[file.path] ?: throw DeliveryFailure("conflict", "缺少文件提交凭证")
            val entry = entries[file.path]
            if (entry?.optString("sha") != sha || entry.optString("type") != "blob" || entry.optString("mode") != "100644")
                throw DeliveryFailure("retryable_error", "仓库文件版本核验失败，本机内容保留")
            val blob = api("/git/blobs/${encode(sha)}")
            val bytes = Base64.getMimeDecoder().decode(blob.getString("content"))
            if (blob.optString("encoding") != "base64" || blob.getString("sha") != sha || blob.getLong("size") != bytes.size.toLong()
                || digest(bytes) != file.sha256 || !bytes.contentEquals(Base64.getDecoder().decode(file.base64)))
                throw DeliveryFailure("retryable_error", "正文或附件内容核验失败，本机内容保留")
        }
    }
    private fun validatePath(path: String) {
        require(Regex("00_Inbox/\\d{8}-\\d{6}\\.md|attachments/\\d{8}-\\d{6}/\\d{8}-\\d{6}(?:-\\d{2,3})?\\.[a-z0-9]{1,10}").matches(path)) { "投递路径无效" }
    }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
