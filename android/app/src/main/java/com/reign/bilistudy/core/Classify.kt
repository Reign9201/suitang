package com.reign.bilistudy.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * 从标题里认出年级、学科、版本、册次，并判断一条视频属不属于某个分类。
 * 逐条移植自 Python 原型 classify.py（iOS 版 Classify.swift 同源），改规则时三处一起改，并跑 ClassifyTest。
 */
object Classify {
    const val STUDY_TID = 208
    /** 义务教育阶段从 2024 年秋季开始逐年换新教材，这之后发布的视频优先。 */
    const val NEW_TEXTBOOK_SINCE = 1_719_763_200 // 2024-07-01 00:00 +08:00

    private val digit: Map<Char, Int> = Taxonomy.CN.mapIndexed { i, c -> c to i + 1 }.toMap() + (1..9).associate { ('0' + it) to it }
    private val cache = HashMap<String, Regex>()
    private fun re(p: String, ignoreCase: Boolean = false): Regex = synchronized(cache) {
        cache.getOrPut(p + ignoreCase) { if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p) }
    }
    fun has(p: String, text: String, ignoreCase: Boolean = false) = re(p, ignoreCase).containsMatchIn(text)
    private fun num(s: String) = s.firstOrNull()?.let { digit[it] } ?: 0

    private const val RANGE = """\s*[到至~\-—－]\s*"""
    private const val SHORT_TAIL = """(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|道德|科学|$|[】\]｜|，,）)])"""

    /** 返回（明确提到的年级，只能推断的年级）。明确年级存在时只用它，避免「七年级……直击中考」被归进九年级。 */
    fun grades(text: String): Pair<Set<Int>, Set<Int>> {
        val strong = HashSet<Int>()
        re("([一二三四五六七八九1-9])$RANGE([一二三四五六七八九1-9])\\s*年级").findAll(text).forEach {
            val a = num(it.groupValues[1]); val b = num(it.groupValues[2])
            if (a > 0 && b > 0) strong.addAll(minOf(a, b)..maxOf(a, b))
        }
        re("([一二三四五六七八九1-9])\\s*年级").findAll(text).forEach { strong.add(num(it.groupValues[1])) }
        re("初([一二三])${RANGE}初?([一二三])").findAll(text).forEach {
            val a = num(it.groupValues[1]); val b = num(it.groupValues[2])
            strong.addAll((6 + minOf(a, b))..(6 + maxOf(a, b)))
        }
        re("初([一二三])(?![级中])").findAll(text).forEach { strong.add(6 + num(it.groupValues[1])) }
        re("高([一二三])${RANGE}高?([一二三])").findAll(text).forEach {
            val a = num(it.groupValues[1]); val b = num(it.groupValues[2])
            strong.addAll((9 + minOf(a, b))..(9 + maxOf(a, b)))
        }
        re("(?<![提最升])高([一二三])(?![级点倍])").findAll(text).forEach { strong.add(9 + num(it.groupValues[1])) }
        re("(?<![第每其之初高一二三四五六七八九十])([一二三四五六七八九])([上下])$SHORT_TAIL").findAll(text).forEach { strong.add(num(it.groupValues[1])) }
        strong.remove(0)
        val weak = HashSet<Int>()
        if ("小升初" in text) weak.add(6)
        if ("中考" in text) weak.add(9)
        if ("高考" in text) weak.add(12)
        if ("选择性必修" in text) weak.add(11) else if ("必修" in text) weak.add(10)
        return strong to weak
    }

    private val subjectPatterns = listOf(
        "语文" to "语文|文言文|古诗文", "数学" to "数学|奥数", "英语" to "英语|English|新概念", "物理" to "物理",
        "化学" to "化学", "生物" to "生物", "历史" to "历史", "地理" to "地理",
        "道德与法治" to "道德与法治|道法|思想品德|思想政治|政治|思政", "科学" to "科学(?!普)",
    )

    /** 初中叫道德与法治，高中叫政治，比对时视为同一科。 */
    fun canonical(subject: String) = if (subject == "政治") "道德与法治" else subject

    fun subjects(text: String): Set<String> = subjectPatterns.filter { has(it.second, text, ignoreCase = true) }.map { it.first }.toSet()

    private val editionPatterns = listOf(
        "人教版" to "人教[AB]?版|新人教|人教", "统编版" to "统编|部编", "北师大版" to "北师", "苏教版" to "苏教",
        "沪教版" to "沪教", "外研版" to "外研", "译林版" to "译林", "青岛版" to "青岛版", "浙教版" to "浙教",
        "湘教版" to "湘教", "冀教版" to "冀教", "鲁教版" to "鲁教", "沪科版" to "沪科", "华师大版" to "华师",
        "北京版" to "北京版", "教科版" to "教科版", "粤教版" to "粤教", "科粤版" to "科粤", "仁爱版" to "仁爱",
        "牛津版" to "牛津",
    )
    /** 这三科全国统一用统编版，人教社出版，很多 UP 主写成「人教版」。 */
    private val unified = setOf("语文", "历史", "道德与法治")

    fun editions(text: String, subject: String?): List<String> {
        var found = editionPatterns.filter { has(it.second, text) }.map { it.first }.distinct()
        if (subject != null && canonical(subject) in unified) found = found.map { if (it == "人教版") "统编版" else it }.distinct()
        return found
    }

    fun volumes(text: String): Set<String> {
        val found = HashSet<String>()
        re("""选择性必修\s*(?:第)?([一二三123上中下])""").findAll(text).forEach {
            val g = it.groupValues[1]
            found.add("选择性必修" + (mapOf("上" to "一", "中" to "二", "下" to "三")[g] ?: "${Taxonomy.CN[maxOf(1, num(g)) - 1]}"))
        }
        re("""(?<!选择性)必修\s*(?:第)?([一二三123上下])""").findAll(text).forEach {
            val g = it.groupValues[1]
            found.add("必修" + (mapOf("上" to "一", "下" to "二")[g] ?: "${Taxonomy.CN[maxOf(1, num(g)) - 1]}"))
        }
        if ("全一册" in text) found.add("全一册")
        if (has("上下(?:全)?册|上[/、和及]下册", text)) found.addAll(listOf("上册", "下册"))
        val tail = """(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|科学|$|[】\]｜，,）)])"""
        if (has("(?<!必修)(?<!选择性必修)上册|上学期|[一二三四五六七八九]上$tail", text)) found.add("上册")
        if (has("(?<!必修)(?<!选择性必修)下册|下学期|[一二三四五六七八九]下$tail", text)) found.add("下册")
        if ("一轮" in text) found.add("一轮复习")
        if ("二轮" in text) found.add("二轮复习")
        return found
    }

    fun episodes(text: String): Int {
        for (p in listOf("""(?:全|共)\s*(\d{1,4})\s*[集讲节课期P]""", """(\d{1,4})\s*集全""", """(?<![第\d.])(\d{1,4})\s*(?:集|讲|节课?|[Pp](?![a-zA-Z]))""")) {
            re(p).find(text)?.let { return it.groupValues[1].toInt() }
        }
        return 0
    }

    fun isReview(text: String) = has("复习|期中|期末|冲刺|真题|押题|备考|考前|速通|速成|刷完|总结|压轴", text)

    /** 搜索结果的时长是「分:秒」，分可能超过 60，例如 1239:55。 */
    fun duration(text: String) = text.split(":").mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, n -> acc * 60 + n }

    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]")

    fun clean(raw: String?): String {
        var t = (raw ?: "").replace(Regex("<[^>]+>"), "")
        listOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ").forEach { (a, b) -> t = t.replace(a, b) }
        return t.replace(emoji, "").replace(Regex("\\s+"), " ").trim()
    }

    fun https(url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    private fun JsonObject.str(k: String): String = get(k)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: ""
    fun int(e: JsonElement?): Int = e?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString.toDouble().toInt() }.getOrNull() } ?: 0
    private fun truthy(e: JsonElement?): Boolean {
        if (e == null || e.isJsonNull) return false
        return runCatching { if (e.asJsonPrimitive.isBoolean) e.asBoolean else e.asString.let { it.isNotEmpty() && it != "0" && it != "false" } }.getOrDefault(false)
    }

    /** 搜索结果里与分类无关的字段。返回 null 和原因表示这条没法在应用里播放。 */
    fun baseItem(row: JsonObject): Pair<Course?, String?> {
        val bvid = row.str("bvid").trim()
        if (!has("^BV[0-9A-Za-z]{10}$", bvid)) return null to "无法播放"
        if (truthy(row["is_pay"]) || truthy(row["is_charge_video"]) || truthy(row["badgepay"])) return null to "付费或充电专属"
        val title = clean(row.str("title"))
        return Course(
            bvid = bvid, title = title, author = clean(row.str("author")), cover = https(row.str("pic").trim()),
            duration = duration(row.str("duration")), play = int(row["play"]), favorites = int(row["favorites"]),
            pubdate = int(row["pubdate"]), typeid = int(row["typeid"]), episodes = episodes(title),
            desc = clean(row.str("description")).take(160), tagtext = clean(row.str("tag")),
        ) to null
    }

    data class Described(val grades: Set<Int>, val subjects: Set<String>, val editions: List<String>, val volumes: Set<String>)

    /** 给任意一条视频认出年级、学科、版本、册次，用于搜索结果的标签。 */
    fun describe(c: Course, subject: String? = null): Described {
        val (strong, weak) = grades(c.title)
        val g = strong.ifEmpty { weak.ifEmpty { grades(c.tagtext).first } }
        val subs = subjects(c.title).ifEmpty { subjects(c.tagtext) }
        val subj = subject ?: subs.singleOrNull()
        val eds = editions(c.title, subj).ifEmpty { editions(c.tagtext, subj).take(1) }
        return Described(g, subs, eds, volumes(c.title))
    }

    /** 判断一条视频是否属于（年级 n、学科、册次）。返回 null 表示收录，否则是拒绝原因。规则按「宁缺毋滥」。 */
    fun reject(c: Course, n: Int, subject: String, volume: String): String? {
        if (c.typeid != STUDY_TID) return "不在学习分区"
        if (c.author.isEmpty() || c.author == "账号已注销") return "账号已注销"
        if (c.duration in 1 until 180) return "短于 3 分钟"
        val (strong, weak) = grades(c.title)
        val g = strong.ifEmpty { weak }
        if (g.isNotEmpty() && n !in g) return "年级不符"
        val subs = subjects(c.title).map(::canonical).toSet()
        if (subs.isNotEmpty() && canonical(subject) !in subs) return "学科不符"
        val vols = volumes(c.title)
        if (vols.isNotEmpty() && volume !in vols) return "册次不符"
        if (g.isEmpty()) {
            val tagGrades = grades(c.tagtext).first
            if (tagGrades.isNotEmpty() && n !in tagGrades) return "年级不符"
        }
        return null
    }

    /** 补齐派生字段并算排序分数（volume 为 null 时不算分，用于搜索结果）。 */
    fun finish(c: Course, n: Int, subject: String?, volume: String?): Course {
        val d = describe(c, subject)
        val base = c.copy(
            editions = d.editions, volumes = d.volumes.sorted(), grades = d.grades.sorted(), subjects = d.subjects.sorted(),
            series = c.episodes >= 4 || c.duration >= 3600, review = isReview(c.title),
            newTextbook = n in 1..9 && (c.pubdate >= NEW_TEXTBOOK_SINCE || has("新教材|新版|202[4-9]", c.title)),
        )
        return if (volume != null) base.copy(score = score(base, n, volume)) else base
    }

    fun score(c: Course, n: Int, volume: String): Double {
        var s = log10(c.play + 1.0) + 1.5 * log10(c.favorites + 1.0)
        if (n in grades(c.title).first) s += 0.6
        if (volume in volumes(c.title)) s += 1.0
        if (c.play > 0 && c.favorites.toDouble() / c.play > 0.02) s += 0.5 // 收藏率高的通常是真正拿来学的课
        if (n <= 9 && c.newTextbook) s += 0.8
        if (c.series) s += 0.5
        return (s * 1000).roundToLong() / 1000.0
    }

    /** 把大模型的判断合进规则结果（和 server.py 的 apply_ai 一致）。 */
    fun applyAI(catalog: Catalog, judged: Map<String, AIJudgement>): Catalog {
        val kept = ArrayList<Course>()
        val dropped = ArrayList<Course>()
        for (item in catalog.items) {
            val j = judged[item.bvid]
            if (j == null) { kept.add(item); continue }
            if (j.fit == "不对口") { dropped.add(item); continue }
            val eds = if (j.edition.isNotEmpty() && item.editions.isEmpty()) editions(j.edition, catalog.subject).take(1) else item.editions
            val score = ((item.score + (j.quality - 3) * 1.5 - if (j.fit == "相关") 2 else 0) * 1000).roundToLong() / 1000.0
            kept.add(item.copy(ai = j, editions = eds, score = score))
        }
        kept.sortByDescending { it.score }
        val rejected = if (dropped.isEmpty()) catalog.rejected else
            listOf(Rejection("大模型判断不对口", dropped.size, dropped.take(3).map { it.title.take(60) })) + catalog.rejected
        // 推荐以大模型的质量判断为主：先比质量分，再比是不是新教材，最后才看播放收藏算出的分数
        val candidates = kept.filter { it.ai?.fit == "对口" && (it.ai.quality) >= 4 }
            .sortedWith(compareByDescending<Course> { it.ai?.quality ?: 0 }.thenByDescending { it.newTextbook }.thenByDescending { it.score })
        val picks = ArrayList<Course>()
        for (c in candidates) if (picks.size < 3 && picks.none { sameCourse(c, it) }) picks.add(c)
        return catalog.copy(items = kept, rejected = rejected, picks = picks.map { it.bvid })
    }

    /** 同一套课常被多个账号搬运：集数相同或标题高度相似的只推荐一条。 */
    fun sameCourse(a: Course, b: Course): Boolean {
        if (a.episodes > 0 && a.episodes == b.episodes) return true
        fun grams(t: String) = if (t.length < 2) emptySet() else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
        val ga = grams(a.title); val gb = grams(b.title)
        if (ga.isEmpty() || gb.isEmpty()) return false
        return ga.intersect(gb).size.toDouble() / minOf(ga.size, gb.size) > 0.6
    }
}

/** 分集标题里的「1.2.1」「第一单元」「Unit 3」→ 按章分组；能认出章节的不到一半时不分组。 */
object Chapters {
    private const val CN_NUM = "零一二三四五六七八九十"

    fun label(part: String): String? {
        Regex("""第\s*([一二三四五六七八九十]+|\d+)\s*(章|单元|模块|专题)""").find(part)?.let { return "第${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("""^\s*(?:Unit|UNIT|unit)\s*(\d+)""").find(part)?.let { return "Unit ${it.groupValues[1]}" }
        Regex("""^\s*(?:P\d+[\s._-]+|\d+[\s、_-]+)?(\d{1,2})\.\d+""").find(part)?.let {
            val n = it.groupValues[1].toInt()
            return if (n <= 10) "第${CN_NUM[n]}章" else "第${n}章"
        }
        return null
    }

    fun group(lessons: List<Lesson>): List<Pair<String?, List<Int>>> {
        val groups = ArrayList<Pair<String?, MutableList<Int>>>()
        var labelled = 0
        lessons.forEachIndexed { i, l ->
            val lab = label(l.part)
            if (lab != null) labelled++
            if (groups.isEmpty() || (lab != null && lab != groups.last().first)) groups.add(lab to ArrayList())
            groups.last().second.add(i)
        }
        if (labelled < lessons.size * 0.5 || groups.count { it.first != null } < 2) return listOf(null to lessons.indices.toList())
        return groups
    }
}
