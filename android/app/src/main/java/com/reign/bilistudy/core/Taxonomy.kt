package com.reign.bilistudy.core

/** 年级、学科、册次目录。和 Python 原型 classify.py、iOS Taxonomy.swift 保持一致，改动时三处一起改。 */
object Taxonomy {
    const val CN = "一二三四五六七八九"

    val stages = listOf("小学" to (1..6).toList(), "初中" to (7..9).toList(), "高中" to (10..12).toList())

    fun gradeName(n: Int) = if (n <= 9) "${CN[n - 1]}年级" else "高${CN[n - 10]}"
    fun gradeShort(n: Int) = if (n <= 9) "${CN[n - 1]}" else "高${CN[n - 10]}"
    fun stage(n: Int) = if (n <= 6) "小学" else if (n <= 9) "初中" else "高中"

    fun subjects(n: Int): List<String> = when {
        n <= 2 -> listOf("语文", "数学", "道德与法治", "科学")
        n <= 6 -> listOf("语文", "数学", "英语", "科学", "道德与法治")
        n == 7 -> listOf("语文", "数学", "英语", "生物", "地理", "历史", "道德与法治")
        n == 8 -> listOf("语文", "数学", "英语", "物理", "生物", "地理", "历史", "道德与法治")
        n == 9 -> listOf("语文", "数学", "英语", "物理", "化学", "历史", "道德与法治")
        else -> listOf("语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "政治")
    }

    fun volumes(n: Int, subject: String): List<String> = when {
        n == 9 && subject == "物理" -> listOf("全一册")
        n <= 9 -> listOf("上册", "下册")
        n == 10 -> listOf("必修一", "必修二")
        n == 11 -> listOf("选择性必修一", "选择性必修二")
        else -> listOf("一轮复习", "二轮复习")
    }

    /** 书脊上的小字，例如「七上」「选必一」。 */
    fun volumeShort(n: Int, volume: String): String = when {
        n <= 9 && (volume == "上册" || volume == "下册") -> "${CN[n - 1]}${volume[0]}"
        volume.startsWith("选择性必修") -> "选必" + volume.last()
        volume.endsWith("轮复习") -> volume.take(2)
        else -> volume
    }

    private fun volumeQueryText(subject: String, volume: String): String = if (subject != "语文") volume else
        mapOf("必修一" to "必修上册", "必修二" to "必修下册", "选择性必修一" to "选择性必修上册", "选择性必修二" to "选择性必修中册")[volume] ?: volume

    /** 每个分类用两个搜索词，见 classify.make_queries。 */
    fun queries(n: Int, subject: String, volume: String): List<String> {
        val v = volumeQueryText(subject, volume)
        return when {
            n <= 6 -> listOf("${gradeName(n)}$subject$v", "${gradeName(n)}$subject$v 全集")
            n <= 9 -> listOf("${gradeName(n)}$subject$v", "初${CN[n - 7]}$subject$v")
            n <= 11 -> listOf("高中$subject$v", "${gradeShort(n)}$subject$v")
            else -> listOf("高考$subject$v", "高三$subject$v")
        }
    }

    val allSubjects = listOf("语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "道德与法治", "政治", "科学")
}
