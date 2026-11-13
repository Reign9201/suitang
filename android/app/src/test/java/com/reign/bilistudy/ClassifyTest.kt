package com.reign.bilistudy

import com.reign.bilistudy.core.AIJudgement
import com.reign.bilistudy.core.Chapters
import com.reign.bilistudy.core.Classify
import com.reign.bilistudy.core.Course
import com.reign.bilistudy.net.AIClient
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 和 Python 原型 classify.py、iOS ClassifyTests 用同样的样例，保证三边规则一致。 */
class ClassifyTest {
    private fun course(title: String, typeid: Int = 208, tags: String = "", episodes: Int = 0) =
        Course("BV1234567890", title, "某老师", "", 3600, 1, 0, 0, typeid, episodes, "", tags)

    @Test fun grades() {
        assertEquals(setOf(7), Classify.grades("初中数学7年级上册").first)
        assertEquals(setOf(7), Classify.grades("提高一倍成绩 初一上册").first)
        assertEquals(setOf(7), Classify.grades("【56集全】人教版七上数学新教材").first)
        assertEquals(setOf(7, 8, 9), Classify.grades("初一到初三全套").first)
        assertEquals((1..6).toSet(), Classify.grades("1-6年级奥数").first)
        assertEquals(setOf(7), Classify.grades("七年级数学 直击中考").first)
        assertEquals(setOf(12), Classify.grades("高考数学一轮复习").second)
        assertEquals(setOf(10), Classify.grades("高中物理必修一").second)
    }

    @Test fun volumesAndEpisodes() {
        assertEquals(setOf("上册", "下册"), Classify.volumes("七年级上下册2026最新版"))
        assertEquals(setOf("上册", "下册"), Classify.volumes("初一上/下册"))
        assertEquals(setOf("必修一"), Classify.volumes("高中语文必修上册"))
        assertEquals(setOf("选择性必修二"), Classify.volumes("选择性必修第二册"))
        assertEquals(170, Classify.episodes("【初一数学170讲】七年级数学全集"))
        assertEquals(100, Classify.episodes("（100P，30+h）"))
        assertEquals(0, Classify.episodes("第12讲 有理数"))
        assertEquals(189, Classify.episodes("全189集【初一数学】"))
        assertEquals(1239 * 60 + 55, Classify.duration("1239:55"))
    }

    @Test fun subjectsAndEditions() {
        assertEquals(setOf("道德与法治"), Classify.subjects("高中思想政治必修一"))
        assertEquals(listOf("统编版"), Classify.editions("人教版七年级语文上册", "语文"))
        assertEquals(listOf("北师大版"), Classify.editions("北师大版 七年级数学", "数学"))
    }

    @Test fun reject() {
        assertNull(Classify.reject(course("人教版七年级数学上册 全集"), 7, "数学", "上册"))
        assertEquals("年级不符", Classify.reject(course("人教版五年级数学上册"), 7, "数学", "上册"))
        assertEquals("学科不符", Classify.reject(course("七年级语文上册"), 7, "数学", "上册"))
        assertEquals("册次不符", Classify.reject(course("七年级数学下册"), 7, "数学", "上册"))
        assertEquals("不在学习分区", Classify.reject(course("七年级数学上册", typeid = 241), 7, "数学", "上册"))
        assertEquals("年级不符", Classify.reject(course("数学上册同步课", tags = "八年级"), 7, "数学", "上册"))
    }

    @Test fun sameCourseAndChapters() {
        assertTrue(Classify.sameCourse(course("全63集 <2026最新版三年级上册语文同步教材课文动画> 上下册63集", episodes = 63),
            course("【63集全【2025最新版三年级语文上册同步教材课文动画】三年级语文上下册63集")))
        assertFalse(Classify.sameCourse(course("【25年秋】小学语文三年级语文上册 （部编版）全册 课文朗读"),
            course("全63集 <2026最新版三年级上册语文同步教材课文动画>", episodes = 63)))
        assertEquals("第一章", Chapters.label("1.2.1 有理数的概念"))
        assertEquals("第二章", Chapters.label("2.1.1 有理数的加法（第1课时）"))
        assertEquals("第三单元", Chapters.label("第三单元 古诗"))
        assertNull(Chapters.label("正数和负数（第1课时）"))
    }

    @Test fun aiCleanup() {
        val (id, j) = AIClient.judgement(JsonParser.parseString("""{"bvid":"BV1","fit":"很对口","kind":"同步课","quality":9}""").asJsonObject)!!
        assertEquals("BV1", id)
        assertEquals(AIJudgement("相关", "同步课", "中等", "", "", 5, ""), j)
        assertEquals(2, AIClient.parseJsonText("```json\n{\"a\":2}\n```").get("a").asInt)
        assertEquals(3, AIClient.parseJsonText("结果如下：{\"a\":3} 以上").get("a").asInt)
    }
}
