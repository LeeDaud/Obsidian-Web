package xyz.leedaud.echo.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import xyz.leedaud.echo.BuildConfig

/** App-wide download state survives page changes; notebook data is never read or modified here. */
class AppUpdates private constructor(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val http = UpdateHttp()
    private val root = File(context.cacheDir, "app-updates")
    private val preferences = context.getSharedPreferences("echo-app-updates", Context.MODE_PRIVATE)
    @Volatile private var state = "idle"
    @Volatile private var message = "点击检查更新"
    @Volatile private var bytes = 0L
    @Volatile private var busy = false
    @Volatile private var candidate: UpdateCandidate? = null
    @Volatile private var cancellation = AtomicBoolean(false)
    init {
        runCatching {
            val file = File(root, "candidate.json")
            if (file.isFile) {
                val data = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
                require(data.getInt("schemaVersion") == 1)
                val restored = UpdateCandidate(data.getLong("versionCode"), data.getString("versionName"), data.getInt("minSdk"),
                    data.getString("apkAssetName"), data.getLong("size"), data.getString("sha256"), data.getString("releaseNotes"), data.getString("url"))
                require(Regex("[a-f0-9]{64}").matches(restored.sha256) && restored.size in 1..UpdatePolicy.LIMIT && restored.code > 0)
                UpdatePolicy.downloadUrl(restored.url)
                candidate = restored
                state = if (apk(restored).isFile) "ready" else "available"
                message = if (state == "ready") "已下载，安装前将再次校验" else "下载未完成，可重新下载"
            }
            val pending = preferences.getLong("installCode", 0)
            if (pending > 0 && installedCode() >= pending) {
                state = "updated"; message = "更新已完成，本机数据保留"
                preferences.edit().remove("installCode").apply()
                candidate?.let { apk(it).delete(); part(it).delete() }
                candidate = null; File(root, "candidate.json").delete()
            } else if (pending > 0) {
                state = "installing"; message = "安装结果尚未确认，请先完成或取消系统安装"
            }
        }.onFailure { state = "error"; message = "更新缓存无法读取，请重新检查更新"; candidate = null }
    }
    companion object {
        @Volatile private var singleton: AppUpdates? = null
        fun get(context: Context): AppUpdates = singleton ?: synchronized(this) {
            singleton ?: AppUpdates(context.applicationContext).also { singleton = it }
        }
    }
    private fun apk(value: UpdateCandidate) = File(root, "code-${value.code}-${value.sha256}.apk")
    private fun part(value: UpdateCandidate) = File(root, "code-${value.code}-${value.sha256}.part")
    @Suppress("DEPRECATION") private fun info() = context.packageManager.getPackageInfo(context.packageName, flags())
    @Suppress("DEPRECATION") private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun installedCode() = code(info())
    @Suppress("DEPRECATION") private fun signers(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return values.orEmpty().map { digest(it.toByteArray()) }.toSet()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verified(value: UpdateCandidate): File {
        val file = apk(value)
        require(file.isFile && file.length() == value.size) { "更新文件不完整，请重新下载" }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) } }
        require(sha.digest().joinToString("") { "%02x".format(it) } == value.sha256) { "安装包摘要不匹配，请重新下载" }
        @Suppress("DEPRECATION") val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags()) ?: error("无法验证安装包")
        val app = archive.applicationInfo ?: error("安装包缺少应用信息")
        UpdatePolicy.archive(value, archive.packageName, code(archive), archive.versionName.orEmpty(), app.minSdkVersion,
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, signers(archive), signers(info()), installedCode(), Build.VERSION.SDK_INT)
        return file
    }
    @Synchronized fun status(): JSONObject = JSONObject().put("state", state).put("message", message).put("busy", busy)
        .put("downloadedBytes", bytes).put("currentVersion", BuildConfig.VERSION_NAME).put("currentCode", installedCode())
        .put("canInstall", !busy && candidate != null && state in setOf("ready", "permission_required"))
        .put("canDownload", !busy && candidate != null && state != "installing")
        .put("releasePage", UpdatePolicy.PAGE)
        .apply { candidate?.let { put("candidate", it.json().apply { remove("url") }) } }
    private fun persist(value: UpdateCandidate) {
        check(root.isDirectory || root.mkdirs()) { "无法建立更新缓存" }
        val file = AtomicFile(File(root, "candidate.json")); val stream = file.startWrite()
        try { stream.write(value.json().put("schemaVersion", 1).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized private fun start(next: String, work: (AtomicBoolean) -> Unit) {
        require(!busy && state != "installing") { "请先完成当前更新操作" }
        val token = AtomicBoolean(false); cancellation = token; busy = true; state = next; bytes = 0
        executor.execute {
            try { work(token) }
            catch (error: Exception) {
                state = if (token.get()) "cancelled" else "error"
                message = if (token.get()) "已取消，原应用与数据保留" else UpdatePolicy.failure(error)
            } finally { busy = false }
        }
    }
    fun check() {
        start("checking") { token ->
            require(context.packageName == UpdatePolicy.PACKAGE && !BuildConfig.DEBUG) { "调试版不接收正式更新，请使用同签名正式包" }
            message = "正在检查正式更新…"
            val release = http.json(UpdatePolicy.LATEST, token::get)
            UpdatePolicy.stable(release)
            val manifest = UpdatePolicy.asset(release, "echo-update.json")
            if (manifest == null) { state = "no_manifest"; message = "发布方尚未提供应用内更新包"; candidate = null; return@start }
            require(manifest.getLong("size") in 1..(256 * 1024)) { "更新清单大小无效" }
            val manifestUrl = manifest.getString("browser_download_url")
            UpdatePolicy.downloadUrl(manifestUrl)
            val tag = java.net.URI(release.getString("html_url")).rawPath.substringAfter("/releases/tag/")
            require(java.net.URI(manifestUrl).rawPath == "/LeeDaud/Obsidian-Web/releases/download/$tag/echo-update.json") { "清单不属于同一发布" }
            val value = UpdatePolicy.parse(http.json(manifestUrl, token::get), release)
            check(!token.get()) { "已取消更新检查" }
            if (value.code <= installedCode()) { candidate = null; state = "latest"; message = "当前已是最新版本" }
            else {
                require(value.minSdk <= Build.VERSION.SDK_INT) { "新版不支持当前安卓版本" }
                persist(value); candidate = value
                state = "available"; message = "发现新版本，可下载更新"
            }
        }
    }
    fun autoCheck() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val online = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (BuildConfig.DEBUG || !online || busy || state in setOf("ready", "permission_required", "installing")) return
        val now = System.currentTimeMillis()
        if (now - preferences.getLong("checkedAt", 0) < 24 * 60 * 60 * 1000L) return
        preferences.edit().putLong("checkedAt", now).apply()
        runCatching { check() }
    }
    fun download() {
        val value = candidate ?: error("请先检查更新")
        start("downloading") { token ->
            message = "正在下载，可随时取消"
            persist(value)
            require(root.usableSpace >= value.size + 16 * 1024 * 1024) { "存储空间不足，原数据保留" }
            val temporary = part(value)
            try {
                http.read(value.url, value.size, token::get) { input, length ->
                    require(length == -1L || length == value.size) { "下载大小与发布清单不匹配" }
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!token.get()) { "已取消更新下载" }
                            val count = input.read(buffer); if (count < 0) break
                            require(bytes + count <= value.size) { "下载超过发布大小" }
                            output.write(buffer, 0, count); bytes += count
                        }
                        output.fd.sync()
                    }
                }
                require(bytes == value.size) { "下载未完整，请重试" }
                check(!token.get()) { "已取消更新下载" }
                // Only update-module cache files are replaced; never an installed application or notebook file.
                if (apk(value).exists()) check(apk(value).delete()) { "无法替换旧更新缓存" }
                check(temporary.renameTo(apk(value))) { "无法完成更新文件暂存" }
                verified(value)
                check(!token.get()) { "已取消更新下载" }
                state = "ready"; message = "下载与校验完成，可安装"
            } finally { temporary.delete() }
        }
    }
    @Synchronized fun cancel() {
        require(state != "installing") { "安装器正在读取文件，请在系统界面取消" }
        cancellation.set(true); http.cancel()
        if (!busy) { state = "cancelled"; message = "已取消，原应用与数据保留" }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // The checkpoint must be durable before handing control to the installer.
    fun prepareInstall(complete: (Intent?, String?) -> Unit) {
        val value = candidate ?: error("请先下载更新")
        start("verifying") { token ->
            try {
                val file = verified(value)
                check(!token.get()) { "已取消更新安装" }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    state = "permission_required"; message = "请允许来自Echo的安装，返回后再次点击安装"
                    complete(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")), null)
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    check(preferences.edit().putLong("installCode", value.code).commit()) { "安装状态未能保存，请检查存储空间" }
                    state = "installing"; message = "等待系统确认安装，未确认成功前保留原应用"
                    complete(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
                }
            } catch (error: Exception) { complete(null, UpdatePolicy.failure(error)); throw error }
        }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Persist explicit system cancellation before allowing another update operation.
    fun installerReturned(resultCode: Int) {
        if (state == "installing") {
            if (resultCode == android.app.Activity.RESULT_CANCELED && preferences.edit().remove("installCode").commit()) {
                state = "ready"; message = "已取消系统安装，原应用与数据保留"
            } else message = "安装结果尚未确认，请完成系统安装或确认已取消"
        }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Explicit cancellation must clear the durable uncertain-install checkpoint.
    @Synchronized fun confirmInstallationCancelled() {
        require(!busy && state == "installing") { "没有待确认的安装" }
        check(preferences.edit().remove("installCode").commit()) { "取消状态未能保存，请重试" }
        state = "ready"; message = "已确认取消，可重新校验安装"
    }
    fun launchFailed() { state = "ready"; message = "无法打开安装器，可重试或手动安装正式包" }
}
