package xyz.leedaud.echo.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** User-facing OS operations are asynchronous so a picker cannot block device draft writes. */
class LocalUiPlatform(private val activity: ComponentActivity) {
    private val io = Executors.newSingleThreadExecutor()
    private var files: ValueCallback<Array<Uri>>? = null
    private var export: Pair<File, (JSONObject?, String?) -> Unit>? = null
    private var permission: ((Boolean) -> Unit)? = null
    private var microphoneRequest: PermissionRequest? = null
    private var destroyed = false
    private var resolvingExport = false
    private var chooseReply: ((JSONObject?, String?) -> Unit)? = null
    private var resolvingPick = false
    private val pickedFiles = LocalUiPickedFiles(activity)
    var refreshAfterSettings = false
    private val updates by lazy { xyz.leedaud.echo.update.AppUpdates.get(activity) }
    private var webDark = false
    private val updateInstaller = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updates.installerReturned(it.resultCode) }
    var requestUpdateDrafts: ((String) -> Unit)? = null
    private var updatePreparation: Pair<String, (Boolean) -> Unit>? = null
    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    fun cancelUpdatePreparation() {
        val pending = updatePreparation; updatePreparation = null
        pending?.second?.invoke(false)
    }
    private fun confirmUpdateDrafts(complete: (Boolean) -> Unit) {
        if (updatePreparation != null || requestUpdateDrafts == null) { complete(false); return }
        val nonce = java.util.UUID.randomUUID().toString()
        updatePreparation = nonce to complete
        updateHandler.postDelayed({ if (updatePreparation?.first == nonce) cancelUpdatePreparation() }, 55000)
        requestUpdateDrafts!!.invoke(nonce)
    }
    var resolveFile: ((String) -> Pair<String, File>)? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val callback = files; files = null
        callback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    private val singlePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = files; files = null; callback?.onReceiveValue(uri?.let { arrayOf(it) })
    }
    private val saver = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = export ?: return@registerForActivityResult
        export = null
        if (uri == null || uri.scheme != "content") { pending.second(null, "已取消导出，原内容保留"); return@registerForActivityResult }
        io.execute {
            val result = runCatching { activity.contentResolver.openOutputStream(uri)?.use { output -> pending.first.inputStream().use { it.copyTo(output) } } ?: error("No destination") }
            activity.runOnUiThread { pending.second(if (result.isSuccess) JSONObject().put("saved", true) else null, if (result.isFailure) "导出未完成，原内容保留" else null) }
        }
    }
    private val requester = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
        val callback = permission; permission = null; callback?.invoke(values.values.any { it })
    }
    fun choose(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        if (destroyed) { callback.onReceiveValue(null); return false }
        files?.onReceiveValue(null)
        files = callback
        val types = params.acceptTypes.filter { it.isNotBlank() }.toTypedArray().ifEmpty { arrayOf("*/*") }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) picker.launch(types) else singlePicker.launch(types)
        return true
    }
    fun cancelSelection() {
        val callback = files; files = null; callback?.onReceiveValue(null)
        val reply = chooseReply; chooseReply = null
        reply?.invoke(JSONObject().put("files", org.json.JSONArray()), null)
    }
    fun microphone(request: PermissionRequest) {
        if (destroyed || microphoneRequest != null || !LocalWebView.trusted(request.origin) || request.resources.isEmpty()
            || request.resources.any { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }) { request.deny(); return }
        microphoneRequest = request
        authorize(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
            if (microphoneRequest !== request) return@authorize
            microphoneRequest = null
            runCatching { if (granted && !destroyed) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny() }
        }
    }
    fun cancelMicrophone(request: PermissionRequest) {
        if (microphoneRequest === request) microphoneRequest = null
    }
    private fun authorize(names: Array<String>, callback: (Boolean) -> Unit) {
        if (destroyed) { callback(false); return }
        if (names.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) { callback(true); return }
        if (permission != null) { callback(false); return }
        permission = callback; requester.launch(names)
    }
    fun handle(command: String, input: JSONObject, reply: (JSONObject?, String?) -> Unit) {
        if (destroyed) { reply(null, "页面已关闭"); return }
        when (command) {
            "platform.update.status", "platform.update.check", "platform.update.download", "platform.update.cancel", "platform.update.auto" -> {
                runCatching {
                    when (command) {
                        "platform.update.check" -> updates.check()
                        "platform.update.download" -> updates.download()
                        "platform.update.cancel" -> updates.cancel()
                        "platform.update.auto" -> updates.autoCheck()
                    }
                    updates.status()
                }.onSuccess { reply(it, null) }.onFailure { reply(null, it.message ?: "更新操作未完成") }
            }
            "platform.update.open" -> {
                confirmUpdateDrafts { saved ->
                    if (!saved || destroyed) reply(null, "设备暂存尚未确认，请返回编辑器暂存后再更新")
                    else {
                        activity.startActivity(Intent(activity, xyz.leedaud.echo.update.UpdateActivity::class.java).putExtra("drafts-confirmed", true).putExtra("update-dark", webDark))
                        reply(JSONObject().put("opened", true), null)
                    }
                }
            }
            "platform.update.drafts-ready" -> {
                val pending = updatePreparation
                if (pending == null || pending.first != input.optString("nonce")) { reply(null, "暂存确认已失效"); return }
                updatePreparation = null
                reply(JSONObject().put("confirmed", true), null)
                pending.second(input.optBoolean("saved"))
            }
            "platform.update.confirm-cancel" -> {
                runCatching { updates.confirmInstallationCancelled(); updates.status() }.onSuccess { reply(it, null) }.onFailure { reply(null, it.message) }
            }
            "platform.update.releases" -> {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(xyz.leedaud.echo.update.UpdatePolicy.PAGE))) }
                    .onSuccess { reply(JSONObject().put("opened", true), null) }.onFailure { reply(null, "无法打开发布页") }
            }
            "platform.update.install" -> {
                confirmUpdateDrafts { saved ->
                if (!saved || destroyed) { reply(null, "设备暂存尚未确认，未启动安装"); return@confirmUpdateDrafts }
                runCatching {
                    updates.prepareInstall { intent, error -> activity.runOnUiThread {
                        if (destroyed || activity.isFinishing || activity.isDestroyed) { updates.launchFailed(); reply(null, "页面已关闭，请重新确认安装") }
                        else if (error != null) reply(null, error)
                        else runCatching { updateInstaller.launch(intent!!); updates.status() }.onSuccess { reply(it, null) }
                            .onFailure { updates.launchFailed(); reply(null, "无法打开系统安装界面") }
                    } }
                }.onFailure { reply(null, it.message ?: "安装未完成，请重试") }
                }
            }
            "platform.choose" -> {
                if (files != null || chooseReply != null || resolvingPick) { reply(null, "请先完成当前文件选择"); return }
                val accept = input.optString("accept")
                if (accept.length > 1024) { reply(null, "文件类型条件过长"); return }
                val types = accept.split(',').mapNotNull { value ->
                    val item = value.trim().lowercase()
                    if (item.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.removePrefix("."))
                    else item.takeIf { Regex("[a-z0-9.+*-]+/[a-z0-9.+*-]+").matches(it) }
                }.distinct().toTypedArray().ifEmpty { arrayOf("*/*") }
                chooseReply = reply
                files = ValueCallback { uris ->
                    if (chooseReply !== reply) return@ValueCallback
                    if (uris == null) { chooseReply = null; reply(JSONObject().put("files", org.json.JSONArray()), null); return@ValueCallback }
                    resolvingPick = true
                    io.execute {
                        val result = runCatching { pickedFiles.copy(uris.toList()) }
                        activity.runOnUiThread {
                            resolvingPick = false
                            if (chooseReply !== reply || destroyed) return@runOnUiThread
                            chooseReply = null
                            reply(result.getOrNull(), if (result.isFailure) (result.exceptionOrNull() as? LocalUiFailure)?.message ?: "文件选择未完成，原内容保留" else null)
                        }
                    }
                }
                try { if (input.optBoolean("multiple")) picker.launch(types) else singlePicker.launch(types) }
                catch (_: Exception) { files = null; chooseReply = null; reply(null, "无法打开系统文件选择器") }
            }
            "platform.settings" -> {
                refreshAfterSettings = true
                activity.startActivity(Intent(activity, xyz.leedaud.echo.NativeActivity::class.java).putExtra("native-settings", true))
                reply(JSONObject().put("opened", true), null)
            }
            "platform.clipboard" -> {
                val text = input.optString("text")
                if (text.toByteArray().size > 256 * 1024) { reply(null, "复制内容过长"); return }
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Memos", text))
                reply(JSONObject().put("copied", true), null)
            }
            "platform.theme" -> {
                webDark = input.optBoolean("dark")
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !input.optBoolean("dark"); isAppearanceLightNavigationBars = !input.optBoolean("dark")
                }
                input.optString("background").takeIf { Regex("#[a-fA-F0-9]{6}").matches(it) }?.let { activity.window.decorView.setBackgroundColor(android.graphics.Color.parseColor(it)) }
                reply(JSONObject(), null)
            }
            "platform.location" -> authorize(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
                if (!granted) { reply(null, "定位权限未允许"); return@authorize }
                val manager = activity.getSystemService(LocationManager::class.java)
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { reply(null, "定位权限已失效"); return@authorize }
                val location = manager.getProviders(true).mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null } }.maxByOrNull { it.time }
                if (location == null) reply(null, "没有可用位置，可手动设置坐标") else reply(JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy), null)
            }
            "platform.save", "platform.share", "platform.view" -> {
                if (export != null || resolvingExport) { reply(null, "请先完成当前导出"); return }
                resolvingExport = true
                io.execute {
                    val resolved = runCatching { resolveFile?.invoke(input.getString("name")) ?: error("No resource") }
                    activity.runOnUiThread {
                        resolvingExport = false
                        if (destroyed) { reply(null, "页面已关闭"); return@runOnUiThread }
                        val pair = resolved.getOrNull()
                        if (pair == null) { reply(null, "导出文件核验失败"); return@runOnUiThread }
                        val filename = input.optString("filename", "memos-export.bin").replace(Regex("[/\\\\\r\n]"), "_").take(180)
                        if (command == "platform.save") { export = pair.second to reply; saver.launch(filename) }
                        else if (command == "platform.view") runCatching {
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", pair.second, filename)
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, pair.first).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onSuccess { reply(JSONObject().put("opened", true), null) }.onFailure { reply(null, "没有可用的文件查看应用") }
                        else runCatching {
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", pair.second, filename)
                            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(pair.first).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享"))
                        }.onSuccess { reply(JSONObject().put("shared", true), null) }.onFailure { reply(null, "没有可用的分享应用") }
                    }
                }
            }
            else -> reply(null, "系统操作不支持")
        }
    }
    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancelUpdatePreparation()
        cancelSelection()
        val callback = permission; permission = null; callback?.invoke(false)
        microphoneRequest = null
        export?.second?.invoke(null, "页面已关闭"); export = null
        io.shutdown()
    }
}
