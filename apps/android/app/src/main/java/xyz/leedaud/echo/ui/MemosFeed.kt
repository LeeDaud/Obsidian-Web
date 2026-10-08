package xyz.leedaud.echo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.leedaud.echo.notes.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext

@Composable internal fun NoteList(state: UiState, model: NoteViewModel, detail: (Note) -> Unit, edit: (Note) -> Unit,
    openDraft: (Draft) -> Unit, page: String, query: String, changeQuery: (String) -> Unit, notify: (String) -> Unit) {
    var date by rememberSaveable(page) { mutableStateOf("") }
    val notes = state.notes.filter { it.archived == (page == "归档") && (page != "置顶" || it.pinned) && (page != "任务" || it.todo)
        && (page != "搜索" || query.isBlank() || if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(query)) displayTime(it.created).startsWith(query) else it.body.contains(query, true))
        && (date.isBlank() || displayTime(it.created).startsWith(date)) }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.created })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 672.dp).fillMaxSize().testTag("memo-feed"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val inlineNote = if (state.draft.baseRevision > 0) notes.find { it.id == state.draft.id }
            else notes.find { it.id == state.draft.parentId }
        if (page == "首页" && inlineNote == null) item(key = "compose") { Editor(state, model, notify) }
        if (page == "搜索") item {
            OutlinedTextField(query, changeQuery, placeholder = { Text("搜索笔记…") }, leadingIcon = { MemosIcon("search") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("search-query"))
        }
        if (page == "归档" || page == "置顶" || page == "附件") item {
            Text(page, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (page == "日历") item {
            MemosCalendar(state.notes, date) { date = it }
            if (date.isNotBlank()) Text(date, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (page == "附件") {
            val attachments = state.notes.flatMap { it.attachments }.distinctBy { it.id }
            items(attachments, key = { it.id }) { attachment -> MemosCard { Attachments(listOf(attachment), model, notify) } }
            if (attachments.isEmpty()) item { EmptyMemos() }
        } else {
            if (notes.isEmpty()) item { EmptyMemos() }
            items(notes, key = { it.id }) { note ->
                if (inlineNote?.id == note.id && page == "首页") {
                    // Edit in the original card's slot, as the web MemoView does.
                    Editor(state, model, notify)
                } else MemoCard(note, state.jobs.find { it.noteId == note.id && it.revision == note.revision },
                    model, { detail(note) }, { edit(note) }, notify, state.notes, detail)
            }
        }
    }
    }
}

@Composable private fun EmptyMemos() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text("暂无数据", fontSize = 14.sp, color = LocalMemosPalette.current.mutedForeground)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun MemoCard(note: Note, job: Delivery?, model: NoteViewModel, detail: () -> Unit, edit: () -> Unit, notify: (String) -> Unit,
    relatedNotes: List<Note> = emptyList(), openRelated: (Note) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    MemosCard(Modifier.testTag("memo-${note.id}")) {
        MemoHeader(note, job, detail, model::refreshStatuses) {
            if (note.pinned) MemosIconButton("bookmark", "取消置顶", onClick = { model.updateNote(note.copy(pinned = false)) })
            Box {
                MemosIconButton("ellipsis-vertical", "笔记操作", Modifier.testTag("memo-menu-${note.id}"), size = 24, onClick = { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = LocalMemosPalette.current.popover) {
                    fun action(block: () -> Unit) { menu = false; block() }
                    FeedMenuItem(if (note.pinned) "取消置顶" else "置顶", "bookmark") { action { model.updateNote(note.copy(pinned = !note.pinned)) } }
                    FeedMenuItem(if (note.frozen) "续写" else "编辑", "square-pen") { action(edit) }
                    FeedMenuItem("复制内容", "copy") { action {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Echo", note.body))
                        notify("正文已复制")
                    } }
                    FeedMenuItem(if (note.archived) "恢复" else "归档", "archive") { action { model.updateNote(note.copy(archived = !note.archived)) } }
                    if (job?.state != "verified" && !note.frozen && job == null) FeedMenuItem("投递当前版本", "save") { action { model.enqueue(note) } }
                    if (job != null && job.state != "verified") FeedMenuItem("重试", "save") { action { model.retry(job) } }
                    FeedMenuItem("详情", "file-text") { action(detail) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag("memo-body-${note.id}")) { MarkdownPreview(note.body, edit) }
        Attachments(note.attachments, model, notify)
        note.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground) }
        NoteRelations(note, relatedNotes, openRelated)
    }
}

@Composable internal fun NoteRelations(note: Note, notes: List<Note>, open: (Note) -> Unit) {
    val parent = notes.find { it.id == note.parentId }
    val children = notes.filter { it.parentId == note.id }
    if (parent != null || children.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOfNotNull(parent?.let { "续写自" to it }).plus(children.map { "续写" to it }).forEach { (label, related) ->
            TextButton(onClick = { open(related) }, modifier = Modifier.fillMaxWidth().testTag("relation-${note.id}-${related.id}"),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                MemosIcon("link", 14); Spacer(Modifier.width(6.dp))
                Text("$label：${displayTime(related.created)} · ${related.body.lineSequence().firstOrNull().orEmpty()}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else if (note.parentId != null) Text("续写自：关联笔记不在当前列表", style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground)
}

@Composable internal fun MemoHeader(note: Note, job: Delivery?, detail: () -> Unit, refresh: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    var statusOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(displayTime(note.created).replace('-', '/'), style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable(onClick = detail).padding(vertical = 12.dp))
        Box {
            TextButton({ statusOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 44.dp)) {
                Text(deliveryLabel(job), style = MaterialTheme.typography.bodySmall, color = LocalMemosPalette.current.mutedForeground)
            }
            DropdownMenu(statusOpen, { statusOpen = false }, modifier = Modifier.widthIn(max = 288.dp), containerColor = LocalMemosPalette.current.popover) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(job?.error ?: when (job?.state) {
                        "verified" -> "GitHub 正文与附件已核验"
                        "waiting_parent" -> "等待前一条笔记投递完成。"
                        null -> if (note.frozen) "缺少投递凭证，原版本保持冻结。" else "已保存到本机。"
                        else -> "已进入投递队列，等待仓库核验完成。"
                    }, style = MaterialTheme.typography.bodySmall)
                    job?.receipt?.let { Text("仓库文件：${it.path}\n核验提交：${it.commit}", style = MaterialTheme.typography.bodySmall) }
                    Text("电脑 Obsidian 是否已拉取：尚未核验。", style = MaterialTheme.typography.bodySmall)
                    MemosButton("刷新状态", null, onClick = refresh)
                }
            }
        }
        }
        actions()
    }
}

@Composable private fun FeedMenuItem(label: String, icon: String, click: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, leadingIcon = { MemosIcon(icon) }, onClick = click)
}
