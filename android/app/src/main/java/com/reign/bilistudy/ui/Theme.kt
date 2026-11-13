package com.reign.bilistudy.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 随堂的颜色：黑板（标题栏、年级）和纸面（内容）。数值和 web/app.css、iOS Theme.swift 一致，规范见 designs/brand/README.md。 */
object C {
    // 黑板
    val board = Color(0xFF1F5C47)
    val boardDeep = Color(0xFF174636)
    val chalk = Color(0xFFF4F0E3)
    val chalk2 = Color(0xBDF4F0E3)
    val chalkLine = Color(0x80F4F0E3)
    val yellow = Color(0xFFF4C24D)
    // 纸面
    val paper = Color(0xFFF3F6F3)
    val card = Color.White
    val grid = Color(0xFFA9CBB9)
    val ink = Color(0xFF1C2B26)
    val ink2 = Color(0xFF52605A)
    val ink3 = Color(0xFF64716B)
    val rule = Color(0xFFE2E8E4)
    val tint = Color(0xFFE5F0EA)
    val red = Color(0xFFC23B2B)
    val tagBg = Color(0xFFEDF2EF)
    val freshBg = Color(0xFFFCEFC7)
    val freshFg = Color(0xFF6E5200)
    val thumbBg = Color(0xFFDFE6E2)
    val screen = Color(0xFF0E1A16)

    val subjects = mapOf(
        "语文" to Color(0xFFB5443B), "数学" to Color(0xFF2E64A8), "英语" to Color(0xFF6B4E9E), "物理" to Color(0xFF1E7F80),
        "化学" to Color(0xFF9A5E17), "生物" to Color(0xFF427A39), "历史" to Color(0xFF875A3D), "地理" to Color(0xFF33789A),
        "道德与法治" to Color(0xFFA23C66), "政治" to Color(0xFFA23C66), "科学" to Color(0xFF54792A),
    )
    val spineHeights = listOf(124, 118, 130, 114, 122, 118, 128, 116, 122)
}

/** Android 不带楷体，标题用系统衬线字体（大多数机型是思源宋体）。 */
val Kai = FontFamily.Serif

fun kai(size: TextUnit) = TextStyle(fontFamily = Kai, fontSize = size, color = C.ink)

/** 白色卡片：大圆角加一层很淡的阴影。 */
fun Modifier.card(radius: Dp = 18.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.shadow(3.dp, shape, ambientColor = C.ink.copy(alpha = 0.25f), spotColor = C.ink.copy(alpha = 0.25f)).background(C.card, shape).clip(shape)
}

/** 页面顶部是黑板或视频时，状态栏图标改成浅色；离开时恢复深色。 */
@Composable
fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = true }
    }
}

/** 随堂的标：田字格里一个播放键。黑板上直接用；纸面上放进黑板绿的圆角方块（见 AppLogo）。 */
@Composable
fun BrandMark(size: Dp) {
    Canvas(Modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.width / 64f
        drawRoundRect(C.chalk, Offset(4 * s, 4 * s), Size(56 * s, 56 * s), CornerRadius(1.5f * s), style = Stroke(3 * s))
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.5f * s, 2.3f * s))
        drawLine(C.chalk.copy(alpha = 0.55f), Offset(32 * s, 8.5f * s), Offset(32 * s, 55.5f * s), 1.4f * s, StrokeCap.Round, dash)
        drawLine(C.chalk.copy(alpha = 0.55f), Offset(8.5f * s, 32 * s), Offset(55.5f * s, 32 * s), 1.4f * s, StrokeCap.Round, dash)
        val play = Path().apply { moveTo(26.2f * s, 21.2f * s); lineTo(26.2f * s, 42.8f * s); lineTo(44.9f * s, 32 * s); close() }
        drawPath(play, C.yellow, style = Fill)
        drawPath(play, C.yellow, style = Stroke(5 * s, join = StrokeJoin.Round))
    }
}

/** 和 App 图标一样的完整标志：黑板绿圆角方块里放 BrandMark。 */
@Composable
fun AppLogo(size: Dp) {
    Box(
        Modifier.size(size).shadow(4.dp, RoundedCornerShape(size * 0.235f))
            .background(Brush.verticalGradient(listOf(Color(0xFF23674F), Color(0xFF174836))), RoundedCornerShape(size * 0.235f)),
        contentAlignment = Alignment.Center,
    ) { BrandMark(size * 0.646f) }
}

/** 田字格：实线外框，中间一横一竖两条虚线。黑板上用粉笔线，当前年级填粉笔黄；纸面上用浅绿线。 */
@Composable
fun TianZiGe(text: String, on: Boolean, size: Dp, fontSize: TextUnit, description: String, onBoard: Boolean = true, onClick: () -> Unit) {
    val line = if (!onBoard) C.grid else if (on) C.boardDeep.copy(alpha = 0.22f) else C.chalkLine
    val fill = if (!onBoard) Color.White else if (on) C.yellow else Color.Transparent
    val border = if (!onBoard) C.grid else if (on) C.yellow else C.chalkLine
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.size(size).background(fill, shape).border(1.5.dp, border, shape)
            .semantics { contentDescription = description; selected = on }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            drawLine(line, Offset(0f, this.size.height / 2), Offset(this.size.width, this.size.height / 2), 2f, pathEffect = dash)
            drawLine(line, Offset(this.size.width / 2, 0f), Offset(this.size.width / 2, this.size.height), 2f, pathEffect = dash)
        }
        Text(text, style = kai(fontSize).copy(color = if (!onBoard) C.ink else if (on) C.boardDeep else C.chalk))
    }
}

/** 当前学科书脊顶上夹的书签条。 */
val RibbonShape: Shape = GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
    lineTo(size.width / 2, size.height * 0.8f); lineTo(0f, size.height); close()
}

/** 进度条：圆头，黑板绿填充；放在封面上时用粉笔黄。 */
@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier, fill: Color = C.board, track: Color = C.tint, height: Dp = 6.dp) {
    BoxWithConstraints(modifier.height(height).clip(CircleShape).background(track).clearAndSetSemantics { }) {
        if (value > 0f) Box(Modifier.width(maxOf(height, maxWidth * value.coerceAtMost(1f))).height(height).clip(CircleShape).background(fill))
    }
}

@Composable
fun Chip(title: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).background(if (on) C.board else Color.White, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, if (on) C.board else C.rule), RoundedCornerShape(24.dp))
            .semantics { selected = on }.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 14.sp, color = if (on) Color.White else C.ink2)
        if (count != null) Text("$count", fontSize = 13.sp, color = if (on) C.chalk2 else C.ink3)
    }
}

enum class TagStyle { PLAIN, KIND, FRESH, OUTLINE }

@Composable
fun Tag(text: String, style: TagStyle = TagStyle.PLAIN) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text, fontSize = 12.sp, maxLines = 1, fontWeight = if (style == TagStyle.KIND) FontWeight.Medium else FontWeight.Normal,
        color = when (style) { TagStyle.FRESH -> C.freshFg; TagStyle.KIND -> C.board; else -> C.ink2 },
        modifier = Modifier
            .background(when (style) { TagStyle.FRESH -> C.freshBg; TagStyle.KIND -> C.tint; TagStyle.OUTLINE -> Color.Transparent; else -> C.tagBg }, shape)
            .then(if (style == TagStyle.OUTLINE) Modifier.border(1.dp, C.rule, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun BookmarkButton(saved: Boolean, label: String, onCover: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            if (onCover) Modifier.size(38.dp).shadow(2.dp, CircleShape).background(Color.White.copy(alpha = 0.94f), CircleShape) else Modifier.size(38.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null, tint = if (saved) C.red else C.ink3)
        }
    }
}

@Composable
fun OutlineButton(text: String, primary: Boolean = false, enabled: Boolean = true, description: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.heightIn(min = 48.dp).background(if (primary) C.board else Color.White, shape)
            .border(1.5.dp, (if (primary) C.board else C.rule).copy(alpha = if (enabled) 1f else 0.35f), shape)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = (if (primary) Color.White else C.ink).copy(alpha = if (enabled) 1f else 0.35f))
    }
}
