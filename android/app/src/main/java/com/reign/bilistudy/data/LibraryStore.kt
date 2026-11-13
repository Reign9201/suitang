package com.reign.bilistudy.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 本机的学习数据：收藏、最近在学、每节进度、看完的节、年级和版本选择。
 * 直接用同步集合的结构保存，同步时整份交给 SyncManager 合并。和 iOS LibraryStore 一致。
 */
class LibraryStore(context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "library.json")
    var sets by mutableStateOf(load())
        private set
    /** 收藏变化时调用（SyncManager 用它安排 3 秒后同步）。 */
    var onChange: ((urgent: Boolean) -> Unit)? = null

    private fun load(): Map<String, SyncSet> = runCatching {
        gson.fromJson<Map<String, SyncSet>>(file.readText(), object : TypeToken<Map<String, SyncSet>>() {}.type)
    }.getOrNull() ?: emptyMap()

    private fun save() = runCatching { file.writeText(gson.toJson(sets)) }

    private fun update(name: String, urgent: Boolean = false, change: (SyncSet) -> SyncSet) {
        var set = change(sets[name] ?: SyncSet())
        set = when (name) {
            "history" -> set.trimmed(100)
            "progress", "watched" -> set.trimmed(3000)
            else -> set
        }
        sets = sets + (name to set)
        save()
        onChange?.invoke(urgent)
    }

    /** 同步后整份替换；返回是否有变化。 */
    fun replace(merged: Map<String, SyncSet>): Boolean {
        val next = sets + merged
        if (next == sets) return false
        sets = next
        save()
        return true
    }

    private fun now() = System.currentTimeMillis()

    // 收藏

    val favorites: List<SavedCourse>
        get() = sets["favorites"]?.items.orEmpty().entries.sortedByDescending { it.value.at }
            .mapNotNull { runCatching { gson.fromJson(it.value.data, SavedCourse::class.java) }.getOrNull() }

    fun isFavorite(bvid: String) = sets["favorites"]?.items?.containsKey(bvid) == true

    fun toggleFavorite(course: SavedCourse) {
        val t = now()
        update("favorites", urgent = true) { set ->
            if (set.items.containsKey(course.bvid)) SyncSet(set.items - course.bvid, set.removals + (course.bvid to t))
            else SyncSet(set.items + (course.bvid to SyncItem(t, gson.toJsonTree(course))), set.removals - course.bvid)
        }
    }

    // 最近在学与进度

    val history: List<HistoryEntry>
        get() = sets["history"]?.items.orEmpty().entries.sortedByDescending { it.value.at }
            .mapNotNull { runCatching { gson.fromJson(it.value.data, HistoryEntry::class.java) }.getOrNull() }

    fun historyEntry(bvid: String): HistoryEntry? =
        sets["history"]?.items?.get(bvid)?.let { runCatching { gson.fromJson(it.data, HistoryEntry::class.java) }.getOrNull() }

    fun record(entry: HistoryEntry) = update("history") { it.copy(items = it.items + (entry.bvid to SyncItem(now(), gson.toJsonTree(entry)))) }

    fun progress(bvid: String, lesson: String): LessonProgress? =
        sets["progress"]?.items?.get("$bvid|$lesson")?.let { runCatching { gson.fromJson(it.data, LessonProgress::class.java) }.getOrNull() }

    fun setProgress(bvid: String, lesson: String, position: Double, duration: Double) =
        update("progress") { it.copy(items = it.items + ("$bvid|$lesson" to SyncItem(now(), gson.toJsonTree(LessonProgress(position, duration))))) }

    fun isWatched(bvid: String, lesson: String) = sets["watched"]?.items?.containsKey("$bvid|$lesson") == true

    fun markWatched(bvid: String, lesson: String) {
        if (isWatched(bvid, lesson)) return
        update("watched") { it.copy(items = it.items + ("$bvid|$lesson" to SyncItem(now(), JsonPrimitive(true)))) }
    }

    fun watchedCount(bvid: String) = sets["watched"]?.items.orEmpty().keys.count { it.startsWith("$bvid|") }

    // 选择（年级、学科、册次、版本）

    fun prefString(key: String): String? = sets["prefs"]?.items?.get(key)?.data?.let { runCatching { it.asString }.getOrNull() }
    fun prefInt(key: String): Int? = sets["prefs"]?.items?.get(key)?.data?.let { runCatching { it.asInt }.getOrNull() }

    fun setPref(key: String, value: Any) {
        val element = gson.toJsonTree(value)
        if (sets["prefs"]?.items?.get(key)?.data == element) return
        update("prefs") { it.copy(items = it.items + (key to SyncItem(now(), element))) }
    }

    val grade: Int? get() = prefInt("grade")
}
