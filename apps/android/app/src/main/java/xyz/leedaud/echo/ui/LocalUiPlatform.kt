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
    var refreshAfterSettings = false
    var resolveFile: ((String) -> Pair<String, File>)? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray()); files = null
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
        files?.onReceiveValue(null)
        files = callback
        picker.launch(params.acceptTypes.filter { it.isNotBlank() }.toTypedArray().ifEmpty { arrayOf("*/*") })
        return true
    }
    fun microphone(request: PermissionRequest) {
        if (!LocalWebView.trusted(request.origin) || request.resources.any { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }) { request.deny(); return }
        authorize(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted -> runCatching { if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny() } }
    }
    private fun authorize(names: Array<String>, callback: (Boolean) -> Unit) {
        if (names.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) { callback(true); return }
        if (permission != null) { callback(false); return }
        permission = callback; requester.launch(names)
    }
    fun handle(command: String, input: JSONObject, reply: (JSONObject?, String?) -> Unit) {
        when (command) {
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
                if (export != null) { reply(null, "请先完成当前导出"); return }
                io.execute {
                    val resolved = runCatching { resolveFile?.invoke(input.getString("name")) ?: error("No resource") }
                    activity.runOnUiThread {
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
    fun destroy() { files?.onReceiveValue(null); files = null; export?.second?.invoke(null, "页面已关闭"); export = null; io.shutdown() }
}
