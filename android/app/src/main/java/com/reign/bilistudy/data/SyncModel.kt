package com.reign.bilistudy.data

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** [at] 是最后一次修改的毫秒时间；[data] 由各功能自己读写，同步不看。 */
data class SyncItem(val at: Long = 0, val data: JsonElement? = null)

/** 一个同步集合：键 → 最后一次修改；删除记成墓碑，见 docs/sync-format.md。和 Montage 的 SyncSet、iOS 的 SyncSet 一致。 */
data class SyncSet(
    val items: Map<String, SyncItem> = emptyMap(),
    val removals: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val REMOVAL_MEMORY_MS = 180L * 24 * 3_600_000

        /** 每个键各自按时间决定，和读文件的顺序无关；删除时间不早于最新修改时删掉并保留墓碑；墓碑 180 天后忘掉。 */
        fun merge(sets: List<SyncSet>, now: Long): SyncSet {
            val items = HashMap<String, SyncItem>()
            val removed = HashMap<String, Long>()
            for (set in sets) {
                // Gson 会把旧文件或别家文件里缺的字段留成 null
                @Suppress("SENSELESS_COMPARISON")
                if (set.items != null) set.items.forEach { (k, item) -> if (item != null && (items[k]?.at ?: Long.MIN_VALUE) < item.at) items[k] = item }
                @Suppress("SENSELESS_COMPARISON")
                if (set.removals != null) set.removals.forEach { (k, at) -> if (at != null && (removed[k] ?: Long.MIN_VALUE) < at) removed[k] = at }
            }
            val kept = items.filter { (k, item) -> item.at > (removed[k] ?: Long.MIN_VALUE) }
            return SyncSet(kept, removed.filter { (k, at) -> now - at < REMOVAL_MEMORY_MS && k !in kept })
        }
    }

    /** 只留最新的 limit 条（丢掉的不记删除）。 */
    fun trimmed(limit: Int): SyncSet = if (items.size <= limit) this else
        copy(items = items.entries.sortedByDescending { it.value.at }.take(limit).associate { it.key to it.value })
}

/** 一台设备在网盘里的文件 bilistudy/<设备 ID>.json。 */
data class SyncDocument(
    val version: Int = 1,
    val device: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    val savedAt: Long = 0,
    val sets: Map<String, SyncSet> = emptyMap(),
)

/** 收藏时的课程快照，离线也能显示。 */
data class SavedCourse(
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String,
    val duration: Int,
    val episodes: Int,
    val grade: Int? = null,
    val subject: String? = null,
    val volume: String? = null,
)

/** 「最近在学」里的一门课：上次看到的那一节。 */
data class HistoryEntry(
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String,
    val lesson: String,
    val index: Int,
    val part: String,
    val total: Int,
    val grade: Int? = null,
    val subject: String? = null,
    val volume: String? = null,
)

data class LessonProgress(
    @SerializedName("position") val position: Double,
    @SerializedName("duration") val duration: Double,
)
