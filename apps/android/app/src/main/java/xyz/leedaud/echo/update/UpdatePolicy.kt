package xyz.leedaud.echo.update

import org.json.JSONObject
import java.net.URI

data class UpdateCandidate(val code: Long, val version: String, val minSdk: Int, val asset: String,
    val size: Long, val sha256: String, val notes: String, val url: String) {
    fun json() = JSONObject().put("versionCode", code).put("versionName", version).put("minSdk", minSdk)
        .put("apkAssetName", asset).put("size", size).put("sha256", sha256).put("releaseNotes", notes).put("url", url)
}

/** Public release metadata is untrusted. Only this application's fixed stable channel is accepted. */
object UpdatePolicy {
    const val PACKAGE = "xyz.leedaud.echo"
    const val PAGE = "https://github.com/LeeDaud/Obsidian-Web/releases"
    const val LATEST = "https://api.github.com/repos/LeeDaud/Obsidian-Web/releases/latest"
    const val LIMIT = 100L * 1024 * 1024
    fun failure(error: Exception): String {
        val value = error.message.orEmpty()
        return if ((error is IllegalArgumentException || error is IllegalStateException) && value.isNotBlank()
            && value.none { it == '/' || it == '\\' || it == ':' }) value.take(180)
        else "更新网络或本机缓存操作未完成，请重试；原数据保留"
    }
    fun downloadUrl(value: String, redirected: Boolean = false): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null) { "更新下载地址不安全" }
        require(uri.path.split('/').none { it == "." || it == ".." } && !uri.path.contains('\\')) { "更新下载路径无效" }
        val direct = uri.host == "github.com" && uri.rawPath.startsWith("/LeeDaud/Obsidian-Web/releases/download/")
        val assetHost = uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
        require(direct || (redirected && assetHost)) { "更新来源不在正式发布渠道" }
        return uri
    }
    fun asset(release: JSONObject, name: String): JSONObject? {
        val assets = release.getJSONArray("assets")
        require(assets.length() <= 100) { "发布资产过多" }
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
        require(matches.size <= 1) { "发布资产名称重复" }
        return matches.singleOrNull()
    }
    fun stable(release: JSONObject) {
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "只接受正式稳定更新" }
        require(release.getString("html_url").startsWith("$PAGE/tag/")) { "发布仓库不匹配" }
    }
    fun parse(manifest: JSONObject, release: JSONObject): UpdateCandidate {
        stable(release)
        for (key in listOf("schemaVersion", "versionCode", "minSdk", "size")) {
            require(manifest.get(key) is Number && Regex("[0-9]+").matches(manifest.get(key).toString())) { "更新清单数值字段无效" }
        }
        require(manifest.getInt("schemaVersion") == 1 && manifest.getString("applicationId") == PACKAGE) { "更新清单不兼容" }
        val name = manifest.getString("apkAssetName")
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.apk").matches(name)) { "APK资产名称无效" }
        val sha = manifest.getString("sha256")
        val size = manifest.getLong("size")
        val code = manifest.getLong("versionCode")
        val minSdk = manifest.getInt("minSdk")
        val version = manifest.getString("versionName")
        val notes = manifest.optString("releaseNotes")
        require(code in 1..Int.MAX_VALUE.toLong() && minSdk in 26..100 && version.isNotBlank() && version.length <= 80
            && notes.length <= 12000 && size in 1..LIMIT && Regex("[a-f0-9]{64}").matches(sha)) { "更新清单字段无效" }
        val file = asset(release, name) ?: error("发布缺少对应APK")
        require(file.getLong("size") == size && file.optString("state", "uploaded") == "uploaded") { "发布文件大小或状态不一致" }
        val url = file.getString("browser_download_url")
        downloadUrl(url)
        val tagPath = URI(release.getString("html_url")).rawPath.substringAfter("/releases/tag/")
        require(URI(url).rawPath == "/LeeDaud/Obsidian-Web/releases/download/$tagPath/$name") { "APK不属于同一发布" }
        return UpdateCandidate(code, version, minSdk, name, size, sha, notes, url)
    }
    fun archive(candidate: UpdateCandidate, packageName: String, code: Long, version: String, minSdk: Int,
        debuggable: Boolean, signers: Set<String>, installedSigners: Set<String>, installedCode: Long, sdk: Int) {
        require(packageName == PACKAGE && code == candidate.code && version == candidate.version && minSdk == candidate.minSdk) { "APK与更新清单不一致" }
        require(code > installedCode && minSdk <= sdk) { "更新版本过旧或设备不兼容" }
        require(!debuggable && signers.isNotEmpty() && signers == installedSigners) { "更新签名不匹配或安装包为调试版" }
    }
}
