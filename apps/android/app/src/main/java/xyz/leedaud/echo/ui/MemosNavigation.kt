package xyz.leedaud.echo.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import xyz.leedaud.echo.notes.*

@Composable fun MemosBrand() {
    val context = LocalContext.current
    val bitmap = remember { context.assets.open("memos-logo.webp").use(BitmapFactory::decodeStream) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
        Text("Memos", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable fun MemosHeader(detail: Boolean, navigate: () -> Unit, back: () -> Unit,
    safeInsets: WindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)) {
    Column(Modifier.testTag("header-safe-area").windowInsetsPadding(safeInsets)) {
        Row(Modifier.fillMaxWidth().height(47.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MemosIconButton(if (detail) "chevron-left" else "menu", if (detail) "返回" else "打开导航",
                Modifier.testTag(if (detail) "back" else "navigation"), size = 32, onClick = if (detail) back else navigate)
            MemosBrand()
        }
        HorizontalDivider(color = LocalMemosPalette.current.border.copy(alpha = .7f))
    }
}

@Composable fun MemosSidebar(state: UiState, page: String, go: (String) -> Unit, create: () -> Unit,
    search: (String) -> Unit, draft: (Draft) -> Unit) {
    val p = LocalMemosPalette.current
    var collectionOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxHeight().background(p.sidebar)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MemosBrand(); Spacer(Modifier.weight(1f))
            MemosIconButton("square-pen", "新笔记", onClick = create)
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(Modifier.background(p.muted, RoundedCornerShape(6.dp)).clickable { collectionOpen = true }.height(28.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemosIcon("house"); Text(if (page in listOf("归档", "置顶")) page else "主页", fontSize = 13.sp); MemosIcon("chevron-down", 12)
                }
                DropdownMenu(collectionOpen, { collectionOpen = false }, containerColor = p.popover) {
                    listOf("首页" to "主页", "笔记" to "所有笔记", "置顶" to "置顶", "归档" to "归档").forEach { (route, title) ->
                        DropdownMenuItem(text = { Text(title, fontSize = 13.sp) }, modifier = Modifier.testTag("tab-$route"),
                            onClick = { collectionOpen = false; go(route) })
                    }
                }
            }
            listOf("日历" to "calendar-days", "附件" to "paperclip").forEach { (title, icon) ->
                if (page == title || (title == "首页" && page == "笔记")) {
                    Row(Modifier.background(p.muted, RoundedCornerShape(6.dp)).clickable { go(title) }.height(28.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MemosIcon(icon); Text(title, fontSize = 13.sp)
                    }
                } else MemosIconButton(icon, title, Modifier.testTag("tab-$title"), size = 28, onClick = { go(title) })
            }
            Spacer(Modifier.weight(1f))
            MemosIconButton("search", "搜索", Modifier.testTag("search-open"), onClick = { search("") })
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = p.border.copy(alpha = .7f))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page != "日历") MemosCalendar(state.notes, "", compact = true) { search(it) }
            Text("视图", fontSize = 13.sp, color = p.mutedForeground, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            SidebarRow("任务", "list-todo", page == "任务") { go("任务") }
            val tags = state.notes.flatMap { Regex("(?<!\\w)#([\\p{L}\\p{N}_/-]+)").findAll(it.body).map { m -> "#${m.groupValues[1]}" }.toList() }.distinct()
            if (tags.isNotEmpty()) {
                Text("标签", fontSize = 13.sp, color = p.mutedForeground, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                tags.forEach { tag -> SidebarRow(tag, "hash", false) { search(tag) } }
            }
            if (state.drafts.isNotEmpty()) {
                Text("设备草稿", fontSize = 13.sp, color = p.mutedForeground, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                state.drafts.forEach { item -> SidebarRow(item.body.ifBlank { "附件草稿" }.take(36), "file-text", false) { draft(item) } }
            }
        }
        HorizontalDivider(color = p.border.copy(alpha = .7f))
        SidebarRow("本机", "settings", page == "设置", Modifier.padding(horizontal = 12.dp).testTag("tab-设置")) { go("设置") }
    }
}

@Composable private fun SidebarRow(label: String, icon: String, active: Boolean, modifier: Modifier = Modifier, click: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (active) LocalMemosPalette.current.muted else androidx.compose.ui.graphics.Color.Transparent,
        RoundedCornerShape(6.dp)).clickable(onClick = click).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MemosIcon(icon); Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
