import Foundation
import Observation

/// 本机的学习数据：收藏、最近在学、每节进度、看完的节、年级和版本选择。
/// 直接用同步集合的结构保存，同步时整份交给 SyncManager 合并。
@MainActor
@Observable
final class LibraryStore {
    static let names = ["favorites", "history", "progress", "watched", "prefs"]

    private(set) var sets: [String: SyncSet]
    /// 收藏或清除有变化时调用（SyncManager 用它安排 3 秒后同步）。
    @ObservationIgnored var onChange: ((_ urgent: Bool) -> Void)?

    private let file: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("library.json")
    }()

    init() {
        if let data = try? Data(contentsOf: file), let s = try? JSONDecoder().decode([String: SyncSet].self, from: data) { sets = s }
        else { sets = [:] }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(sets) { try? data.write(to: file, options: .atomic) }
    }

    private func update(_ name: String, urgent: Bool = false, _ change: (inout SyncSet) -> Void) {
        var set = sets[name] ?? SyncSet()
        change(&set)
        switch name {
        case "history": set.trim(to: 100)
        case "progress", "watched": set.trim(to: 3000)
        default: break
        }
        sets[name] = set
        save()
        onChange?(urgent)
    }

    /// 同步后整份替换；返回是否有变化。
    @discardableResult
    func replace(with merged: [String: SyncSet]) -> Bool {
        var next = sets
        for (name, set) in merged { next[name] = set }
        guard next != sets else { return false }
        sets = next
        save()
        return true
    }

    // MARK: 收藏

    var favorites: [SavedCourse] {
        (sets["favorites"]?.items ?? [:]).sorted { $0.value.at > $1.value.at }.compactMap { $0.value.data.decode(SavedCourse.self) }
    }

    func isFavorite(_ bvid: String) -> Bool { sets["favorites"]?.items[bvid] != nil }

    func toggleFavorite(_ course: SavedCourse) {
        let now = nowMs()
        update("favorites", urgent: true) { set in
            if set.items[course.bvid] != nil {
                set.items[course.bvid] = nil
                set.removals[course.bvid] = now
            } else {
                set.items[course.bvid] = SyncItem(at: now, data: .from(course))
                set.removals[course.bvid] = nil
            }
        }
    }

    // MARK: 最近在学与进度

    var history: [HistoryEntry] {
        (sets["history"]?.items ?? [:]).sorted { $0.value.at > $1.value.at }.compactMap { $0.value.data.decode(HistoryEntry.self) }
    }

    func historyEntry(_ bvid: String) -> HistoryEntry? { sets["history"]?.items[bvid]?.data.decode(HistoryEntry.self) }

    func record(_ entry: HistoryEntry) {
        update("history") { $0.items[entry.bvid] = SyncItem(at: nowMs(), data: .from(entry)) }
    }

    func progress(_ bvid: String, _ lesson: String) -> LessonProgress? {
        sets["progress"]?.items["\(bvid)|\(lesson)"]?.data.decode(LessonProgress.self)
    }

    func setProgress(_ bvid: String, _ lesson: String, position: Double, duration: Double) {
        update("progress") { $0.items["\(bvid)|\(lesson)"] = SyncItem(at: nowMs(), data: .from(LessonProgress(position: position, duration: duration))) }
    }

    func isWatched(_ bvid: String, _ lesson: String) -> Bool { sets["watched"]?.items["\(bvid)|\(lesson)"] != nil }

    func markWatched(_ bvid: String, _ lesson: String) {
        guard !isWatched(bvid, lesson) else { return }
        update("watched") { $0.items["\(bvid)|\(lesson)"] = SyncItem(at: nowMs(), data: .bool(true)) }
    }

    func watchedCount(_ bvid: String) -> Int {
        (sets["watched"]?.items ?? [:]).keys.filter { $0.hasPrefix(bvid + "|") }.count
    }

    // MARK: 选择

    func pref<T: Decodable>(_ key: String, _ type: T.Type) -> T? { sets["prefs"]?.items[key]?.data.decode(T.self) }

    func setPref<T: Codable & Equatable>(_ key: String, _ value: T) {
        guard pref(key, T.self) != value else { return }
        update("prefs") { $0.items[key] = SyncItem(at: nowMs(), data: .from(value)) }
    }

    var grade: Int? { pref("grade", Int.self) }
}
