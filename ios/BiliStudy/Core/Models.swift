import Foundation

/// 大模型对一条视频的判断，字段和 ai.py 的 ORGANIZE_SCHEMA 一致。
struct AIJudgement: Codable, Hashable {
    var fit: String
    var kind: String
    var level: String
    var edition: String
    var summary: String
    var quality: Int
    var comment: String
}

/// 一门课（搜索结果里的一条视频）。
struct Course: Codable, Identifiable, Hashable {
    var bvid: String
    var title: String
    var author: String
    var cover: String
    var duration: Int
    var play: Int
    var favorites: Int
    var pubdate: Int
    var typeid: Int
    var episodes: Int
    var desc: String
    var tagtext: String
    var editions: [String] = []
    var volumes: [String] = []
    var grades: [Int] = []
    var subjects: [String] = []
    var series = false
    var review = false
    var newTextbook = false
    var score: Double = 0
    var ai: AIJudgement?

    var id: String { bvid }

    /// B 站图片服务按尺寸裁剪，列表里不需要原图。
    var thumbURL: URL? {
        URL(string: cover.contains("hdslb.com") ? cover + "@336w_189h_1c.jpg" : cover)
    }
}

struct Rejection: Codable, Hashable {
    var reason: String
    var count: Int
    var samples: [String]
}

/// 某个分类（年级、学科、册次）整理好的结果。
struct Catalog: Codable {
    var grade: Int
    var subject: String
    var volume: String
    var queries: [String]
    var seen: Int
    var items: [Course]
    var rejected: [Rejection]
    var builtAt: Date
    var picks: [String] = []
}

/// 视频详情里的一节：分 P 视频用 p 区分，B 站「合集」里每一节是独立的 BV 号。
struct Lesson: Codable, Hashable, Identifiable {
    var key: String
    var bvid: String
    var cid: Int
    var page: Int
    var part: String
    var duration: Int
    var id: String { key }
}

struct CourseDetail: Codable {
    var bvid: String
    var title: String
    var author: String
    var cover: String
    var duration: Int
    var play: Int
    var lessons: [Lesson]
    var seasonTitle: String?

    var displayTitle: String { seasonTitle ?? title }
}

struct PlaySource {
    var url: URL
    var quality: String
    var headers: [String: String]
}

/// 一句话搜索解析出的条件。
struct SearchIntent: Codable, Hashable {
    var grade: Int
    var subject: String
    var volume: String
    var topic: String
    var query: String
}

/// 打开一门课时带着的来源，用来返回和记录「最近在学」属于哪个分类。
struct CourseContext: Codable, Hashable {
    var grade: Int?
    var subject: String?
    var volume: String?
}

enum Formatter {
    static func duration(_ seconds: Int) -> String {
        let s = max(0, seconds)
        let h = s / 3600, m = s % 3600 / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    static func hours(_ seconds: Int) -> String {
        seconds >= 3600 ? String(format: "%.1f 小时", Double(seconds) / 3600) : "\(max(1, seconds / 60)) 分钟"
    }

    static func count(_ n: Int) -> String {
        if n >= 100_000_000 { return trim(String(format: "%.1f", Double(n) / 1e8)) + " 亿" }
        if n >= 10_000 { return trim(String(format: "%.1f", Double(n) / 1e4)) + " 万" }
        return "\(n)"
    }

    private static func trim(_ s: String) -> String { s.hasSuffix(".0") ? String(s.dropLast(2)) : s }
}
