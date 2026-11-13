package com.reign.bilistudy.net

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reign.bilistudy.core.AppException
import com.reign.bilistudy.core.Classify
import com.reign.bilistudy.core.CourseDetail
import com.reign.bilistudy.core.Lesson
import com.reign.bilistudy.core.PlaySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B 站公开网页接口，只用访客身份（buvid3 Cookie），不登录、不处理付费内容。
 * 做法和 Montage 的 BilibiliSearch / BilibiliVideoClient、原型 server.py、iOS BiliClient 一致。
 */
object BiliClient {
    private const val API_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    /** 视频服务器拒绝手机浏览器的 UA，桌面 UA 可以（Montage 实测）。 */
    const val MEDIA_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val throttle = Mutex()
    private var lastCall = 0L
    @Volatile private var cookie = ""
    @Volatile private var cookieAt = 0L

    private suspend fun raw(url: String, referer: String, cookie: String = ""): Pair<Int, String> {
        throttle.withLock {
            val wait = lastCall + 350 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", API_UA).header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }.build()
            try {
                http.newCall(request).execute().use { it.code to it.body.string() }
            } catch (e: IOException) {
                throw AppException("连不上 B 站：${e.message}")
            }
        }
    }

    private suspend fun visitorCookie(force: Boolean = false): String {
        if (!force && cookie.isNotEmpty() && System.currentTimeMillis() - cookieAt < 12 * 3600_000L) return cookie
        val loaded = runCatching {
            val (_, body) = raw("https://api.bilibili.com/x/frontend/finger/spi", "https://www.bilibili.com/")
            val d = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
            val b3 = d.get("b_3")?.asString.orEmpty()
            val b4 = d.get("b_4")?.asString.orEmpty()
            if (b3.isEmpty()) "" else if (b4.isEmpty()) "buvid3=$b3" else "buvid3=$b3; buvid4=$b4"
        }.getOrDefault("")
        cookie = loaded
        cookieAt = if (loaded.isEmpty()) 0 else System.currentTimeMillis()
        return loaded
    }

    /** 取 data 字段；遇到 412 风控换一个访客 Cookie 重试两次。 */
    private suspend fun get(url: String, referer: String): JsonObject {
        var c = visitorCookie()
        repeat(3) { attempt ->
            val (status, body) = raw(url, referer, c)
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            val code = root?.get("code")?.let { runCatching { it.asInt }.getOrNull() }
            if (status != 412 && code != -412) {
                if (status != 200 || root == null) throw AppException("B 站返回 HTTP $status")
                if (code != 0) throw AppException("B 站返回错误 $code：${root.get("message")?.asString.orEmpty()}")
                return root.getAsJsonObject("data") ?: JsonObject()
            }
            delay(400L * (attempt + 1))
            c = visitorCookie(force = true)
        }
        throw AppException("B 站暂时拒绝了请求，过一会儿再试")
    }

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 30, tids: Int? = Classify.STUDY_TID): JsonObject {
        val url = "https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "video").addQueryParameter("keyword", keyword)
            .addQueryParameter("order", "totalrank").addQueryParameter("page", "$page")
            .addQueryParameter("page_size", "$pageSize")
            .apply { if (tids != null) addQueryParameter("tids", "$tids") }.build().toString()
        return get(url, "https://search.bilibili.com/")
    }

    suspend fun detail(bvid: String): CourseDetail {
        val url = "https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder().addQueryParameter("bvid", bvid).build().toString()
        val d = get(url, "https://www.bilibili.com/video/$bvid/")
        val title = Classify.clean(d.get("title")?.asString)
        val pages = (d.get("pages") as? JsonArray).orEmpty().map { it.asJsonObject }.map {
            val page = Classify.int(it["page"])
            Lesson("p$page", bvid, it["cid"]?.asLong ?: 0, page, Classify.clean(it["part"]?.asString), Classify.int(it["duration"]))
        }
        // B 站「合集」：每一节是独立的 BV 号；分 P 视频没有这一项
        val seasonObj = d.get("ugc_season")?.takeIf { it.isJsonObject }?.asJsonObject
        val season = ArrayList<Lesson>()
        seasonObj?.getAsJsonArray("sections")?.forEach { section ->
            section.asJsonObject.getAsJsonArray("episodes")?.forEach { e ->
                val ep = e.asJsonObject
                val id = ep.get("bvid")?.asString ?: return@forEach
                val cid = ep.get("cid")?.asLong?.takeIf { it > 0 } ?: ep.getAsJsonObject("page")?.get("cid")?.asLong ?: 0
                season.add(Lesson(id, id, cid, 1, Classify.clean(ep.get("title")?.asString), Classify.int(ep.getAsJsonObject("arc")?.get("duration"))))
            }
        }
        val (lessons, seasonTitle) = when {
            pages.size > 1 -> pages to null
            season.size > 1 -> season to Classify.clean(seasonObj?.get("title")?.asString)
            else -> listOf(Lesson("p1", bvid, pages.firstOrNull()?.cid ?: (d.get("cid")?.asLong ?: 0), 1, title, Classify.int(d["duration"]))) to null
        }
        return CourseDetail(bvid, title, Classify.clean(d.getAsJsonObject("owner")?.get("name")?.asString),
            Classify.https(d.get("pic")?.asString.orEmpty()), Classify.int(d.getAsJsonObject("stat")?.get("view")), lessons, seasonTitle)
    }

    /** 访客可取的 MP4 地址（目前只有 360P）。播放时必须带 B 站的 Referer。 */
    suspend fun playSource(bvid: String, cid: Long): PlaySource {
        val url = "https://api.bilibili.com/x/player/playurl".toHttpUrl().newBuilder()
            .addQueryParameter("bvid", bvid).addQueryParameter("cid", "$cid").addQueryParameter("qn", "64")
            .addQueryParameter("fnval", "1").addQueryParameter("fnver", "0").addQueryParameter("fourk", "0").build().toString()
        val referer = "https://www.bilibili.com/video/$bvid/"
        val d = get(url, referer)
        val first = d.getAsJsonArray("durl")?.firstOrNull()?.asJsonObject
        val src = first?.get("url")?.asString ?: throw AppException("这个视频暂时只能在 B 站播放")
        val q = Classify.int(d["quality"])
        val quality = mapOf(16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P")[q] ?: "${q}P"
        return PlaySource(Classify.https(src), quality, mapOf("Referer" to referer, "Origin" to "https://www.bilibili.com"))
    }

    private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()
}
