package com.reign.bilistudy.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.reign.bilistudy.net.DavAccount
import com.reign.bilistudy.net.WebDav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 多端同步：每台设备只写网盘里自己的文件，读别人的文件后在本机合并，见 docs/sync-format.md。
 * 回到前台时最多每分钟一次、收藏变化后约 3 秒、退出播放页时，以及手动「立即同步」。做法和 Montage 的 SyncManager 一致。
 */
class SyncManager(context: Context, private val library: LibraryStore) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val sortedGson = GsonBuilder().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var folderReady = false
    private var lastUploaded: String? = null
    private val remoteCache = HashMap<String, Pair<String, SyncDocument>>()

    var account by mutableStateOf(SecureStore.get("sync.account")?.let { runCatching { gson.fromJson(it, DavAccount::class.java) }.getOrNull() })
        private set
    var lastSyncAt by mutableStateOf(prefs.getLong("lastAt", 0))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var devices by mutableStateOf(emptyList<String>())
        private set

    val deviceId: String = prefs.getString("device", null) ?: UUID.randomUUID().toString().uppercase().also { prefs.edit().putString("device", it).apply() }
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    init {
        library.onChange = { urgent -> if (urgent) schedule(3000) }
    }

    val statusText: String
        get() {
            val a = account ?: return "未登录，学习记录只保存在这台设备上"
            val host = runCatching { java.net.URI(a.url).host }.getOrNull() ?: a.url
            return when {
                running -> "正在同步 $host"
                error != null -> "同步失败：$error"
                lastSyncAt == 0L -> "已登录 $host，还没有同步过"
                else -> "已登录 $host，上次同步 ${timeText(lastSyncAt)}"
            }
        }

    private fun timeText(t: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val same = d.get(Calendar.YEAR) == today.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (same) "今天 HH:mm" else "M 月 d 日 HH:mm", Locale.CHINA).format(Date(t))
    }

    /** 保存账号前先实际访问一次网盘，账号或密码不对直接抛错。 */
    suspend fun login(a: DavAccount) {
        val dav = WebDav(a)
        dav.ensureFolder()
        dav.list()
        SecureStore.set("sync.account", gson.toJson(a))
        account = a
        folderReady = true
        remoteCache.clear()
        lastUploaded = null
        error = null
        syncNow()
    }

    fun logout() {
        SecureStore.set("sync.account", null)
        account = null
        error = null
        remoteCache.clear()
        lastUploaded = null
        folderReady = false
    }

    fun schedule(delayMs: Long) {
        if (account == null) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); syncNow() }
    }

    /** 回到前台时调用：距上次同步不到一分钟就跳过。 */
    fun syncIfStale() {
        if (System.currentTimeMillis() - lastSyncAt < 60_000) return
        schedule(500)
    }

    suspend fun syncNow() = mutex.withLock {
        val a = account ?: return@withLock
        running = true
        try {
            val dav = WebDav(a)
            if (!folderReady) { dav.ensureFolder(); folderReady = true }
            val own = "$deviceId.json"
            val docs = ArrayList<SyncDocument>()
            val names = ArrayList<String>()
            for (file in dav.list()) {
                if (file.name == own) continue
                val cached = remoteCache[file.name]
                val doc = if (cached != null && file.etag.isNotEmpty() && cached.first == file.etag) cached.second else {
                    val (body, etag) = dav.get(file.name)
                    val d = runCatching { gson.fromJson(body, SyncDocument::class.java) }.getOrNull() ?: continue
                    remoteCache[file.name] = etag.ifEmpty { file.etag } to d
                    d
                }
                docs.add(doc)
                if (doc.deviceName.isNotEmpty()) names.add(doc.deviceName)
            }
            // 本机的集合和别人的一起合并；不认识的集合也合并后原样保留
            val now = System.currentTimeMillis()
            val local = library.sets
            val allNames = local.keys + docs.flatMap { it.sets.orEmpty().keys }
            val merged = allNames.associateWith { name ->
                val m = SyncSet.merge(listOfNotNull(local[name]) + docs.mapNotNull { it.sets.orEmpty()[name] }, now)
                when (name) { "history" -> m.trimmed(100); "progress", "watched" -> m.trimmed(3000); else -> m }
            }
            library.replace(merged)
            // 内容（不含保存时间）和上次上传的一样就不再上传
            val fingerprint = sortedGson.toJson(sortedSets(library.sets))
            if (fingerprint != lastUploaded) {
                dav.put(own, gson.toJson(SyncDocument(device = deviceId, deviceName = deviceName, savedAt = now, sets = library.sets)))
                lastUploaded = fingerprint
            }
            devices = names
            error = null
            lastSyncAt = now
            prefs.edit().putLong("lastAt", now).apply()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
        }
    }

    private fun sortedSets(sets: Map<String, SyncSet>) = sets.toSortedMap().mapValues { (_, s) -> SyncSet(s.items.toSortedMap(), s.removals.toSortedMap()) }

    @Suppress("SENSELESS_COMPARISON")
    private fun Map<String, SyncSet>?.orEmpty(): Map<String, SyncSet> = this ?: emptyMap()
}
