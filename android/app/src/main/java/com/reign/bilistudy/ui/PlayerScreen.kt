package com.reign.bilistudy.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.reign.bilistudy.AppState
import com.reign.bilistudy.core.Chapters
import com.reign.bilistudy.core.CourseContext
import com.reign.bilistudy.core.CourseDetail
import com.reign.bilistudy.core.Fmt
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.data.HistoryEntry
import com.reign.bilistudy.data.SavedCourse
import com.reign.bilistudy.net.BiliClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun PlayerScreen(state: AppState, bvid: String, context: CourseContext) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<CourseDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf("") }
    val prefs = remember { ctx.getSharedPreferences("player", 0) }
    var speed by remember { mutableStateOf(prefs.getFloat("speed", 1f)) }
    // B 站视频服务器要求带 B 站的 Referer 和桌面浏览器的 User-Agent（和 Montage 的 BilibiliPlayerActivity 一致）
    val http = remember { DefaultHttpDataSource.Factory().setUserAgent(BiliClient.MEDIA_UA).setAllowCrossProtocolRedirects(true) }
    val player = remember { ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build() }

    fun saveProgress() {
        val d = detail ?: return
        val l = d.lessons.getOrNull(index) ?: return
        val pos = player.currentPosition / 1000.0
        val dur = player.duration / 1000.0
        if (dur <= 0 || pos <= 1) return
        state.library.setProgress(d.bvid, l.key, pos, dur)
        if (pos / dur > 0.9) state.library.markWatched(d.bvid, l.key) // 看过 90% 算看完
    }

    fun select(i: Int) {
        val d = detail ?: return
        if (i !in d.lessons.indices) return
        saveProgress()
        index = i
        val l = d.lessons[i]
        state.library.record(HistoryEntry(d.bvid, d.displayTitle, d.cover, d.author, l.key, i, l.part, d.lessons.size, context.grade, context.subject, context.volume))
        scope.launch {
            try {
                val src = BiliClient.playSource(l.bvid, l.cid)
                if (index != i) return@launch
                quality = src.quality
                error = null
                http.setDefaultRequestProperties(src.headers)
                player.setMediaItem(MediaItem.fromUri(src.url))
                player.prepare()
                state.library.progress(d.bvid, l.key)?.let { p -> if (p.position > 5 && p.position < p.duration - 10) player.seekTo((p.position * 1000).toLong()) }
                player.setPlaybackSpeed(speed)
                player.play()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    LaunchedEffect(bvid) {
        try {
            val d = BiliClient.detail(bvid)
            detail = d
            val last = state.library.historyEntry(bvid)?.lesson
            select(d.lessons.indexOfFirst { it.key == last }.coerceAtLeast(0))
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(Unit) { while (true) { delay(5000); saveProgress() } }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s != Player.STATE_ENDED) return
                val d = detail ?: return
                d.lessons.getOrNull(index)?.let { state.library.markWatched(d.bvid, it.key); state.library.setProgress(d.bvid, it.key, 0.0, 0.0) }
                if (index + 1 < d.lessons.size) select(index + 1)
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress()
            player.removeListener(listener)
            player.release()
            state.sync.schedule(500) // 退出播放页时同步进度
        }
    }

    Column(Modifier.fillMaxSize().background(C.paper)) {
        LightStatusBarIcons()
        Box(Modifier.fillMaxWidth().background(C.screen).windowInsetsPadding(WindowInsets.statusBars)) {
            AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Box(Modifier.padding(8.dp).size(44.dp).background(Color.Black.copy(alpha = 0.42f), CircleShape).semantics { contentDescription = "返回" }
                .clickable(role = Role.Button) { state.pop() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
        }
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = C.ink2) else CircularProgressIndicator(color = C.board, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        val lesson = d.lessons.getOrNull(index)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("第 ${index + 1} 节，共 ${d.lessons.size} 节", fontSize = 12.sp, color = C.ink3)
            Text(lesson?.part.orEmpty(), style = kai(21.sp), maxLines = 2, lineHeight = 29.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
            Text(d.displayTitle, fontSize = 13.sp, color = C.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(d.author, "${d.lessons.size} 节", Fmt.hours(d.lessons.sumOf { it.duration }), "${Fmt.count(d.play)} 次播放", quality)
                    .filter { it.isNotEmpty() }.joinToString("   "),
                fontSize = 12.sp, color = C.ink3, modifier = Modifier.padding(top = 2.dp),
            )
            if (error != null) Text(error!!, fontSize = 13.sp, color = C.red, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlineButton("上一节", enabled = index > 0) { select(index - 1) }
                OutlineButton("下一节", primary = true, enabled = index + 1 < d.lessons.size) { select(index + 1) }
                OutlineButton(if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×", description = "播放速度 $speed 倍") {
                    val all = listOf(1f, 1.25f, 1.5f, 2f)
                    speed = all[(all.indexOf(speed) + 1) % all.size]
                    prefs.edit().putFloat("speed", speed).apply()
                    player.setPlaybackSpeed(speed)
                }
                val saved = state.library.isFavorite(d.bvid)
                BookmarkButton(saved, if (saved) "取消收藏" else "收藏这门课") {
                    state.library.toggleFavorite(SavedCourse(d.bvid, d.displayTitle, d.cover, d.author, d.lessons.sumOf { it.duration }, d.lessons.size, context.grade, context.subject, context.volume))
                }
            }
        }
        val list = rememberLazyListState()
        val groups = remember(d) { Chapters.group(d.lessons) }
        LaunchedEffect(index) { list.animateScrollToItem((index - 2).coerceAtLeast(0)) }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 14.dp).card()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text("目录", style = kai(19.sp), modifier = Modifier.weight(1f).semantics { heading() })
                Text("已看完 ${state.library.watchedCount(d.bvid)} / ${d.lessons.size}", fontSize = 12.sp, color = C.red)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.rule))
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                groups.forEach { (label, idx) ->
                    if (label != null) item { Text(label, style = kai(15.sp).copy(color = C.ink2), modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp).semantics { heading() }) }
                    items(idx, key = { it }) { i ->
                        val l = d.lessons[i]
                        val on = i == index
                        val done = state.library.isWatched(d.bvid, l.key)
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).background(if (on) C.tint else Color.Transparent)
                            .clearAndSetSemantics { contentDescription = "第 ${i + 1} 节，${l.part}" + if (done) "，已看完" else ""; selected = on }
                            .clickable(role = Role.Button) { select(i) }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(46.dp).background(if (on) C.board else Color.Transparent))
                            Text("${i + 1}", fontSize = 12.sp, color = C.ink3, modifier = Modifier.width(34.dp).padding(end = 8.dp), textAlign = TextAlign.End)
                            Text(l.part, fontSize = 14.sp, color = if (on) C.boardDeep else C.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (l.duration > 0) Text(Fmt.duration(l.duration), fontSize = 12.sp, color = C.ink3, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(if (done) "✓" else "", style = kai(15.sp).copy(color = C.red), modifier = Modifier.width(22.dp))
                        }
                    }
                }
            }
        }
    }
}
