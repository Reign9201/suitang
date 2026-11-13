import Foundation

/// 年级、学科、册次目录。和 Python 原型的 classify.py 保持一致，改动时两边一起改。
enum Taxonomy {
    static let cn: [Character] = Array("一二三四五六七八九")

    static let stages: [(name: String, grades: [Int])] = [
        ("小学", Array(1...6)),
        ("初中", Array(7...9)),
        ("高中", Array(10...12)),
    ]

    static func gradeName(_ n: Int) -> String {
        n <= 9 ? "\(cn[n - 1])年级" : "高\(cn[n - 10])"
    }

    static func gradeShort(_ n: Int) -> String {
        n <= 9 ? String(cn[n - 1]) : "高\(cn[n - 10])"
    }

    static func stage(_ n: Int) -> String {
        n <= 6 ? "小学" : (n <= 9 ? "初中" : "高中")
    }

    static func subjects(_ n: Int) -> [String] {
        switch n {
        case ...2: return ["语文", "数学", "道德与法治", "科学"]
        case ...6: return ["语文", "数学", "英语", "科学", "道德与法治"]
        case 7: return ["语文", "数学", "英语", "生物", "地理", "历史", "道德与法治"]
        case 8: return ["语文", "数学", "英语", "物理", "生物", "地理", "历史", "道德与法治"]
        case 9: return ["语文", "数学", "英语", "物理", "化学", "历史", "道德与法治"]
        default: return ["语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "政治"]
        }
    }

    static func volumes(_ n: Int, _ subject: String) -> [String] {
        if n == 9 && subject == "物理" { return ["全一册"] }
        if n <= 9 { return ["上册", "下册"] }
        if n == 10 { return ["必修一", "必修二"] }
        if n == 11 { return ["选择性必修一", "选择性必修二"] }
        return ["一轮复习", "二轮复习"]
    }

    /// 书脊上的小字，例如「七上」「选必一」。
    static func volumeShort(_ n: Int, _ volume: String) -> String {
        if n <= 9 && (volume == "上册" || volume == "下册") { return "\(cn[n - 1])\(volume.prefix(1))" }
        if volume.hasPrefix("选择性必修") { return "选必" + String(volume.suffix(1)) }
        if volume.hasSuffix("轮复习") { return String(volume.prefix(2)) }
        return volume
    }

    private static func volumeQueryText(_ subject: String, _ volume: String) -> String {
        guard subject == "语文" else { return volume }
        return ["必修一": "必修上册", "必修二": "必修下册",
                "选择性必修一": "选择性必修上册", "选择性必修二": "选择性必修中册"][volume] ?? volume
    }

    /// 每个分类用两个搜索词，见 classify.make_queries。
    static func queries(_ n: Int, _ subject: String, _ volume: String) -> [String] {
        let v = volumeQueryText(subject, volume)
        if n <= 6 { return ["\(gradeName(n))\(subject)\(v)", "\(gradeName(n))\(subject)\(v) 全集"] }
        if n <= 9 { return ["\(gradeName(n))\(subject)\(v)", "初\(cn[n - 7])\(subject)\(v)"] }
        if n <= 11 { return ["高中\(subject)\(v)", "\(gradeShort(n))\(subject)\(v)"] }
        return ["高考\(subject)\(v)", "高三\(subject)\(v)"]
    }

    static let allSubjects = ["语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "道德与法治", "政治", "科学"]
}
