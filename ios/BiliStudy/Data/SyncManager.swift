import Foundation
import Observation
import UIKit

/// 多端同步：每台设备只写网盘里自己的文件，读别人的文件后在本机合并，见 docs/sync-format.md。
/// 回到前台时最多每分钟一次、收藏变化后约 3 秒、退出播放页时，以及手动「立即同步」。
@MainActor
@Observable
final class SyncManager {
    private(set) var account: DavAccount?
    private(set) var lastSyncAt: Date?
    private(set) var error: String?
    private(set) var running = false
    private(set) var devices: [String] = []

    @ObservationIgnored private let library: LibraryStore
    @ObservationIgnored private var folderReady = false
    @ObservationIgnored private var remoteCache: [String: (etag: String, doc: SyncDocument)] = [:]
    @ObservationIgnored private var lastUploaded: Data?
    @ObservationIgnored private var pending: Task<Void, Never>?

    let deviceID: String = {
        if let id = UserDefaults.standard.string(forKey: "sync.device") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "sync.device")
        return id
    }()

    init(library: LibraryStore) {
        self.library = library
        if let json = Keychain.get("sync.account"), let a = try? JSONDecoder().decode(DavAccount.self, from: Data(json.utf8)) { account = a }
        if let t = UserDefaults.standard.object(forKey: "sync.lastAt") as? Date { lastSyncAt = t }
        library.onChange = { [weak self] urgent in
            if urgent { self?.schedule(after: 3) }
        }
    }

    var statusText: String {
        guard let account else { return "未登录，学习记录只保存在这台设备上" }
        let host = URL(string: account.url)?.host ?? account.url
        if running { return "正在同步 \(host)" }
        if let error { return "同步失败：\(error)" }
        guard let lastSyncAt else { return "已登录 \(host)，还没有同步过" }
        return "已登录 \(host)，上次同步 \(Self.timeText(lastSyncAt))"
    }

    static func timeText(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = Calendar.current.isDateInToday(d) ? "今天 HH:mm" : "M 月 d 日 HH:mm"
        return f.string(from: d)
    }

    /// 保存账号前先实际访问一次网盘，账号或密码不对直接报错。
    func login(_ a: DavAccount) async throws {
        let dav = WebDAV(account: a)
        try await dav.ensureFolder()
        _ = try await dav.list()
        Keychain.set("sync.account", String(data: try JSONEncoder().encode(a), encoding: .utf8))
        account = a
        folderReady = true
        remoteCache = [:]
        lastUploaded = nil
        error = nil
        await syncNow()
    }

    func logout() {
        Keychain.set("sync.account", nil)
        account = nil
        error = nil
        remoteCache = [:]
        lastUploaded = nil
        folderReady = false
    }

    func schedule(after seconds: Double) {
        guard account != nil else { return }
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
            guard !Task.isCancelled else { return }
            await self?.syncNow()
        }
    }

    /// 回到前台时调用：距上次同步不到一分钟就跳过。
    func syncIfStale() {
        if let lastSyncAt, Date().timeIntervalSince(lastSyncAt) < 60 { return }
        schedule(after: 0.5)
    }

    func syncNow() async {
        guard let account, !running else { return }
        running = true
        defer { running = false }
        do {
            let dav = WebDAV(account: account)
            if !folderReady { try await dav.ensureFolder(); folderReady = true }
            let own = "\(deviceID).json"
            var docs: [SyncDocument] = []
            var names: [String] = []
            for file in try await dav.list() where file.name != own {
                if let cached = remoteCache[file.name], !file.etag.isEmpty, cached.etag == file.etag {
                    docs.append(cached.doc)
                } else {
                    let (data, etag) = try await dav.get(file.name)
                    guard let doc = try? JSONDecoder().decode(SyncDocument.self, from: data) else { continue }
                    remoteCache[file.name] = (etag.isEmpty ? file.etag : etag, doc)
                    docs.append(doc)
                }
                if let d = docs.last, !d.deviceName.isEmpty { names.append(d.deviceName) }
            }
            // 本机的集合和别人的一起合并；不认识的集合也合并后原样保留
            let now = nowMs()
            let local = library.sets
            var allNames = Set(local.keys)
            docs.forEach { allNames.formUnion($0.sets.keys) }
            var merged: [String: SyncSet] = [:]
            for name in allNames {
                var m = SyncSet.merge([local[name]].compactMap { $0 } + docs.compactMap { $0.sets[name] }, now: now)
                if name == "history" { m.trim(to: 100) }
                if name == "progress" || name == "watched" { m.trim(to: 3000) }
                merged[name] = m
            }
            library.replace(with: merged)
            let doc = SyncDocument(device: deviceID, deviceName: UIDevice.current.name, savedAt: now, sets: library.sets)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            // 内容（不含保存时间）和上次上传的一样就不再上传
            var comparable = doc
            comparable.savedAt = 0
            let fingerprint = try encoder.encode(comparable)
            if fingerprint != lastUploaded {
                try await dav.put(own, try encoder.encode(doc))
                lastUploaded = fingerprint
            }
            devices = names
            error = nil
            lastSyncAt = Date()
            UserDefaults.standard.set(lastSyncAt, forKey: "sync.lastAt")
        } catch {
            self.error = error.localizedDescription
        }
    }
}
