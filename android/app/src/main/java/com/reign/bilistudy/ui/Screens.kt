package com.reign.bilistudy.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.reign.bilistudy.AppState
import com.reign.bilistudy.Screen
import com.reign.bilistudy.Tab
import com.reign.bilistudy.core.Catalog
import com.reign.bilistudy.core.Course
import com.reign.bilistudy.core.CourseContext
import com.reign.bilistudy.core.Fmt
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.core.thumbOf
import com.reign.bilistudy.data.AIStatus
import com.reign.bilistudy.data.CatalogStore
import com.reign.bilistudy.data.HistoryEntry
import com.reign.bilistudy.data.SavedCourse
import com.reign.bilistudy.net.AIConfig
import kotlinx.coroutines.launch

@Composable
fun Root(state: AppState) {
    Box(Modifier.fillMaxSize().background(C.paper)) {
        val top = state.stack.lastOrNull()
        when {
            state.grade == null -> WelcomeScreen(state)
            top is Screen.Player -> PlayerScreen(state, top.bvid, top.context)
            top is Screen.Search -> SearchScreen(state, top.query)
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (state.tab) {
                        Tab.CATALOG -> CatalogScreen(state)
                        Tab.MINE -> MineScreen(state)
                        Tab.SETTINGS -> SettingsScreen(state)
                    }
                }
                BottomNav(state)
            }
        }
        if (state.showSyncDialog) Dialog(onDismissRequest = { state.showSyncDialog = false }) {
            Box(Modifier.background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp)) { SyncForm(state) { state.showSyncDialog = false } }
        }
    }
}

/** 页面顶部：一条黑板。左边是随堂的标和页面名，右边是这一页的操作。 */
@Composable
fun BoardBar(
    title: String,
    brand: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LightStatusBarIcons()
    Column(Modifier.fillMaxWidth().background(C.board).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (brand) {
                BrandMark(22.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = kai(21.sp).copy(color = C.chalk), maxLines = 1, modifier = Modifier.weight(1f))
            trailing()
        }
        extra?.invoke(this)
    }
}

/** 黑板上的小按钮：粉笔色的框，用于顶栏里的同步入口。 */
@Composable
fun BoardAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.heightIn(min = 40.dp).border(1.5.dp, C.chalkLine.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.chalk.copy(alpha = if (enabled) 1f else 0.5f)) }
}

/** 底部导航：黑板托盘，当前页用粉笔黄标出。 */
@Composable
private fun BottomNav(state: AppState) {
    Row(Modifier.fillMaxWidth().background(C.board).windowInsetsPadding(WindowInsets.navigationBars).height(62.dp)) {
        listOf(
            Triple(Tab.CATALOG, "课程", Icons.Outlined.LibraryBooks),
            Triple(Tab.MINE, "我的", Icons.Outlined.Person),
            Triple(Tab.SETTINGS, "设置", Icons.Outlined.Tune),
        ).forEach { (tab, label, icon) ->
            val on = state.tab == tab
            Column(
                Modifier.weight(1f).fillMaxSize().semantics { selected = on }
                    .clickable(role = Role.Tab) { state.stack.clear(); state.tab = tab },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, tint = if (on) C.yellow else C.chalk2, modifier = Modifier.size(22.dp))
                Text(label, fontSize = 11.sp, color = if (on) C.yellow else C.chalk2,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** 年级：黑板上的一行田字格，当前年级填粉笔黄。 */
@Composable
fun GradeStrip(state: AppState, active: Int?) {
    val list = rememberLazyListState()
    LaunchedEffect(active) { active?.let { list.scrollToItem((it - 1).coerceAtLeast(0)) } }
    LazyRow(state = list, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items((1..12).toList()) { g ->
            TianZiGe(Taxonomy.gradeShort(g), g == active, 44.dp, if (g > 9) 15.sp else 20.sp, Taxonomy.gradeName(g)) { state.chooseGrade(g) }
        }
    }
}

@Composable
fun CatalogScreen(state: AppState) {
    val g = state.grade ?: 7
    val subject = state.subject(g)
    val volume = state.volume(g, subject)
    val key = CatalogStore.key(g, subject, volume)
    var kind by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) { state.catalogs.load(g, subject, volume) }

    Column(Modifier.fillMaxSize()) {
        BoardBar("随堂", brand = true, trailing = {
            Box(Modifier.size(44.dp).semantics { contentDescription = "搜索" }.clickable(role = Role.Button) { state.push(Screen.Search("")) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, null, tint = C.chalk, modifier = Modifier.size(21.dp))
            }
        }, extra = { GradeStrip(state, g) })

        val catalog = state.catalogs.catalogs[key]
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp)) {
            item { Shelf(state, g, subject) }
            if (catalog == null) {
                item {
                    Column(Modifier.padding(vertical = 48.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val error = state.catalogs.errors[key]
                        if (error != null) {
                            Text(error, color = C.ink2)
                            Spacer(Modifier.height(14.dp))
                            OutlineButton("重试") { state.catalogs.load(g, subject, volume, refresh = true) }
                        } else CircularProgressIndicator(color = C.board, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
                return@LazyColumn
            }

            val status = state.catalogs.aiStatus[key]
            item { FilterRow(state, g, subject, volume, catalog, kind) { kind = it } }

            state.library.history.firstOrNull { it.grade == g && it.subject == subject }?.let { h ->
                item { ResumeCard(state, h, Modifier.padding(top = 16.dp)) }
            }

            val ed = state.edition(catalog.subject)
            val edOk = catalog.items.count { ed in it.editions } >= 2
            val shown = catalog.items.filter { (ed.isEmpty() || !edOk || ed in it.editions) && (kind.isEmpty() || it.ai?.kind == kind) }
            val picks = catalog.picks.toSet()
            val top = shown.filter { it.bvid in picks }
            val rest = shown.filter { it.bvid !in picks }
            val context = CourseContext(catalog.grade, catalog.subject, catalog.volume)

            if (status == AIStatus.Pending) item {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = C.ink3, strokeWidth = 1.5.dp)
                    Text("大模型整理中", fontSize = 13.sp, color = C.ink3, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (shown.isEmpty()) item {
                Text("这个分类下还没有找到合适的视频。", fontSize = 15.sp, color = C.ink2, modifier = Modifier.padding(vertical = 40.dp))
            }

            if (top.isNotEmpty()) {
                item { SectionTitle("推荐", top.size) }
                items(top, key = { "p" + it.bvid }) { c -> PickCard(state, c, context, Modifier.padding(bottom = 12.dp)) }
            }
            listOf("整套课程" to rest.filter { it.series }, "单个讲解" to rest.filter { !it.series }).forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item { SectionTitle(title, list.size) }
                    item {
                        Sheet { list.forEachIndexed { i, c -> CourseRow(state, c, context, first = i == 0) } }
                    }
                }
            }
            item { FilteredNote(catalog, status) }
        }
    }
}

/** 学科：书架上的书脊，竖排书名，当前学科高出一截，顶上夹一条粉笔黄书签。 */
@Composable
private fun Shelf(state: AppState, grade: Int, active: String) {
    val subjects = Taxonomy.subjects(grade)
    val scroll = rememberScrollState()
    LaunchedEffect(active, grade) { scroll.animateScrollTo(subjects.indexOf(active).coerceAtLeast(0) * 50) }
    Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(172.dp)) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom,
        ) {
            subjects.forEachIndexed { i, s ->
                val on = s == active
                val base = C.spineHeights[i % C.spineHeights.size]
                val h by animateDpAsState(if (on) (base + 24).dp else base.dp, label = "spineHeight")
                val w by animateDpAsState(if (on) 50.dp else 44.dp, label = "spineWidth")
                Box {
                    Column(
                        Modifier.width(w).height(h)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                            .background(C.subjects[s] ?: C.board)
                            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.12f))))
                            .semantics { contentDescription = s; selected = on }
                            .clickable(role = Role.Button) { state.chooseSubject(s) }
                            .padding(top = 12.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).background(Color.White.copy(alpha = 0.5f)))
                        Spacer(Modifier.height(8.dp))
                        s.forEach { ch ->
                            Text("$ch", style = kai((if (s.length > 2) 13 else 18).sp).copy(color = Color.White),
                                lineHeight = (if (s.length > 2) 15 else 21).sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(Taxonomy.volumeShort(grade, state.volume(grade, s)), fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                    if (on) Box(Modifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = (-10).dp).size(10.dp, 24.dp).background(C.yellow, RibbonShape))
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(8.dp).background(C.board))
    }
}

/** 册次、版本、内容类型放在一行，放不下时横向滑动。 */
@Composable
private fun FilterRow(state: AppState, grade: Int, subject: String, volume: String, catalog: Catalog, kind: String, onKind: (String) -> Unit) {
    val editions = catalog.items.flatMap { it.editions }.groupingBy { it }.eachCount().filter { it.value >= 2 }.entries.sortedByDescending { it.value }
    val kinds = catalog.items.mapNotNull { it.ai?.kind }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val edition = state.edition(subject).takeIf { e -> editions.any { it.key == e } }.orEmpty()
    Row(
        Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val volumes = Taxonomy.volumes(grade, subject)
        if (volumes.size > 1) Row(
            Modifier.clip(CircleShape).background(Color.White).border(1.dp, C.rule, CircleShape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            volumes.forEach { v ->
                val on = v == volume
                Box(
                    Modifier.heightIn(min = 38.dp).background(if (on) C.board else Color.Transparent, CircleShape)
                        .semantics { selected = on }.clickable(role = Role.Tab) { state.chooseVolume(v); onKind("") }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(v, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Color.White else C.ink2)
                }
            }
        }
        if (editions.isNotEmpty()) {
            Chip("全部", catalog.items.size, edition.isEmpty()) { state.chooseEdition(subject, "") }
            editions.forEach { e -> Chip(e.key, e.value, edition == e.key) { state.chooseEdition(subject, e.key) } }
        }
        if (kinds.size > 1) {
            Box(Modifier.width(1.dp).height(22.dp).background(C.rule))
            kinds.forEach { k -> Chip(k.key, k.value, kind == k.key) { onKind(if (kind == k.key) "" else k.key) } }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 26.dp, bottom = 12.dp).semantics { heading() }, verticalAlignment = Alignment.Bottom) {
        Text(title, style = kai(20.sp))
        Text("  $count", fontSize = 12.sp, color = C.ink3)
    }
}

/** 一张白纸：课程行排在里面，行与行之间一条细线。 */
@Composable
private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(vertical = 4.dp), content = content)
}

@Composable
private fun FilteredNote(catalog: Catalog, status: AIStatus?) {
    var open by remember { mutableStateOf(false) }
    val count = catalog.rejected.sumOf { it.count }
    Column(Modifier.padding(top = 22.dp, bottom = 8.dp)) {
        Text(
            if (count > 0) "已过滤 $count 条" else "整理说明", fontSize = 13.sp, color = C.ink3,
            modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { open = !open }.padding(vertical = 12.dp),
        )
        if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            catalog.rejected.forEach { r ->
                Text("${r.reason}　${r.count} 条", fontSize = 13.sp, color = C.ink2)
                if (r.samples.isNotEmpty()) Text(r.samples.joinToString("；"), fontSize = 13.sp, color = C.ink3)
            }
            Text("搜索词：" + catalog.queries.joinToString("、"), fontSize = 13.sp, color = C.ink3)
            when (status) {
                is AIStatus.Ready -> Text("大模型整理：${status.model}", fontSize = 13.sp, color = C.ink3)
                is AIStatus.Failed -> Text("大模型整理没有完成：${status.message}", fontSize = 13.sp, color = C.ink3)
                is AIStatus.Off -> Text("未启用大模型整理（${status.reason}），当前只按标题规则归类。", fontSize = 13.sp, color = C.ink3)
                else -> {}
            }
        }
    }
}

/**
 * 封面：圆角，右下角是总时长；有进度时底部一条粉笔黄进度条。
 * width 为 null 时铺满可用宽度，推荐卡片用这种。
 */
@Composable
fun Thumb(url: String?, duration: Int?, width: Int? = 112, radius: Dp = 10.dp, progress: Float = 0f, modifier: Modifier = Modifier) {
    Box(
        modifier.then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f).clip(RoundedCornerShape(radius)).background(C.thumbBg),
    ) {
        if (!url.isNullOrEmpty()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (duration != null && duration > 0) Text(
            Fmt.duration(duration), fontSize = 11.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(C.screen.copy(alpha = 0.74f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp).clearAndSetSemantics { },
        )
        if (progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), fill = C.yellow, track = Color.Black.copy(alpha = 0.3f), height = 4.dp)
    }
}

/** 一条课程：封面、标题、简介、作者和集数、标签，右边是收藏。 */
@Composable
fun CourseRow(state: AppState, c: Course, context: CourseContext, first: Boolean = true, whereTag: String? = null) {
    val saved = state.library.isFavorite(c.bvid)
    val seen = state.library.watchedCount(c.bvid)
    val total = state.library.historyEntry(c.bvid)?.total
    val label = listOfNotNull(c.title, c.ai?.summary?.takeIf { it.isNotEmpty() }, c.author,
        if (c.episodes > 0) "${c.episodes} 集" else null, "时长 ${Fmt.hours(c.duration)}").joinToString("，")
    Column {
        if (!first) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(C.rule))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = label }
                    .clickable(role = Role.Button) { state.push(Screen.Player(c.bvid, context)) }
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(c.thumb, c.duration, 112)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
                    c.ai?.summary?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 13.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        listOfNotNull(c.author.ifEmpty { null }, if (c.episodes > 0) "${c.episodes} 集" else null, "${Fmt.count(c.play)} 次播放").joinToString("  "),
                        fontSize = 12.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    CourseTags(c, whereTag)
                    if (seen > 0 && total != null) WatchedCount(seen, total)
                }
            }
            BookmarkButton(saved, (if (saved) "取消收藏：" else "收藏：") + c.title.take(24), onClick = { toggle(state, c, context) })
        }
    }
}

/** 推荐：大封面卡片。标题、老师批注、简介都放在封面上方，右上角是收藏。 */
@Composable
private fun PickCard(state: AppState, c: Course, context: CourseContext, modifier: Modifier = Modifier) {
    val saved = state.library.isFavorite(c.bvid)
    val seen = state.library.watchedCount(c.bvid)
    val total = state.library.historyEntry(c.bvid)?.total ?: 0
    val label = listOfNotNull(c.title, c.ai?.summary?.takeIf { it.isNotEmpty() }, c.ai?.comment?.takeIf { it.isNotEmpty() }, c.author,
        if (c.episodes > 0) "${c.episodes} 集" else null, "时长 ${Fmt.hours(c.duration)}").joinToString("，")
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().card()
                .clearAndSetSemantics { contentDescription = label }
                .clickable(role = Role.Button) { state.push(Screen.Player(c.bvid, context)) },
        ) {
            Thumb(largeThumb(c.cover), c.duration, null, radius = 0.dp, progress = if (total > 0) seen.toFloat() / total else 0f)
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
                c.ai?.comment?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = kai(15.sp).copy(color = C.red), maxLines = 2, lineHeight = 22.sp, overflow = TextOverflow.Ellipsis)
                }
                c.ai?.summary?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 13.sp, color = C.ink2, maxLines = 3, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(c.author.ifEmpty { null }, if (c.episodes > 0) "${c.episodes} 集" else null, "${Fmt.count(c.play)} 次播放").joinToString("  "),
                    fontSize = 12.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                CourseTags(c, null)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            BookmarkButton(saved, (if (saved) "取消收藏：" else "收藏：") + c.title.take(24), onCover = true, onClick = { toggle(state, c, context) })
        }
    }
}

/** 分类页顶部的继续学习：封面、这一节的标题、整门课的进度。 */
@Composable
private fun ResumeCard(state: AppState, h: HistoryEntry, modifier: Modifier = Modifier) {
    val done = state.library.watchedCount(h.bvid)
    Row(
        modifier.fillMaxWidth().card()
            .clearAndSetSemantics { contentDescription = "继续学习：${h.title}，第 ${h.index + 1} 节 ${h.part}，已看完 $done 节，共 ${h.total} 节" }
            .clickable(role = Role.Button) { state.push(Screen.Player(h.bvid, CourseContext(h.grade, h.subject, h.volume))) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbOf(h.cover), null, 104)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(h.part, style = kai(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.title, fontSize = 12.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(done.toFloat() / h.total.coerceAtLeast(1), Modifier.weight(1f), height = 5.dp)
                Text("$done / ${h.total}", fontSize = 11.sp, color = C.ink3)
            }
            Text("继续第 ${h.index + 1} 节", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.board, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseTags(c: Course, whereTag: String?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        whereTag?.let { Tag(it, TagStyle.OUTLINE) }
        c.ai?.let { ai ->
            Tag(ai.kind, TagStyle.KIND)
            if (ai.level != "中等") Tag(ai.level)
            if (ai.fit == "相关") Tag("可参考")
        }
        c.editions.firstOrNull()?.let { Tag(it) }
        if (c.newTextbook) Tag("新教材", TagStyle.FRESH)
        if (c.ai == null && c.review) Tag("复习")
    }
}

/** 看完几节：红笔写的数字。 */
@Composable
private fun WatchedCount(seen: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓ ", fontSize = 12.sp, color = C.red)
        Text("$seen", style = kai(19.sp).copy(color = C.red))
        Text("/$total", fontSize = 12.sp, color = C.red)
    }
}

private fun toggle(state: AppState, c: Course, context: CourseContext) {
    state.library.toggleFavorite(SavedCourse(c.bvid, c.title, c.cover, c.author, c.duration, c.episodes, context.grade, context.subject, context.volume))
}

private fun largeThumb(cover: String) = if (cover.contains("hdslb.com")) "$cover@480w_270h_1c.jpg" else cover

@Composable
fun WelcomeScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().background(C.paper).windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppLogo(52.dp)
                Text("随堂", style = kai(28.sp).copy(color = C.ink), letterSpacing = 3.sp)
            }
            Text("你在读几年级？", style = kai(30.sp), modifier = Modifier.semantics { heading() })
        }
        Taxonomy.stages.forEach { (name, grades) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.ink2, letterSpacing = 1.5.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    grades.forEach { g ->
                        TianZiGe(Taxonomy.gradeShort(g), false, 66.dp, if (g > 9) 20.sp else 28.sp, Taxonomy.gradeName(g), onBoard = false) { state.chooseGrade(g) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("之后随时可以在顶部换年级。在别的设备上用过？", fontSize = 14.sp, color = C.ink2)
            Text(
                "登录网盘，接着上次的进度学", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.board,
                modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { state.showSyncDialog = true }.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
fun MineScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var subject by remember { mutableStateOf("") }
    val favorites = state.library.favorites
    val subjects = favorites.mapNotNull { it.subject }.distinct().sorted()
    val shown = favorites.filter { subject.isEmpty() || it.subject == subject }

    Column(Modifier.fillMaxSize()) {
        BoardBar("我的", trailing = {
            if (state.sync.account == null) {
                Box(Modifier.padding(end = 10.dp)) { BoardAction("登录网盘") { state.tab = Tab.SETTINGS } }
            } else {
                Box(Modifier.padding(end = 10.dp)) {
                    BoardAction(if (state.sync.running) "同步中…" else "立即同步", enabled = !state.sync.running) { scope.launch { state.sync.syncNow() } }
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 32.dp)) {
            if (state.sync.account != null) item {
                Text(state.sync.statusText, fontSize = 12.sp, color = C.ink3, modifier = Modifier.padding(top = 4.dp))
            }
            val history = state.library.history.take(4)
            if (history.isNotEmpty()) {
                item { SectionTitle("继续学习", history.size) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        history.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { h -> MiniResume(state, h, Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("收藏", favorites.size)
                if (subjects.size > 1) Row(
                    Modifier.padding(bottom = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("全部", favorites.size, subject.isEmpty()) { subject = "" }
                    subjects.forEach { s -> Chip(s, favorites.count { it.subject == s }, subject == s) { subject = s } }
                }
            }
            if (shown.isEmpty()) item {
                Text("在课程旁点书签就能收藏，换设备登录同一个网盘后也能看到。", fontSize = 14.sp, color = C.ink2, modifier = Modifier.padding(vertical = 12.dp))
            } else item {
                Sheet { shown.forEachIndexed { i, f -> FavoriteRow(state, f, first = i == 0) } }
            }
        }
    }
}

/** 我的页里的继续学习：两列一张卡，封面在上面。 */
@Composable
private fun MiniResume(state: AppState, h: HistoryEntry, modifier: Modifier = Modifier) {
    val done = state.library.watchedCount(h.bvid)
    Column(
        modifier.card().clearAndSetSemantics { contentDescription = "继续学习：${h.title}，第 ${h.index + 1} 节 ${h.part}，已看完 $done 节，共 ${h.total} 节" }
            .clickable(role = Role.Button) { state.push(Screen.Player(h.bvid, CourseContext(h.grade, h.subject, h.volume))) }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Thumb(thumbOf(h.cover), null, null, radius = 10.dp)
        Text(h.part, style = kai(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(h.title, fontSize = 11.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(done.toFloat() / h.total.coerceAtLeast(1), Modifier.weight(1f), height = 4.dp)
            Text("$done/${h.total}", fontSize = 10.sp, color = C.ink3)
        }
    }
}

@Composable
private fun FavoriteRow(state: AppState, f: SavedCourse, first: Boolean) {
    val where = listOfNotNull(f.grade?.let(Taxonomy::gradeName), f.subject).joinToString(" ")
    Column {
        if (!first) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(C.rule))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${f.title}，${f.author}" }
                    .clickable(role = Role.Button) { state.push(Screen.Player(f.bvid, CourseContext(f.grade, f.subject, f.volume))) }
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(thumbOf(f.cover), f.duration, 104)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (where.isNotEmpty()) Tag(where, TagStyle.OUTLINE)
                        if (f.author.isNotEmpty()) Text(f.author, fontSize = 12.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            BookmarkButton(true, "取消收藏：" + f.title.take(24)) { state.library.toggleFavorite(f) }
        }
    }
}

/** 搜索框的提示语：配了大模型时可以整句话搜。 */
@Composable
fun AiHint(): String = if (AIConfig.load().unavailableReason == null) "搜索课程、知识点，或直接说想学什么" else "搜索课程、知识点或老师"
