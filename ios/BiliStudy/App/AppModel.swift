import Foundation
import UIKit
import Observation

enum Section: Hashable { case catalog, mine, settings }

enum Route: Hashable {
    case player(bvid: String, context: CourseContext)
    case search(String)
}

/// 全局状态：当前年级、学科、册次（记在 prefs 里，参与同步）、侧栏位置和导航栈。
@MainActor
@Observable
final class AppModel {
    let library: LibraryStore
    let catalogs = CatalogStore()
    let sync: SyncManager

    var section: Section = .catalog
    var path: [Route] = []
    var fontVersion = 0
    var showSyncSheet = false

    init() {
        let library = LibraryStore()
        self.library = library
        self.sync = SyncManager(library: library)
        FontLoader.ensureKai { [weak self] in self?.fontVersion += 1 }
        #if DEBUG
        // 调试时可以用启动环境变量预填大模型设置，避免把密钥写进工程文件
        let env = ProcessInfo.processInfo.environment
        if let token = env["BILISTUDY_AI_TOKEN"], (AIConfig.secret ?? "").isEmpty {
            AIConfig.secret = token
            var c = AIConfig.load()
            if let base = env["BILISTUDY_AI_BASE"] { c.baseURL = base }
            if let model = env["BILISTUDY_AI_MODEL"] { c.organizeModel = model; c.intentModel = model }
            if env["BILISTUDY_AI_AUTH"] == "bearer" { c.auth = .bearer }
            c.save()
        }
        // 没有模拟器操作权限时用来走流程：BILISTUDY_DEBUG_GRADE=7，BILISTUDY_DEBUG_ROUTE=player:BV…|search:…|mine|settings
        if let g = env["BILISTUDY_DEBUG_GRADE"].flatMap(Int.init) { library.setPref("grade", g) }
        if let s = env["BILISTUDY_DEBUG_SUBJECT"], let g = library.grade { library.setPref("subject|\(g)", s) }
        if let route = env["BILISTUDY_DEBUG_ROUTE"] {
            if route == "mine" { section = .mine }
            else if route == "settings" { section = .settings }
            else if route.hasPrefix("search:") { path = [.search(String(route.dropFirst(7)))] }
            else if route.hasPrefix("player:") {
                let g = library.grade
                path = [.player(bvid: String(route.dropFirst(7)), context: CourseContext(grade: g, subject: g.map(subject(for:)), volume: g.map { volume(for: $0, subject(for: $0)) }))]
            }
        }
        // BILISTUDY_DEBUG_DAV=地址|账号|密码：启动后登录网盘并同步（测试多端同步用）
        if let dav = env["BILISTUDY_DEBUG_DAV"]?.split(separator: "|").map(String.init), dav.count == 3 {
            let sync = self.sync
            Task { try? await sync.login(DavAccount(url: dav[0], user: dav[1], password: dav[2])) }
        }
        if let b = env["BILISTUDY_DEBUG_FAVORITE"], !library.isFavorite(b) {
            library.toggleFavorite(SavedCourse(bvid: b, title: env["BILISTUDY_DEBUG_FAVORITE_TITLE"] ?? b, cover: "", author: "", duration: 0, episodes: 0,
                                               grade: library.grade, subject: library.grade.map(subject(for:)), volume: nil))
        }
        #endif
    }

    #if DEBUG
    /// 调试时切到横屏（BILISTUDY_DEBUG_LANDSCAPE=1）。
    static func debugRotate() {
        guard ProcessInfo.processInfo.environment["BILISTUDY_DEBUG_LANDSCAPE"] == "1",
              let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
    }
    #endif

    var grade: Int? { library.grade }

    func subject(for grade: Int) -> String {
        let saved = library.pref("subject|\(grade)", String.self)
        let all = Taxonomy.subjects(grade)
        return saved.flatMap { all.contains($0) ? $0 : nil } ?? all[0]
    }

    func volume(for grade: Int, _ subject: String) -> String {
        let saved = library.pref("volume|\(grade)|\(subject)", String.self)
        let all = Taxonomy.volumes(grade, subject)
        return saved.flatMap { all.contains($0) ? $0 : nil } ?? all[0]
    }

    func edition(for subject: String) -> String { library.pref("edition|\(subject)", String.self) ?? "" }

    func choose(grade: Int) {
        library.setPref("grade", grade)
        section = .catalog
        path = []
    }

    func choose(subject: String) {
        guard let g = grade else { return }
        library.setPref("subject|\(g)", subject)
    }

    func choose(volume: String) {
        guard let g = grade else { return }
        library.setPref("volume|\(g)|\(subject(for: g))", volume)
    }

    func choose(edition: String, for subject: String) { library.setPref("edition|\(subject)", edition) }

    /// 从搜索的直达入口打开某个分类。
    func open(grade: Int, subject: String, volume: String?) {
        library.setPref("grade", grade)
        library.setPref("subject|\(grade)", subject)
        if let volume, !volume.isEmpty { library.setPref("volume|\(grade)|\(subject)", volume) }
        section = .catalog
        path = []
    }
}
