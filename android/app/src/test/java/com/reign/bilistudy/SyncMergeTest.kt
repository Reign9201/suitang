package com.reign.bilistudy

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.reign.bilistudy.data.SyncDocument
import com.reign.bilistudy.data.SyncSet
import com.reign.bilistudy.net.WebDav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跑 docs/sync-cases.json 里和 iOS 共用的合并用例。 */
class SyncMergeTest {
    private val gson = Gson()

    @Test fun sharedCases() {
        val text = javaClass.classLoader!!.getResource("sync-cases.json")!!.readText()
        val root = JsonParser.parseString(text).asJsonObject
        val now = root.get("now").asLong
        val cases = root.getAsJsonArray("cases")
        assertTrue(cases.size() > 0)
        for (c in cases.map { it.asJsonObject }) {
            val sets = c.getAsJsonArray("sets").map { gson.fromJson(it, SyncSet::class.java) }
            val expected = gson.fromJson(c.get("expected"), SyncSet::class.java)
            val name = c.get("name").asString
            assertEquals(name, expected, SyncSet.merge(sets, now))
            assertEquals("$name（倒序）", expected, SyncSet.merge(sets.reversed(), now))
        }
    }

    @Test fun readsIosDocument() {
        // iOS 写出的文件（数字没有小数点、有不认识的集合）也能读，并原样保留
        val json = """{"version":1,"device":"D","deviceName":"iPad","platform":"ios","savedAt":5,
            "sets":{"favorites":{"items":{"BV1":{"at":5,"data":{"bvid":"BV1","title":"t","cover":"","author":"a","duration":10,"episodes":56,"grade":7}}},"removals":{}},
                    "future":{"items":{"x":{"at":1,"data":{"k":1}}}}}}"""
        val doc = gson.fromJson(json, SyncDocument::class.java)
        assertEquals("ios", doc.platform)
        assertEquals(5L, doc.sets["favorites"]!!.items["BV1"]!!.at)
        assertEquals(1, SyncSet.merge(listOf(doc.sets["future"]!!), 10).items.size)
    }

    @Test fun propfind() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/dav/bilistudy/</D:href></D:response>
            <D:response><D:href>/dav/bilistudy/ABC.json</D:href><D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        assertEquals(listOf(com.reign.bilistudy.net.RemoteFile("ABC.json", "\"e1\"")), WebDav.parsePropfind(xml))
    }
}
