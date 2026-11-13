import Foundation

/// 任意 JSON 值，用来原样保存同步集合里各功能自己的数据。
enum JSONValue: Codable, Hashable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): if n.rounded() == n && abs(n) < 1e15 { try c.encode(Int64(n)) } else { try c.encode(n) }
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    /// 把任意 Codable 转成 JSONValue，或从 JSONValue 读回。
    static func from<T: Encodable>(_ value: T) -> JSONValue {
        (try? JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))) ?? .null
    }

    func decode<T: Decodable>(_ type: T.Type) -> T? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

struct SyncItem: Codable, Hashable {
    var at: Int64
    var data: JSONValue
}

/// 一个同步集合：键 → 最后一次修改；删除记成墓碑，见 docs/sync-format.md。
struct SyncSet: Codable, Hashable {
    var items: [String: SyncItem] = [:]
    var removals: [String: Int64] = [:]

    init(items: [String: SyncItem] = [:], removals: [String: Int64] = [:]) {
        self.items = items
        self.removals = removals
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? c.decode([String: SyncItem].self, forKey: .items)) ?? [:]
        removals = (try? c.decode([String: Int64].self, forKey: .removals)) ?? [:]
    }

    static let removalMemoryMs: Int64 = 180 * 24 * 3_600_000

    /// 每个键各自按时间决定，和读文件的顺序无关；删除时间不早于最新修改时删掉并保留墓碑；墓碑 180 天后忘掉。
    static func merge(_ sets: [SyncSet], now: Int64) -> SyncSet {
        var items: [String: SyncItem] = [:]
        var removed: [String: Int64] = [:]
        for set in sets {
            for (key, item) in set.items where (items[key]?.at ?? .min) < item.at { items[key] = item }
            for (key, at) in set.removals where (removed[key] ?? .min) < at { removed[key] = at }
        }
        var out = SyncSet()
        for (key, item) in items where item.at > (removed[key] ?? .min) { out.items[key] = item }
        for (key, at) in removed where now - at < removalMemoryMs && out.items[key] == nil { out.removals[key] = at }
        return out
    }

    /// 只留最新的 limit 条（丢掉的不记删除）。
    mutating func trim(to limit: Int) {
        guard items.count > limit else { return }
        let keep = items.sorted { $0.value.at > $1.value.at }.prefix(limit)
        items = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
    }
}

/// 一台设备在网盘里的文件 bilistudy/<设备 ID>.json。
struct SyncDocument: Codable {
    var version = 1
    var device: String
    var deviceName: String
    var platform = "ios"
    var savedAt: Int64
    var sets: [String: SyncSet]

    init(device: String, deviceName: String, savedAt: Int64, sets: [String: SyncSet]) {
        self.device = device
        self.deviceName = deviceName
        self.savedAt = savedAt
        self.sets = sets
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(Int.self, forKey: .version)) ?? 1
        device = (try? c.decode(String.self, forKey: .device)) ?? ""
        deviceName = (try? c.decode(String.self, forKey: .deviceName)) ?? ""
        platform = (try? c.decode(String.self, forKey: .platform)) ?? ""
        savedAt = (try? c.decode(Int64.self, forKey: .savedAt)) ?? 0
        sets = (try? c.decode([String: SyncSet].self, forKey: .sets)) ?? [:]
    }
}

/// 收藏时的课程快照，离线也能显示。
struct SavedCourse: Codable, Hashable, Identifiable {
    var bvid: String
    var title: String
    var cover: String
    var author: String
    var duration: Int
    var episodes: Int
    var grade: Int?
    var subject: String?
    var volume: String?
    var id: String { bvid }
}

/// 「最近在学」里的一门课：上次看到的那一节。
struct HistoryEntry: Codable, Hashable, Identifiable {
    var bvid: String
    var title: String
    var cover: String
    var author: String
    var lesson: String
    var index: Int
    var part: String
    var total: Int
    var grade: Int?
    var subject: String?
    var volume: String?
    var id: String { bvid }
}

struct LessonProgress: Codable, Hashable {
    var position: Double
    var duration: Double
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
