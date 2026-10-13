package xyz.leedaud.echo.update

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import xyz.leedaud.echo.ui.MemosTheme
import xyz.leedaud.echo.ui.MemosButton

class UpdateActivity : ComponentActivity() {
    private val updates by lazy { AppUpdates.get(this) }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updates.installerReturned(it.resultCode) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemosTheme(if (intent.hasExtra("update-dark")) intent.getBooleanExtra("update-dark", false) else isSystemInDarkTheme()) {
                var status by remember { mutableStateOf(updates.status()) }
                var error by remember { mutableStateOf<String?>(null) }
                var confirmCancel by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { status = updates.status(); delay(600) } } }
                val act: (() -> Unit) -> Unit = { action -> runCatching(action).onFailure { error = it.message ?: "操作未完成，请重试" }; status = updates.status() }
                val candidate = status.optJSONObject("candidate")
                val busy = status.optBoolean("busy")
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemosButton("返回", "chevron-left", onClick = { finish() })
                        Text("应用更新", style = MaterialTheme.typography.titleLarge)
                        Text("当前 ${status.optString("currentVersion")} · 构建 ${status.optLong("currentCode")}")
                        Text(status.optString("message"), style = MaterialTheme.typography.bodyMedium)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (candidate != null) {
                            Text("新版 ${candidate.optString("versionName")} · 构建 ${candidate.optLong("versionCode")}", style = MaterialTheme.typography.titleMedium)
                            Text(candidate.optString("releaseNotes"), style = MaterialTheme.typography.bodyMedium)
                            if (status.optString("state") == "downloading") {
                                val size = candidate.optLong("size", 1).coerceAtLeast(1)
                                val progress = (status.optLong("downloadedBytes").toFloat() / size).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Text("已下载 ${(progress * 100).toInt()}%")
                            }
                        }
                        MemosButton("检查更新", "search", enabled = !busy && status.optString("state") != "installing", onClick = { act { updates.check() } })
                        if (status.optBoolean("canDownload") && !status.optBoolean("canInstall")) MemosButton("下载更新", "download", onClick = { act { updates.download() } })
                        if (status.optBoolean("canInstall")) MemosButton(if (status.optString("state") == "permission_required") "允许安装并返回" else "安装更新", "download", enabled = intent.getBooleanExtra("drafts-confirmed", false), onClick = {
                            act { updates.prepareInstall { next, failure -> runOnUiThread {
                                if (failure != null) error = failure
                                else if (next != null && !isFinishing && !isDestroyed) runCatching { installer.launch(next) }.onFailure { updates.launchFailed(); error = "无法打开系统安装界面" }
                                else updates.launchFailed()
                            } } }
                        })
                        if (busy && status.optString("state") in setOf("checking", "downloading")) MemosButton("取消", "x", onClick = { act { updates.cancel() } })
                        if (status.optString("state") == "installing" && !busy) MemosButton("安装已取消，重新确认", "check", onClick = { confirmCancel = true })
                        Text("确认后由系统覆盖安装，保留本机笔记、草稿与附件。首次需要允许来自Echo的安装。", style = MaterialTheme.typography.bodySmall)
                        MemosButton("查看正式发布页", "link", onClick = { act { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdatePolicy.PAGE))) } })
                    }
                }
                if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("确认系统安装已取消") },
                    text = { Text("请先在系统安装界面完成取消，再重新校验。正在安装时不要继续。") },
                    confirmButton = { TextButton(onClick = { confirmCancel = false; act { updates.confirmInstallationCancelled() } }) { Text("已取消") } },
                    dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } })
            }
        }
    }
}
