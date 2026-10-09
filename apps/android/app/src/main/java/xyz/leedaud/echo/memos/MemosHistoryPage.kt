package xyz.leedaud.echo.memos

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.leedaud.echo.ui.*

@Composable fun MemosHistoryPage(state: MemosState, model: MemosViewModel, copy: (MemosCachedMemo, String) -> Unit,
    initialMemo: MemosMemo? = null, onConnected: () -> Unit = {}, back: () -> Unit = {}) {
    val context = LocalContext.current
    var origin by remember { mutableStateOf("https://memos.leedaud.xyz") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selected by remember(state.account?.scope, initialMemo?.name) { mutableStateOf(initialMemo) }
    var loginRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.account?.scope, state.busy) {
        if (loginRequested && state.account != null && !state.busy) { loginRequested = false; onConnected() }
    }
    var localError by remember { mutableStateOf<String?>(null) }
    if (selected != null) BackHandler { selected = null; back() }
    else BackHandler { back() }
    LazyColumn(Modifier.fillMaxSize().testTag("memos-history"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (initialMemo == null) "Memos 账号" else "备忘录", style = MaterialTheme.typography.titleLarge) }
        if (!state.ready) item { Text("正在恢复账号信息…") }
        else if (state.account == null) {
            item { OutlinedTextField(origin, { origin = it }, label = { Text("实例地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("memos-origin")) }
            item { OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("memos-username")) }
            item { OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("memos-password")) }
            item { MemosButton("登录并读取历史", "link", enabled = !state.busy && username.isNotBlank() && password.isNotBlank(), onClick = {
                loginRequested = true; model.login(origin, username.trim(), password); password = ""
            }) }
        } else {
            if (selected == null) item {
                Text("${state.account.username} · ${state.account.origin}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemosButton("刷新", "download", enabled = !state.busy, onClick = { model.refresh() })
                    MemosButton("断开账号", "x", enabled = !state.busy, onClick = { selected = null; model.disconnect() })
                }
            }
            if (state.offline) item { Text("离线副本 · 不代表服务器最新版本", style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground) }
            if (selected == null) {
                item { MemosButton("返回首页", "house", onClick = onConnected) }
            } else {
                val memo = selected!!
                val cached = state.cached.find { it.memo.name == memo.name && it.memo.version == memo.version }
                item { MemosButton("返回首页", "chevron-left", onClick = { selected = null; back() }) }
                item {
                    MemosCard {
                        Text("创建：${memo.created}\n更新：${memo.updated}", style = MaterialTheme.typography.bodySmall)
                        MarkdownPreview(memo.body)
                        memo.attachments.forEach { Text("附件：${it.filename} (${it.size} bytes)", style = MaterialTheme.typography.bodySmall) }
                        val json = org.json.JSONObject(memo.raw)
                        json.optString("parent").takeIf { it.isNotBlank() }?.let { Text("原关联：$it", style = MaterialTheme.typography.bodySmall) }
                        val relations = json.optJSONArray("relations")
                        for (i in 0 until (relations?.length() ?: 0)) {
                            val related = relations!!.getJSONObject(i).optJSONObject("relatedMemo")?.optString("name").orEmpty()
                            if (related.isNotBlank()) Text("原引用：$related", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (cached != null) items(cached.files, key = { it.attachment.name }) { file ->
                    if (file.attachment.mime.startsWith("image/")) {
                        val bitmap by produceState<android.graphics.Bitmap?>(null, file.sha256) {
                            value = withContext(Dispatchers.IO) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.file.path, bounds)
                                bounds.inJustDecodeBounds = false
                                bounds.inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= 768 }
                                BitmapFactory.decodeFile(file.file.path, bounds)
                            }
                        }
                        bitmap?.let { Image(it.asImageBitmap(), file.attachment.filename, Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(file.attachment.filename, modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    MemosIconButton("paperclip", "打开附件", onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file.file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { localError = "没有可用的附件查看应用，可另存本机后导出" }
                    })
                    }
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemosButton("离线保存", "download", modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = { model.saveOffline(memo) })
                    MemosButton("另存设备草稿", "copy", modifier = Modifier.fillMaxWidth(), enabled = !state.busy && cached != null, onClick = { model.localCopy(memo, copy) })
                } }
            }
        }
        state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground) } }
        localError?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
    }
}
