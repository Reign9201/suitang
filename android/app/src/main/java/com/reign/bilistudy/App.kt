package com.reign.bilistudy

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.reign.bilistudy.core.CourseContext
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.data.CatalogStore
import com.reign.bilistudy.data.LibraryStore
import com.reign.bilistudy.data.SavedCourse
import com.reign.bilistudy.data.SecureStore
import com.reign.bilistudy.data.SyncManager
import com.reign.bilistudy.net.AIConfig
import com.reign.bilistudy.net.DavAccount
import com.reign.bilistudy.ui.Root
import kotlinx.coroutines.launch

enum class Tab { CATALOG, MINE, SETTINGS }

sealed class Screen {
    data class Player(val bvid: String, val context: CourseContext) : Screen()
    data class Search(val query: String) : Screen()
}

/** 全局状态：当前年级、学科、册次（记在 prefs 里，参与同步）、底部导航和页面栈。 */
class AppState(app: Application) {
    val library = LibraryStore(app)
    val catalogs = CatalogStore(app)
    val sync = SyncManager(app, library)
    var tab by mutableStateOf(Tab.CATALOG)
    val stack = mutableStateListOf<Screen>()
    var showSyncDialog by mutableStateOf(false)

    val grade: Int? get() = library.grade

    fun subject(grade: Int): String {
        val all = Taxonomy.subjects(grade)
        return library.prefString("subject|$grade")?.takeIf { it in all } ?: all[0]
    }

    fun volume(grade: Int, subject: String): String {
        val all = Taxonomy.volumes(grade, subject)
        return library.prefString("volume|$grade|$subject")?.takeIf { it in all } ?: all[0]
    }

    fun edition(subject: String) = library.prefString("edition|$subject").orEmpty()

    fun chooseGrade(g: Int) { library.setPref("grade", g); tab = Tab.CATALOG; stack.clear() }
    fun chooseSubject(s: String) { grade?.let { library.setPref("subject|$it", s) } }
    fun chooseVolume(v: String) { grade?.let { library.setPref("volume|$it|${subject(it)}", v) } }
    fun chooseEdition(subject: String, e: String) = library.setPref("edition|$subject", e)

    /** 从搜索的直达入口打开某个分类。 */
    fun open(grade: Int, subject: String, volume: String?) {
        library.setPref("grade", grade)
        library.setPref("subject|$grade", subject)
        if (!volume.isNullOrEmpty()) library.setPref("volume|$grade|$subject", volume)
        tab = Tab.CATALOG
        stack.clear()
    }

    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

class App : Application() {
    lateinit var state: AppState

    override fun onCreate() {
        super.onCreate()
        SecureStore.init(this)
        AIConfig.init(this)
        state = AppState(this)
    }
}

class MainActivity : ComponentActivity() {
    private val state get() = (application as App).state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        debugSetup(intent)
        setContent {
            BackHandler(enabled = state.stack.isNotEmpty()) { state.pop() }
            Root(state)
        }
    }

    override fun onResume() {
        super.onResume()
        state.sync.syncIfStale()
    }

    /**
     * 只在可调试的包里生效，用来在没有手动操作时走流程，避免把密钥写进工程文件：
     * adb shell am start -n com.reign.bilistudy/.MainActivity --es ai_token … --es ai_base … --es ai_model … --es ai_auth bearer
     * --ei grade 7 --es route player:BV…|search:…|mine|settings --es dav 地址|账号|密码
     */
    private fun debugSetup(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        intent.getStringExtra("ai_token")?.let { token ->
            AIConfig.secret = token
            var c = AIConfig.load()
            intent.getStringExtra("ai_base")?.let { c = c.copy(baseUrl = it) }
            intent.getStringExtra("ai_model")?.let { c = c.copy(organizeModel = it, intentModel = it) }
            if (intent.getStringExtra("ai_auth") == "bearer") c = c.copy(auth = "bearer")
            AIConfig.save(c)
        }
        if (intent.hasExtra("grade")) state.library.setPref("grade", intent.getIntExtra("grade", 7))
        intent.getStringExtra("favorite")?.let { b ->
            if (!state.library.isFavorite(b)) state.library.toggleFavorite(SavedCourse(b, intent.getStringExtra("favorite_title") ?: b, "", "", 0, 0, state.grade, state.grade?.let(state::subject)))
        }
        when (val route = intent.getStringExtra("route")) {
            null -> {}
            "mine" -> state.tab = Tab.MINE
            "settings" -> state.tab = Tab.SETTINGS
            else -> if (route.startsWith("search:")) state.push(Screen.Search(route.removePrefix("search:")))
                else if (route.startsWith("player:")) state.grade.let { g ->
                    state.push(Screen.Player(route.removePrefix("player:"), CourseContext(g, g?.let(state::subject), g?.let { state.volume(it, state.subject(it)) })))
                }
        }
        intent.getStringExtra("dav")?.split("|")?.takeIf { it.size == 3 }?.let { (url, user, pass) ->
            lifecycleScope.launch { runCatching { state.sync.login(DavAccount(url, user, pass)) } }
        }
    }
}
