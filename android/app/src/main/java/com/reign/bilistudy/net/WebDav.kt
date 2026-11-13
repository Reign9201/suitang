package com.reign.bilistudy.net

import com.reign.bilistudy.core.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV 账号。坚果云的地址是 https://dav.jianguoyun.com/dav/，密码是「应用密码」。 */
data class DavAccount(val url: String, val user: String, val password: String) {
    companion object { const val JIANGUOYUN = "https://dav.jianguoyun.com/dav/" }
}

data class RemoteFile(val name: String, val etag: String)

/** 同步只需要四种请求：MKCOL 建文件夹、PROPFIND 列文件、GET 下载、PUT 上传。都在账号地址下的 bilistudy/ 里。和 Montage 的 WebDav 一致。 */
class WebDav(private val account: DavAccount) {
    companion object {
        const val FOLDER = "bilistudy"
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

        /** 解析 PROPFIND 的 207 回复：每个 response 里的 href（取文件名）和 getetag。命名空间前缀各家不同，只看本地名。 */
        fun parsePropfind(xml: String): List<RemoteFile> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val responses = doc.getElementsByTagNameNS("*", "response")
            val out = ArrayList<RemoteFile>()
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = r.getElementsByTagNameNS("*", "href").item(0)?.textContent?.trim().orEmpty()
                val etag = r.getElementsByTagNameNS("*", "getetag").item(0)?.textContent?.trim().orEmpty()
                val decoded = URLDecoder.decode(href, "UTF-8")
                if (decoded.endsWith("/")) continue
                out.add(RemoteFile(decoded.substringAfterLast('/'), etag))
            }
            return out
        }
    }

    private val folder: HttpUrl = (account.url.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull()
        ?: throw AppException("网盘地址无效")).newBuilder().addPathSegment(FOLDER).addPathSegment("").build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))

    private fun fail(code: Int): Nothing = throw AppException(when (code) {
        401 -> "账号或应用密码不对"
        403 -> "网盘拒绝访问（403）"
        404 -> "网盘地址不对（404）"
        507 -> "网盘空间不足"
        else -> "网盘返回 $code"
    })

    private suspend fun <T> call(request: Request, block: (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
        try { http.newCall(request).execute().use(block) } catch (e: IOException) { throw AppException("连不上网盘：${e.message}") }
    }

    /** 建文件夹；已存在也算成功（405，有的服务返回 409 或 301）。 */
    suspend fun ensureFolder() = call(request(folder).method("MKCOL", null).build()) { if (it.code !in setOf(200, 201, 301, 405, 409)) fail(it.code) }

    suspend fun list(): List<RemoteFile> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""
        val r = request(folder).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        return call(r) { if (it.code != 207 && !it.isSuccessful) fail(it.code); parsePropfind(it.body.string()) }.filter { it.name.endsWith(".json") }
    }

    suspend fun get(name: String): Pair<String, String> =
        call(request(folder.newBuilder().addPathSegment(name).build()).get().build()) {
            if (!it.isSuccessful) fail(it.code)
            it.body.string() to it.header("ETag").orEmpty()
        }

    suspend fun put(name: String, json: String) =
        call(request(folder.newBuilder().addPathSegment(name).build()).put(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()) {
            if (!it.isSuccessful) fail(it.code)
        }
}
