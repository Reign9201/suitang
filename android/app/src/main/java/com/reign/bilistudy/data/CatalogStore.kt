package com.reign.bilistudy.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.reign.bilistudy.core.AIJudgement
import com.reign.bilistudy.core.Catalog
import com.reign.bilistudy.core.Classify
import com.reign.bilistudy.core.Course
import com.reign.bilistudy.core.Prompts
import com.reign.bilistudy.core.Rejection
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.net.AIClient
import com.reign.bilistudy.net.AIConfig
import com.reign.bilistudy.net.BiliClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.UUID

sealed class AIStatus {
    data class Off(val reason: String) : AIStatus()
    data object Pending : AIStatus()
    data class Ready(val model: String) : AIStatus()
    data class Failed(val message: String) : AIStatus()
}

/**
 * 整理分类：先用规则立刻给出结果，再在后台让大模型整理，好了以后替换。
 * 规则结果和大模型结果都缓存在本机 24 小时，和原型 server.py、iOS CatalogStore 一致。
 */
class CatalogStore(context: Context) {
    val catalogs = mutableStateMapOf<String, Catalog>()
    val errors = mutableStateMapOf<String, String>()
    val aiStatus = mutableStateMapOf<String, AIStatus>()
    private val loading = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()
    private val dir = File(context.cacheDir, "catalog").apply { mkdirs() }

    companion object {
        fun key(g: Int, s: String, v: String) = "$g|$s|$v"

        /** String.hashCode 在各版本间稳定，但为了和 iOS 一致，缓存键用 FNV-1a。 */
        fun stableHash(s: String): String {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in s.toByteArray()) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            return java.lang.Long.toUnsignedString(h, 16)
        }
    }

    private fun file(name: String) = File(dir, URLEncoder.encode(name, "UTF-8"))

    private inline fun <reified T> readCache(name: String, maxAgeMs: Long): T? {
        val f = file(name)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return runCatching { gson.fromJson<T>(f.readText(), object : TypeToken<T>() {}.type) }.getOrNull()
    }

    private fun writeCache(name: String, value: Any) = runCatching { file(name).writeText(gson.toJson(value)) }

    fun load(grade: Int, subject: String, volume: String, refresh: Boolean = false) {
        val k = key(grade, subject, volume)
        if (!refresh && catalogs[k] != null) { enrich(k); return }
        if (!loading.add(k)) return
        errors.remove(k)
        scope.launch {
            try {
                val cached: Catalog? = if (refresh) null else withContext(Dispatchers.IO) { readCache<Catalog>("rules-$k", 24 * 3600_000L) }
                val catalog = cached ?: build(grade, subject, volume).also { c -> withContext(Dispatchers.IO) { writeCache("rules-$k", c) } }
                catalogs[k] = catalog
                enrich(k)
            } catch (e: Exception) {
                errors[k] = e.message ?: "整理失败"
            } finally {
                loading.remove(k)
            }
        }
    }

    private suspend fun build(n: Int, subject: String, volume: String): Catalog {
        val queries = Taxonomy.queries(n, subject, volume)
        val seen = HashSet<String>()
        val kept = ArrayList<Course>()
        val rejected = LinkedHashMap<String, Pair<Int, MutableList<String>>>()
        fun reject(reason: String, sample: String?) {
            val r = rejected.getOrPut(reason) { 0 to ArrayList() }
            rejected[reason] = (r.first + 1) to r.second.apply { if (sample != null && size < 3) add(sample.take(60)) }
        }
        for (q in queries) {
            val data = BiliClient.search(q)
            for (el in data.getAsJsonArray("result") ?: com.google.gson.JsonArray()) {
                val row = el as? JsonObject ?: continue
                val (item, reason) = Classify.baseItem(row)
                if (!seen.add(item?.bvid ?: UUID.randomUUID().toString())) continue
                if (item == null) { reject(reason ?: "无法播放", null); continue }
                val r = Classify.reject(item, n, subject, volume)
                if (r != null) { reject(r, item.title); continue }
                kept.add(Classify.finish(item, n, subject, volume))
            }
        }
        kept.sortByDescending { it.score }
        return Catalog(n, subject, volume, queries, seen.size, kept,
            rejected.map { (reason, v) -> Rejection(reason, v.first, v.second) }.sortedByDescending { it.count }, System.currentTimeMillis())
    }

    /** 后台大模型整理。模型名在缓存键里：换了模型会重新整理。 */
    private fun enrich(k: String) {
        val catalog = catalogs[k] ?: return
        val config = AIConfig.load()
        config.unavailableReason?.let { aiStatus[k] = AIStatus.Off(it); return }
        if (catalog.items.any { it.ai != null } || aiStatus[k] == AIStatus.Pending) return
        val model = config.organizeModel
        val cacheName = "ai${Prompts.ORGANIZE_VERSION}-$model-$k-${stableHash(catalog.items.map { it.bvid }.sorted().joinToString(","))}"
        val cached: Map<String, AIJudgement>? = readCache(cacheName, 24 * 3600_000L)
        if (cached != null) { catalogs[k] = Classify.applyAI(catalog, cached); aiStatus[k] = AIStatus.Ready(model); return }
        if (catalog.items.isEmpty()) { aiStatus[k] = AIStatus.Ready(model); return }
        aiStatus[k] = AIStatus.Pending
        scope.launch {
            try {
                val judged = AIClient(config).organize(Taxonomy.gradeName(catalog.grade), Taxonomy.stage(catalog.grade),
                    catalog.subject, catalog.volume, catalog.items, model)
                withContext(Dispatchers.IO) { writeCache(cacheName, judged) }
                catalogs[k]?.let { catalogs[k] = Classify.applyAI(it, judged) }
                aiStatus[k] = AIStatus.Ready(model)
            } catch (e: Exception) {
                aiStatus[k] = AIStatus.Failed(e.message ?: "大模型整理出错")
            }
        }
    }

    /** 改了大模型设置后，让失败或关闭状态的分类重新整理。 */
    fun resetAI() {
        aiStatus.keys.toList().forEach { k ->
            if (aiStatus[k] !is AIStatus.Ready) { aiStatus.remove(k); enrich(k) }
        }
    }
}
