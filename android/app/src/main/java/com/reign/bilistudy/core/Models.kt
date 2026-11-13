package com.reign.bilistudy.core

/** 大模型对一条视频的判断，字段和 ai.py 的 ORGANIZE_SCHEMA 一致。 */
data class AIJudgement(
    val fit: String,
    val kind: String,
    val level: String,
    val edition: String,
    val summary: String,
    val quality: Int,
    val comment: String,
)

/** 一门课（搜索结果里的一条视频）。 */
data class Course(
    val bvid: String,
    val title: String,
    val author: String,
    val cover: String,
    val duration: Int,
    val play: Int,
    val favorites: Int,
    val pubdate: Int,
    val typeid: Int,
    val episodes: Int,
    val desc: String,
    val tagtext: String,
    val editions: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val grades: List<Int> = emptyList(),
    val subjects: List<String> = emptyList(),
    val series: Boolean = false,
    val review: Boolean = false,
    val newTextbook: Boolean = false,
    val score: Double = 0.0,
    val ai: AIJudgement? = null,
) {
    /** B 站图片服务按尺寸裁剪，列表里不需要原图。 */
    val thumb: String get() = thumbOf(cover)
}

fun thumbOf(cover: String) = if (cover.contains("hdslb.com")) "$cover@336w_189h_1c.jpg" else cover

data class Rejection(val reason: String, val count: Int, val samples: List<String>)

/** 某个分类（年级、学科、册次）整理好的结果。 */
data class Catalog(
    val grade: Int,
    val subject: String,
    val volume: String,
    val queries: List<String>,
    val seen: Int,
    val items: List<Course>,
    val rejected: List<Rejection>,
    val builtAt: Long,
    val picks: List<String> = emptyList(),
)

/** 视频详情里的一节：分 P 视频用 p 区分，B 站「合集」里每一节是独立的 BV 号。 */
data class Lesson(val key: String, val bvid: String, val cid: Long, val page: Int, val part: String, val duration: Int)

data class CourseDetail(
    val bvid: String,
    val title: String,
    val author: String,
    val cover: String,
    val play: Int,
    val lessons: List<Lesson>,
    val seasonTitle: String?,
) {
    val displayTitle get() = seasonTitle ?: title
}

data class PlaySource(val url: String, val quality: String, val headers: Map<String, String>)

/** 一句话搜索解析出的条件。 */
data class SearchIntent(val grade: Int, val subject: String, val volume: String, val topic: String, val query: String)

/** 打开一门课时带着的来源分类。 */
data class CourseContext(val grade: Int? = null, val subject: String? = null, val volume: String? = null)

object Fmt {
    fun duration(seconds: Int): String {
        val s = maxOf(0, seconds)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun hours(seconds: Int) = if (seconds >= 3600) "%.1f 小时".format(seconds / 3600.0) else "${maxOf(1, seconds / 60)} 分钟"

    fun count(n: Int): String = when {
        n >= 100_000_000 -> "%.1f".format(n / 1e8).removeSuffix(".0") + " 亿"
        n >= 10_000 -> "%.1f".format(n / 1e4).removeSuffix(".0") + " 万"
        else -> "$n"
    }
}

class AppException(message: String) : Exception(message)
