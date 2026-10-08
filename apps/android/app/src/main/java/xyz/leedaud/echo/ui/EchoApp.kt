package xyz.leedaud.echo.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.TextView
import android.widget.Toast
import android.view.GestureDetector
import android.view.MotionEvent
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.LineHeightSpan
import android.text.style.ClickableSpan
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import kotlinx.coroutines.*
import xyz.leedaud.echo.notes.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EchoApp(model: NoteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("首页") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var theme by rememberSaveable { mutableStateOf("系统") }
    var detail by remember { mutableStateOf<Note?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun notify(message: String) {
        toast?.cancel()
        toast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state.message, state.ready) { if (state.ready) state.message?.let { notify(it); model.dismissMessage() } }
    DisposableEffect(Unit) { onDispose { toast?.cancel() } }
    LaunchedEffect(state.ready, owner) {
        if (state.ready) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { delay(4000); model.refreshStatuses() }
        }
    }
    fun go(next: String) { model.stash(); detail = null; page = next; scope.launch { drawer.close() } }
    MemosTheme(dark = theme == "深色" || (theme == "系统" && isSystemInDarkTheme())) {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            BoxWithConstraints {
                ModalDrawerSheet(modifier = Modifier.width(minOf(288.dp, maxWidth - 32.dp)), drawerContainerColor = LocalMemosPalette.current.sidebar,
                    drawerShape = RoundedCornerShape(0.dp)) {
                    MemosSidebar(state, page, ::go, { go("首页"); model.newNote() }, { query = it; searchOpen = it.isBlank(); go("搜索") },
                        { go("首页"); model.openDraft(it) })
                }
            }
        }) {
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), containerColor = MaterialTheme.colorScheme.background,
            topBar = { MemosHeader(detail != null, { scope.launch { drawer.open() } }, { detail = null }) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (!state.ready) {
                    Column(Modifier.padding(24.dp)) {
                        Text("正在恢复本机记录…")
                        state.message?.let { Text(it); TextButton(onClick = { model.initialize() }, enabled = !state.busy) { Text("重试恢复") } }
                    }
                } else if (detail != null) {
                    val note = state.notes.find { it.id == detail!!.id } ?: detail!!
                    BackHandler { detail = null }
                    NoteDetail(note, state, model, { detail = null; model.edit(note); page = "首页" }, ::notify, { detail = it })
                } else when (page) {
                    "设置" -> Settings(state, model, theme) { theme = it }
                    else -> NoteList(state, model, { detail = it }, { model.edit(it); page = "首页" },
                        { model.openDraft(it); page = "首页" }, page, query, { query = it }, ::notify)
                }
            }
        }
        }
        if (searchOpen) AlertDialog(onDismissRequest = { searchOpen = false }, title = { Text("搜索") },
            text = { OutlinedTextField(query, { query = it }, placeholder = { Text("搜索笔记…") }, singleLine = true, modifier = Modifier.testTag("quick-find")) },
            confirmButton = { TextButton(onClick = { searchOpen = false }) { Text("搜索") } },
            dismissButton = { TextButton(onClick = { searchOpen = false }) { Text("关闭") } })
    }
}

@Composable internal fun Editor(state: UiState, model: NoteViewModel, notify: (String) -> Unit) {
    val context = LocalContext.current
    val draft = state.draft
    val focusManager = LocalFocusManager.current
    var value by remember(draft.id) { mutableStateOf(TextFieldValue(draft.body)) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var insertOpen by remember { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var locationOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var attachmentOwner by rememberSaveable { mutableStateOf(draft.id) }
    fun stopRecording(automatic: Boolean = false) {
        val recorder = recording ?: return
        val target = recordingFile ?: return
        recording = null; recordingFile = null
        try {
            val stopped = runCatching { recorder.stop() }.isSuccess
            if (stopped || automatic) model.importRecording(target.inputStream(), "${timestamp(System.currentTimeMillis())}.m4a")
            else notify("录音太短或未完成，请重新录制")
        } catch (_: Exception) { notify("录音未完成，请重新录制") }
        finally { recorder.release() }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.importUris(uris, attachmentOwner)
    }
    fun update(next: TextFieldValue) {
        if (next.text.toByteArray(Charsets.UTF_8).size > NoteFormat.TEXT_LIMIT) { notify("正文最多 256 KiB，请拆分笔记"); return }
        value = next
        val current = model.state.value.draft
        if (current.id == draft.id && current.body != next.text) model.change(current.copy(body = next.text))
    }
    fun format(prefix: String, suffix: String = "") {
        val first = minOf(value.selection.start, value.selection.end)
        val last = maxOf(value.selection.start, value.selection.end)
        val inserted = prefix + value.text.substring(first, last) + suffix
        update(value.copy(text = value.text.replaceRange(first, last, inserted), selection = TextRange(first + prefix.length, first + inserted.length - suffix.length)))
    }
    fun startRecording() {
        if (recording != null) return
        try {
            val target = File(context.cacheDir, "recording-${newId()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(target.absolutePath)
            recorder.setMaxFileSize(9 * 1024 * 1024L)
            recorder.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording(true) }
            recorder.prepare(); recorder.start()
            recording = recorder; recordingFile = target
            notify("正在录音，点击停止后保留到本机")
        } catch (_: Exception) { notify("录音无法启动，请检查系统权限") }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else notify("未允许录音，可继续记录文字")
    }
    val scope = rememberCoroutineScope()
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) {
            scope.launch {
                val point = withContext(Dispatchers.IO) {
                    try {
                        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
                    } catch (_: SecurityException) { null }
                }
                if (point == null) notify("暂时没有位置，请使用手动输入")
                else {
                    val current = model.state.value.draft
                    if (current.id == draft.id) model.change(current.copy(location = "${point.latitude}, ${point.longitude}"))
                }
            }
        } else notify("未允许定位，可手动输入位置")
    }
    DisposableEffect(Unit) { onDispose { recording?.let { runCatching { it.stop() }; it.release() }; recording = null } }
    LaunchedEffect(draft.body) {
        if (value.text != draft.body) value = TextFieldValue(draft.body, TextRange(draft.body.length))
    }
    if (locationOpen) AlertDialog(onDismissRequest = { locationOpen = false }, title = { Text("位置") },
        text = { Column {
            OutlinedTextField(value = draft.location ?: "", onValueChange = { model.change(draft.copy(location = it.takeIf(String::isNotBlank))) },
                label = { Text("位置") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("获取当前位置") }
        } }, confirmButton = { TextButton(onClick = { locationOpen = false }) { Text("确认") } })
    MemosCard(Modifier.testTag("editor-${draft.id}")) {
        draft.parentId?.let { parent -> Text("续写自：${state.notes.find { it.id == parent }?.let { displayTime(it.created) } ?: "原笔记"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (formatting) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("标题", "heading", "## " to ""), Triple("加粗", "bold", "**" to "**"), Triple("斜体", "italic", "*" to "*"),
                Triple("代码", "code", "`" to "`"), Triple("列表", "list", "- " to ""), Triple("引用", "quote", "> " to ""),
                Triple("链接", "link", "[" to "](https://)"), Triple("待办", "list-todo", "- [ ] " to "")).forEach { (label, icon, markers) ->
                MemosIconButton(icon, label, enabled = !preview && !state.busy, onClick = { format(markers.first, markers.second) })
            }
        }
        }
        if (preview) MarkdownPreview(value.text) else BasicTextField(value = value, onValueChange = { update(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 400.dp).testTag("note-editor"), enabled = !state.busy,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalMemosPalette.current.foreground),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalMemosPalette.current.foreground),
            decorationBox = { inner -> Box { if (value.text.isEmpty()) Text("此刻的想法…", color = LocalMemosPalette.current.mutedForeground); inner() } })
        if (recording != null) Row(verticalAlignment = Alignment.CenterVertically) {
            MemosIcon("mic"); Text("正在录音", Modifier.weight(1f).padding(start = 8.dp))
            MemosButton("停止录音", null, onClick = { stopRecording() })
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val separator = if (maxWidth < 280.dp * fontScale) "\n" else "；"
            Text(if (state.busy) "正在保存…" else if (state.draftSaved) "编辑中 · 尚未保存为笔记${separator}暂存仅在当前设备" else "正在保留设备草稿…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (draft.attachments.isEmpty() && draft.location == null) Spacer(Modifier.height(0.dp))
        draft.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground) }
        Attachments(draft.attachments, model, notify, remove = { attachment ->
            val body = value.text.replace(Regex("!?\\[[^]]*]\\(echo-attachment:${Regex.escape(attachment.id)}\\)"), "")
            value = TextFieldValue(body, TextRange(body.length))
            model.change(draft.copy(body = body, attachments = draft.attachments.filterNot { it.id == attachment.id }))
        }) { attachment ->
            val text = if (attachment.mime.startsWith("image/")) "![](echo-attachment:${attachment.id})" else "[${attachment.name}](echo-attachment:${attachment.id})"
            format(text)
        }
        Row(Modifier.fillMaxWidth().border(1.dp, LocalMemosPalette.current.border.copy(alpha = .7f), RoundedCornerShape(8.dp)).padding(3.dp)) {
            listOf(false to "笔记", true to "待办").forEach { (todo, label) ->
                TextButton(onClick = {
                    val body = if (todo) NoteFormat.toTodo(value.text) else NoteFormat.fromTodo(value.text)
                    if (body.toByteArray(Charsets.UTF_8).size > NoteFormat.TEXT_LIMIT) { notify("转换后正文超过 256 KiB，请拆分笔记"); return@TextButton }
                    value = TextFieldValue(body, TextRange(body.length)); model.change(draft.copy(body = body, todo = todo))
                }, enabled = !state.busy, shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("kind-$label"),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (draft.todo == todo) LocalMemosPalette.current.muted else Color.Transparent)) { Text(label) }
            }
        }
        if (draft.baseRevision > 0 || draft.parentId != null) MemosButton("取消", null, modifier = Modifier.fillMaxWidth(), onClick = { model.newNote() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MemosButton("暂存", "archive", Modifier.weight(1f).testTag("stash"), enabled = !state.busy && recording == null,
                onClick = { focusManager.clearFocus(); model.stash() })
            Box {
                MemosInsertButton(Modifier.testTag("insert"), enabled = !state.busy, onClick = { insertOpen = true })
                DropdownMenu(insertOpen, { insertOpen = false }, containerColor = LocalMemosPalette.current.popover) {
                    MemosMenuItem("图片", "image") { insertOpen = false; attachmentOwner = draft.id; filePicker.launch(arrayOf("image/*")) }
                    MemosMenuItem("附件", "paperclip") { insertOpen = false; attachmentOwner = draft.id; filePicker.launch(arrayOf("*/*")) }
                    MemosMenuItem(if (recording == null) "录音" else "停止录音", "mic") {
                        insertOpen = false; if (recording == null) microphone.launch(Manifest.permission.RECORD_AUDIO) else stopRecording()
                    }
                    MemosMenuItem("位置", "map-pin") { insertOpen = false; locationOpen = true }
                    HorizontalDivider()
                    MemosMenuItem("格式工具栏", "type") { insertOpen = false; formatting = !formatting }
                    MemosMenuItem(if (preview) "编辑" else "预览", "eye") { insertOpen = false; preview = !preview }
                }
            }
            MemosButton(if (draft.baseRevision > 0) "更新" else "保存", "save", Modifier.weight(1f).testTag("save"),
                enabled = !state.busy && recording == null && (value.text.isNotBlank() || draft.attachments.isNotEmpty()),
                onClick = { focusManager.clearFocus(); model.save() })
        }
    }
}

@Composable private fun MemosMenuItem(label: String, icon: String, click: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, leadingIcon = { MemosIcon(icon) }, onClick = click,
        modifier = Modifier.heightIn(min = 44.dp))
}

@Composable internal fun MemosCalendar(notes: List<Note>, selected: String, compact: Boolean = false, select: (String) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now(shanghai)) }
    val days = notes.groupingBy { Instant.ofEpochMilli(it.created).atZone(shanghai).toLocalDate() }.eachCount()
    val start = month.atDay(1)
    val p = LocalMemosPalette.current
    val maxCount = (days.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val today = LocalDate.now(shanghai)
    LaunchedEffect(month) { if (!compact) select(if (month == YearMonth.from(today)) today.toString() else month.atDay(1).toString()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 32.dp else 44.dp).padding(start = if (compact) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium)
            MemosIconButton("chevron-left", "上月", size = if (compact) 28 else 44, onClick = { month = month.minusMonths(1) })
            MemosIconButton("chevron-right", "下月", size = if (compact) 28 else 44, onClick = { month = month.plusMonths(1) })
            if (!compact) TextButton(onClick = { month = YearMonth.from(today); select(today.toString()) }) { Text("今天") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (compact) Arrangement.spacedBy(4.dp) else Arrangement.Start) { listOf("日", "一", "二", "三", "四", "五", "六").forEach {
            Box(Modifier.weight(1f).height(if (compact) 26.dp else 32.dp), contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart) {
                Text(it, Modifier.then(if (compact) Modifier else Modifier.padding(start = 12.dp)), fontSize = if (compact) 11.sp else 13.sp,
                    color = p.mutedForeground.copy(alpha = if (compact) .5f else 1f))
            }
        } }
        val offset = start.dayOfWeek.value % 7
        Column(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.border(1.dp, p.border.copy(alpha = .7f), RoundedCornerShape(8.dp))),
            verticalArrangement = if (compact) Arrangement.spacedBy(4.dp) else Arrangement.Top) {
        for (week in 0 until ((month.lengthOfMonth() + offset + 6) / 7)) Row(horizontalArrangement = if (compact) Arrangement.spacedBy(4.dp) else Arrangement.Start) {
            for (weekday in 0..6) {
                val day = week * 7 + weekday - offset + 1
                val date = start.plusDays((day - 1).toLong()); val count = days[date] ?: 0
                val current = YearMonth.from(date) == month
                if (compact) {
                    val ratio = count.toFloat() / maxCount
                    val alpha = when { count <= 0 -> 0f; ratio > .75f -> .30f; ratio > .50f -> .20f; ratio > .25f -> .12f; else -> .06f }
                    val picked = selected == date.toString()
                    Box(Modifier.weight(1f).height(30.dp).clickable(enabled = current) { select(date.toString()) }
                        .semantics { contentDescription = "${date}，${count}条笔记"; this.selected = picked }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(30.dp).background(if (!current) Color.Transparent else if (picked) p.foreground else p.foreground.copy(alpha = alpha), RoundedCornerShape(6.dp))
                            .testTag("calendar-chip-$date"), contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString(), fontSize = 12.sp,
                                color = if (!current) p.mutedForeground.copy(alpha = .25f) else if (picked) p.background else p.foreground.copy(alpha = if (count > 0) .9f else .75f))
                        }
                    }
                    continue
                }
                Column(Modifier.weight(1f).heightIn(min = 56.dp).background(if (selected == date.toString()) p.muted else p.card)
                    .border(.5.dp, p.border.copy(alpha = .7f)).clickable { select(date.toString()) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(date.dayOfMonth.toString(), fontSize = 13.sp, color = if (current) p.foreground else p.mutedForeground.copy(alpha = .5f))
                    if (count > 0) Text("$count", fontSize = 11.sp, color = p.mutedForeground)
                }
            }
        }
        }
    }
}

internal fun deliveryLabel(job: Delivery?): String = when {
    job?.receipt != null && job.state == "verified" -> "已投递"
    job == null || job.state in setOf("auth_required", "conflict", "retryable_error", "paused") -> "已保存"
    else -> "投递中"
}

@Composable private fun NoteDetail(note: Note, state: UiState, model: NoteViewModel, edit: () -> Unit, notify: (String) -> Unit, open: (Note) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = state.jobs.find { it.noteId == note.id && it.revision == note.revision }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = exportBytes; exportBytes = null
        if (uri != null && bytes != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开所选目标") }; notify("已导出正文和附件") }
            catch (_: Exception) { notify("导出未完成，请重试") }
        }
    }
    fun exportNote() {
        scope.launch {
            try {
                exportBytes = withContext(Dispatchers.IO) {
                    val output = java.io.ByteArrayOutputStream()
                    ZipOutputStream(output).use { zip -> model.exportFiles(note).forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                    } }
                    output.toByteArray()
                }
                exporter.launch("Echo-${timestamp(note.created)}.zip")
            } catch (_: Exception) { notify("导出准备失败，本机内容保留") }
        }
    }
    val p = LocalMemosPalette.current
    fun shareImage() {
        scope.launch {
            try {
                val image = withContext(Dispatchers.IO) { ShareImage.create(note.body,
                    note.attachments.filter { it.mime.startsWith("image/") }.map { model.privateFile(it.file) },
                    p.card.toArgbCompat(), p.foreground.toArgbCompat()) }
                val target = withContext(Dispatchers.IO) { File(context.cacheDir, "share/note-${newId()}.png").apply {
                    parentFile!!.mkdirs(); outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享笔记图片"))
            } catch (_: Exception) { notify("图片导出未完成。长笔记可使用 Markdown 导出。") }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("移出本机列表？") },
        text = { Text("仅隐藏本机记录，不删除 GitHub 文件。投递未确认的记录会保留。") },
        confirmButton = { TextButton(onClick = { model.updateNote(note.copy(deleted = true)); deleting = false }) { Text("确认移出") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("关闭") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        MemosCard {
            MemoHeader(note, job, {}, model::refreshStatuses) {
                if (note.pinned) MemosIconButton("bookmark", "取消置顶", onClick = { model.updateNote(note.copy(pinned = false)) })
                Box {
                    MemosIconButton("ellipsis-vertical", "笔记操作", Modifier.testTag("detail-menu"), size = 24, onClick = { menu = true })
                    DropdownMenu(menu, { menu = false }, containerColor = p.popover) {
                        fun action(block: () -> Unit) { menu = false; block() }
                        MemosMenuItem(if (note.frozen) "续写" else "编辑", "square-pen") { action(edit) }
                        MemosMenuItem(if (note.pinned) "取消置顶" else "置顶", "bookmark") { action { model.updateNote(note.copy(pinned = !note.pinned)) } }
                        MemosMenuItem(if (note.archived) "恢复" else "归档", "archive") { action { model.updateNote(note.copy(archived = !note.archived)) } }
                        if (job == null && !note.frozen) MemosMenuItem("投递当前版本", "save") { action { model.enqueue(note) } }
                        else if (job != null && job.state != "verified") MemosMenuItem("重试", "save") { action { model.retry(job) } }
                        HorizontalDivider()
                        MemosMenuItem("复制", "copy") { action {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Echo", note.body))
                            notify("正文已复制")
                        } }
                        MemosMenuItem("分享", "share-2") { action {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, note.body) }, "分享正文"))
                        } }
                        MemosMenuItem("导出 Markdown 和附件", "download") { action(::exportNote) }
                        MemosMenuItem("分享图片", "image") { action(::shareImage) }
                        MemosMenuItem("移出列表", "x") { action { deleting = true } }
                    }
                }
            }
            MarkdownPreview(note.body)
            Attachments(note.attachments, model, notify)
            note.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.mutedForeground) }
            NoteRelations(note, state.notes, open)
        }
    }
}

@Composable internal fun Attachments(items: List<AttachmentRef>, model: NoteViewModel, notify: (String) -> Unit,
                                    remove: ((AttachmentRef) -> Unit)? = null, insert: ((AttachmentRef) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    items.forEach { attachment ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (attachment.mime.startsWith("image/")) {
                val bitmap by produceState<Bitmap?>(null, attachment.id) {
                    value = withContext(Dispatchers.IO) {
                        val file = model.privateFile(attachment.file)
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        options.inJustDecodeBounds = false
                        options.inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(options.outWidth, options.outHeight) / it <= 384 }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    }
                }
                bitmap?.let { Image(it.asImageBitmap(), attachment.name,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 240.dp).clickable {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", model.privateFile(attachment.file))
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { notify("没有可用的图片查看应用，可通过分享或附件导出查看") }
                    }, contentScale = ContentScale.Fit) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(attachment.name.take(32), Modifier.padding(top = 12.dp))
                if (insert != null) TextButton(onClick = { insert(attachment) }) { Text("插入正文") }
                if (remove != null) TextButton(onClick = { remove(attachment) }) { Text("移除") }
                if (attachment.mime.startsWith("audio/")) TextButton(onClick = {
                    try {
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(model.privateFile(attachment.file).absolutePath)
                            setOnPreparedListener { it.start() }; prepareAsync()
                        }
                    } catch (_: Exception) { notify("音频无法播放") }
                }) { Text("播放") }
                if (attachment.mime.startsWith("audio/") && player != null) TextButton(onClick = { runCatching { player?.stop() }; player?.release(); player = null }) { Text("停止") }
                TextButton(onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", model.privateFile(attachment.file))
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = attachment.mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "分享附件"))
                    } catch (_: Exception) { notify("暂时无法打开系统分享") }
                }) { Text("分享") }
            }
        }
    }
}

@Composable internal fun MarkdownPreview(text: String, onDoubleTap: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val foreground = LocalContentColor.current
    val context = LocalContext.current
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val renderer = remember(context) {
        Markwon.builder(context).usePlugin(TaskListPlugin.create(context)).usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = Uri.parse(link)
                        if (uri.scheme?.lowercase() in setOf("https", "mailto", "tel", "obsidian")) {
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        }
                    }
                }
            }).build()
    }
    AndroidView(factory = { context -> MemoTextView(context).apply {
        textSize = 16f; includeFontPadding = false; setLineSpacing(0f, 1.5f); setTextIsSelectable(true)
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                val offset = getOffsetForPosition(event.x, event.y)
                val styled = this@apply.text as? Spanned
                val spans = styled?.getSpans(offset, offset, Any::class.java).orEmpty()
                if (spans.any { it is ClickableSpan || it.javaClass.simpleName in setOf("CodeSpan", "CodeBlockSpan", "AsyncDrawableSpan", "ImageSpan") }) return false
                if (event.x < 24 * resources.displayMetrics.density && spans.any { it.javaClass.simpleName == "TaskListSpan" }) return false
                doubleTap?.invoke() ?: return false
                return true
            }
        })
        setOnTouchListener { view, event ->
            val handled = detector.onTouchEvent(event)
            if (handled) view.performClick()
            handled
        }
    } }, update = { view ->
        view.setTextColor(foreground.toArgbCompat())
        view.setLinkTextColor(colors.primary.toArgbCompat())
        if (view.tag != text) {
            val rendered = SpannableStringBuilder(renderer.toMarkdown(text))
            // CommonMark's empty separator line should match the web's 8px paragraph gap.
            Regex("\n\n").findAll(rendered).forEach { match ->
                rendered.setSpan(object : LineHeightSpan {
                    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: android.graphics.Paint.FontMetricsInt) {
                        fm.ascent = -(8 * view.resources.displayMetrics.density / 1.5f).toInt()
                        fm.top = fm.ascent; fm.descent = 0; fm.bottom = 0
                    }
                }, match.range.first + 1, match.range.first + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            renderer.setParsedMarkdown(view, rendered); view.tag = text
        }
    }, modifier = Modifier.fillMaxWidth())
}
private class MemoTextView(context: Context) : TextView(context) {
    override fun performClick(): Boolean { super.performClick(); return true }
}
private fun Color.toArgbCompat(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

@Composable private fun Settings(state: UiState, model: NoteViewModel, theme: String, setTheme: (String) -> Unit) {
    var owner by remember(state.target?.id) { mutableStateOf(state.target?.owner ?: "") }
    var repo by remember(state.target?.id) { mutableStateOf(state.target?.repo ?: "") }
    var branch by remember(state.target?.id) { mutableStateOf(state.target?.branch ?: "main") }
    var token by remember { mutableStateOf("") }
    var enabled by remember(state.target?.enabled) { mutableStateOf(state.target?.enabled ?: false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("系统" to "monitor", "浅色" to "sun", "深色" to "moon").forEach { (label, icon) ->
                MemosButton(label, icon, Modifier.weight(1f).testTag("theme-$label"), enabled = label != theme, onClick = { setTheme(label) })
            }
        }
        HorizontalDivider(color = LocalMemosPalette.current.border.copy(alpha = .7f))
        Text("GitHub", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(owner, { owner = it.trim() }, label = { Text("仓库所有者") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(repo, { repo = it.trim() }, label = { Text("仓库名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(branch, { branch = it.trim() }, label = { Text("已有分支") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text(if (state.target != null) "新令牌（留空沿用当前目标授权）" else "GitHub 授权令牌") },
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("开启投递"); Text("保存后投递到 GitHub", style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground) }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        MemosButton("检查连接并保存配置", "save", enabled = !state.busy, onClick = { model.configure(owner, repo, branch, token, enabled); token = "" })
        state.target?.let { target ->
            Text("当前：${target.owner}/${target.repo} · ${target.branch}")
            OutlinedButton(onClick = { model.setEnabled(!target.enabled) }, enabled = !state.busy) { Text(if (target.enabled) "暂停投递" else "恢复投递") }
        }
    }
}

private object ShareImage {
    fun create(text: String, images: List<File>, background: Int, foreground: Int): Bitmap {
        require(text.length <= 12000) { "长笔记请使用 Markdown 导出" }
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = 32f }
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, 984)
            .setLineSpacing(8f, 1f).build()
        val heights = images.map { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法渲染，请使用附件导出" }
            minOf(1000, (984f * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1))
        }
        val height = layout.height + 96 + heights.sumOf { it + 24 }
        require(height <= 8000) { "图片过高，请使用 Markdown 导出" }
        val bitmap = Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(background); save(); translate(48f, 48f); layout.draw(this); restore()
            var top = layout.height + 72f
            images.forEachIndexed { index, file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inJustDecodeBounds = false
                options.inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(options.outWidth, options.outHeight) / it <= 1024 }
                val image = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("图片无法渲染")
                drawBitmap(image, null, android.graphics.RectF(48f, top, 1032f, top + heights[index]), null)
                image.recycle(); top += heights[index] + 24
            }
        }
        return bitmap
    }
}
