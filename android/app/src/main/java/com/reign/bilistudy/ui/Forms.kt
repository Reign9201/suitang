package com.reign.bilistudy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.reign.bilistudy.AppState
import com.reign.bilistudy.core.Classify
import com.reign.bilistudy.core.Course
import com.reign.bilistudy.core.CourseContext
import com.reign.bilistudy.core.SearchIntent
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.net.AIClient
import com.reign.bilistudy.net.AIConfig
import com.reign.bilistudy.net.BiliClient
import com.reign.bilistudy.net.DavAccount
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(state: AppState, initial: String) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var intent by remember { mutableStateOf<SearchIntent?>(null) }
    var keyword by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Course>() }
    var page by remember { mutableIntStateOf(1) }
    var pages by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    fun search(reset: Boolean) = scope.launch {
        val q = text.trim().ifEmpty { return@launch }
        loading = true; error = null
        try {
            if (reset) {
                results.clear(); page = 1; intent = null; keyword = q
                // 有大模型时先把一句话解析成年级、学科、知识点，再用改写后的搜索词去搜
                val config = AIConfig.load()
                if (config.unavailableReason == null) runCatching { AIClient(config).parseIntent(q, state.grade, config.intentModel) }.getOrNull()?.let {
                    intent = it
                    if (it.query.isNotEmpty()) keyword = it.query
                }
            } else page += 1
            val data = BiliClient.search(keyword, page, 20)
            pages = Classify.int(data.get("numPages"))
            var items = (data.getAsJsonArray("result") ?: com.google.gson.JsonArray()).mapNotNull { (it as? JsonObject)?.let(Classify::baseItem)?.first }.map { c ->
                val g = Classify.describe(c).grades
                Classify.finish(c, if (g.size == 1) g.first() else 0, null, null)
            }
            intent?.takeIf { it.grade > 0 }?.let { i ->
                // 和解析出的年级、学科一致的排前面，其余保持 B 站的顺序
                val subj = Classify.canonical(i.subject)
                items = items.sortedBy { c ->
                    val sameSubject = subj.isEmpty() || c.subjects.map(Classify::canonical).contains(subj)
                    if (i.grade in c.grades && sameSubject) 0 else if (sameSubject) 1 else 2
                }
            }
            results.addAll(items.filter { n -> results.none { it.bvid == n.bvid } })
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    LaunchedEffect(Unit) { if (initial.isNotEmpty()) search(true) else focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(C.paper).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).semantics { contentDescription = "返回" }.clickable(role = Role.Button) { state.pop() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = C.ink2)
            }
            Row(Modifier.weight(1f).padding(start = 4.dp).heightIn(min = 44.dp).border(1.5.dp, C.board, CircleShape).background(Color.White, CircleShape).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = C.ink3, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (text.isEmpty()) Text(AiHint(), fontSize = 14.sp, color = C.ink3)
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "搜索" },
                        textStyle = TextStyle(fontSize = 15.sp, color = C.ink), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(true) }))
                }
            }
        }
        intent?.takeIf { it.grade > 0 || it.topic.isNotEmpty() }?.let { i ->
            Row(Modifier.padding(top = 12.dp).horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(if (i.grade > 0) Taxonomy.gradeName(i.grade) else "", i.subject, i.volume, i.topic).filter { it.isNotEmpty() }.forEach {
                    Text(it, fontSize = 13.sp, color = C.board, modifier = Modifier.background(C.tint, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
                }
                if (i.grade > 0 && i.subject.isNotEmpty()) OutlineButton(
                    "打开${Taxonomy.gradeName(i.grade)}${i.subject}${i.volume}", primary = true,
                    onClick = { state.open(i.grade, i.subject, i.volume) },
                )
            }
        }
        if (keyword.isNotEmpty()) Text("只搜索 B 站校园学习分区" + if (keyword != text.trim()) "，搜索词「$keyword」" else "", fontSize = 12.sp, color = C.ink3, modifier = Modifier.padding(top = 6.dp))
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (results.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().card().padding(vertical = 4.dp)) {
                    results.forEachIndexed { i, c ->
                        val vols = c.volumes.toSet()
                        val vol = if (vols.containsAll(listOf("上册", "下册"))) "上下册" else c.volumes.firstOrNull().orEmpty()
                        val where = listOf(if (c.grades.size == 1) Taxonomy.gradeName(c.grades[0]) else "", c.subjects.singleOrNull().orEmpty(), vol).filter { it.isNotEmpty() }.joinToString(" ")
                        CourseRow(state, c, CourseContext(c.grades.singleOrNull() ?: intent?.grade?.takeIf { it > 0 }, c.subjects.singleOrNull(), c.volumes.firstOrNull()),
                            first = i == 0, whereTag = where.ifEmpty { null })
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        loading -> CircularProgressIndicator(color = C.board, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        error != null -> Text(error!!, color = C.ink2)
                        results.isEmpty() && keyword.isNotEmpty() -> Text("没有找到「$text」相关的学习视频，换个说法试试，比如加上年级或课本版本。", color = C.ink2)
                        page < pages -> OutlineButton("加载更多") { search(false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier = this.horizontalScroll(rememberScrollState())

@Composable
fun SettingsScreen(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        BoardBar("设置")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card { SyncForm(state) }
            Card { AIForm(state) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().card().padding(18.dp)) { content() }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.ink)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 46.dp).background(Color(0xFFF8FAF8), RoundedCornerShape(12.dp))
            .border(1.dp, C.rule, RoundedCornerShape(12.dp)).padding(horizontal = 13.dp, vertical = 13.dp)
            .semantics { contentDescription = label }, textStyle = TextStyle(fontSize = 15.sp, color = C.ink), singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri))
        if (hint != null) Text(hint, fontSize = 12.sp, lineHeight = 18.sp, color = C.ink3)
    }
}

/** 多端同步：WebDAV 地址、账号、应用密码。保存时先实际访问一次网盘。 */
@Composable
fun SyncForm(state: AppState, onDone: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val a = state.sync.account
    var url by remember { mutableStateOf(a?.url ?: DavAccount.JIANGUOYUN) }
    var user by remember { mutableStateOf(a?.user.orEmpty()) }
    var password by remember { mutableStateOf(a?.password.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("多端同步", style = kai(21.sp), modifier = Modifier.semantics { heading() })
        Text(state.sync.statusText, fontSize = 12.sp, color = if (state.sync.error == null) C.ink2 else C.red)
        Field("WebDAV 地址", url, { url = it })
        Field("账号", user, { user = it })
        Field("应用密码", password, { password = it }, secret = true, hint = "坚果云在网页版「账户信息 → 安全选项 → 第三方应用管理」生成，不是登录密码")
        Text("同步收藏、学习进度、最近在学，以及选过的年级和课本版本", fontSize = 12.sp, color = C.ink2)
        error?.let { Text(it, fontSize = 13.sp, color = C.red) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(if (saving) "正在连接…" else "保存并同步", primary = true, enabled = !saving && user.isNotEmpty() && password.isNotEmpty()) {
                scope.launch {
                    saving = true; error = null
                    try { state.sync.login(DavAccount(url.trim(), user.trim(), password)); onDone?.invoke() } catch (e: Exception) { error = e.message } finally { saving = false }
                }
            }
            if (a != null) Text("停止同步", fontSize = 14.sp, color = C.ink2, modifier = Modifier.heightIn(min = 48.dp)
                .clickable(role = Role.Button) { state.sync.logout(); password = "" }.padding(horizontal = 8.dp, vertical = 14.dp))
        }
    }
}

/** 大模型整理：接口地址、密钥、两个任务的模型；高级选项里是鉴权方式、思考强度、取结果方式、API 版本。 */
@Composable
fun AIForm(state: AppState) {
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf(AIConfig.load()) }
    var secret by remember { mutableStateOf(AIConfig.secret.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    fun update(n: AIConfig) { c = n; AIConfig.save(n); state.catalogs.resetAI() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("大模型整理", style = kai(21.sp), modifier = Modifier.weight(1f).semantics { heading() })
            Switch(c.enabled, { update(c.copy(enabled = it)) }, Modifier.semantics { contentDescription = "用大模型整理课程" },
                colors = SwitchDefaults.colors(checkedTrackColor = C.board, uncheckedTrackColor = C.rule, uncheckedThumbColor = Color.White))
        }
        Field("接口地址", c.baseUrl, { update(c.copy(baseUrl = it)) })
        Field("密钥", secret, { secret = it; AIConfig.secret = it; state.catalogs.resetAI() }, secret = true, hint = "只存在本机加密存储，不会同步到网盘")
        Field("整理用模型", c.organizeModel, { update(c.copy(organizeModel = it)) })
        Field("搜索解析用模型", c.intentModel, { update(c.copy(intentModel = it)) })
        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = C.ink)
            Text(" 高级选项", fontSize = 14.sp, color = C.ink)
        }
        if (advanced) {
            Options("鉴权方式", c.auth, listOf("bearer" to "Bearer 令牌", "apiKey" to "API Key")) { update(c.copy(auth = it)) }
            Options("思考强度", c.effort, listOf("low" to "low", "medium" to "medium", "high" to "high", "" to "不传")) { update(c.copy(effort = it)) }
            Options("取结果方式", c.jsonMode, listOf("auto" to "自动", "schema" to "结构化输出", "tool" to "工具调用", "prompt" to "提示词")) { update(c.copy(jsonMode = it)) }
            Field("API 版本", c.apiVersion, { update(c.copy(apiVersion = it)) }, hint = "留空时官方地址用 2026-10-21，其他地址用 2023-06-01")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(if (testing) "测试中…" else "测试连接", enabled = !testing) {
                scope.launch {
                    testing = true
                    val started = System.currentTimeMillis()
                    result = try {
                        val i = AIClient(c).parseIntent("我初二，浮力听不懂", 7, c.intentModel)
                        "连接正常：%s，搜索解析用时 %.1f 秒（%s %s）".format(c.intentModel, (System.currentTimeMillis() - started) / 1000.0, if (i.grade > 0) Taxonomy.gradeName(i.grade) else "", i.subject)
                    } catch (e: Exception) { e.message ?: "连接失败" }
                    testing = false
                }
            }
            Text(if (result.isEmpty() && !c.enabled) "关闭后只按标题规则归类" else result, fontSize = 12.sp, color = C.ink2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Options(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.ink)
        Row(Modifier.horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> Chip(l, null, v == value) { onPick(v) } }
        }
    }
}
