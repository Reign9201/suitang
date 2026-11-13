import Foundation
import Observation

enum AIStatus: Equatable {
    case off(String)
    case pending
    case ready(String)
    case failed(String)
}

/// 整理分类：先用规则立刻给出结果，再在后台让大模型整理，好了以后替换。
/// 规则结果和大模型结果都缓存在本机 24 小时，和原型 server.py 的 catalog / ensure_ai 一致。
@MainActor
@Observable
final class CatalogStore {
    private(set) var catalogs: [String: Catalog] = [:]
    private(set) var errors: [String: String] = [:]
    private(set) var aiStatus: [String: AIStatus] = [:]
    @ObservationIgnored private var loading: Set<String> = []

    static func key(_ g: Int, _ s: String, _ v: String) -> String { "\(g)|\(s)|\(v)" }

    private let cacheDir: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("catalog")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private func file(_ name: String) -> URL {
        cacheDir.appendingPathComponent(name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? name)
    }

    private func readCache<T: Decodable>(_ name: String, maxAge: TimeInterval) -> T? {
        let url = file(name)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attrs[.modificationDate] as? Date, Date().timeIntervalSince(modified) < maxAge,
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func writeCache<T: Encodable>(_ name: String, _ value: T) {
        if let data = try? JSONEncoder().encode(value) { try? data.write(to: file(name), options: .atomic) }
    }

    func load(grade: Int, subject: String, volume: String, refresh: Bool = false) {
        let key = Self.key(grade, subject, volume)
        if !refresh, catalogs[key] != nil { enrich(key); return }
        guard !loading.contains(key) else { return }
        loading.insert(key)
        errors[key] = nil
        Task {
            defer { loading.remove(key) }
            do {
                var catalog: Catalog
                if !refresh, let cached: Catalog = readCache("rules-" + key, maxAge: 24 * 3600) { catalog = cached }
                else {
                    catalog = try await Self.build(grade: grade, subject: subject, volume: volume)
                    writeCache("rules-" + key, catalog)
                }
                catalogs[key] = catalog
                enrich(key)
            } catch {
                errors[key] = error.localizedDescription
            }
        }
    }

    nonisolated static func build(grade n: Int, subject: String, volume: String) async throws -> Catalog {
        let queries = Taxonomy.queries(n, subject, volume)
        var seen = Set<String>()
        var kept: [Course] = []
        var rejected: [String: (Int, [String])] = [:]
        var order: [String] = []
        func reject(_ reason: String, _ sample: String?) {
            if rejected[reason] == nil { order.append(reason); rejected[reason] = (0, []) }
            rejected[reason]!.0 += 1
            if let sample, rejected[reason]!.1.count < 3 { rejected[reason]!.1.append(String(sample.prefix(60))) }
        }
        for q in queries {
            let data = try await BiliClient.shared.search(q)
            for row in data["result"] as? [[String: Any]] ?? [] {
                let (item, reason) = Classify.baseItem(row)
                let id = item?.bvid ?? UUID().uuidString
                guard seen.insert(id).inserted else { continue }
                guard let item else { reject(reason ?? "无法播放", nil); continue }
                if let r = Classify.reject(item, grade: n, subject: subject, volume: volume) { reject(r, item.title); continue }
                kept.append(Classify.finish(item, grade: n, subject: subject, volume: volume))
            }
        }
        kept.sort { $0.score > $1.score }
        let rej = order.map { Rejection(reason: $0, count: rejected[$0]!.0, samples: rejected[$0]!.1) }.sorted { $0.count > $1.count }
        return Catalog(grade: n, subject: subject, volume: volume, queries: queries, seen: seen.count,
                       items: kept, rejected: rej, builtAt: Date())
    }

    /// 后台大模型整理。模型名在缓存键里：换了模型会重新整理。
    private func enrich(_ key: String) {
        guard var catalog = catalogs[key] else { return }
        let config = AIConfig.load()
        if let reason = config.unavailableReason { aiStatus[key] = .off(reason); return }
        if catalog.items.contains(where: { $0.ai != nil }) { return }
        if case .pending = aiStatus[key] { return }
        let model = config.organizeModel
        let ids = catalog.items.map(\.bvid).sorted().joined(separator: ",")
        let cacheName = "ai\(Prompts.organizeVersion)-\(model)-\(key)-\(Self.stableHash(ids))"
        if let judged: [String: AIJudgement] = readCache(cacheName, maxAge: 24 * 3600) {
            Classify.applyAI(&catalog, judged: judged)
            catalogs[key] = catalog
            aiStatus[key] = .ready(model)
            return
        }
        guard !catalog.items.isEmpty else { aiStatus[key] = .ready(model); return }
        aiStatus[key] = .pending
        let items = catalog.items
        Task {
            do {
                let judged = try await AIClient(config: config).organize(
                    gradeName: Taxonomy.gradeName(catalog.grade), stage: Taxonomy.stage(catalog.grade),
                    subject: catalog.subject, volume: catalog.volume, items: items, model: model)
                writeCache(cacheName, judged)
                guard var latest = catalogs[key] else { return }
                Classify.applyAI(&latest, judged: judged)
                catalogs[key] = latest
                aiStatus[key] = .ready(model)
            } catch {
                aiStatus[key] = .failed(error.localizedDescription)
            }
        }
    }

    /// String.hashValue 每次启动都不同，缓存键要用固定的哈希（FNV-1a）。
    nonisolated static func stableHash(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return String(h, radix: 16)
    }

    /// 改了大模型设置后，让失败或关闭状态的分类重新整理。
    func resetAI() {
        for (key, status) in aiStatus {
            if case .ready = status { continue }
            aiStatus[key] = nil
            enrich(key)
        }
    }
}
