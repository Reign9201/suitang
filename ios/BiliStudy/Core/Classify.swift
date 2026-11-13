import Foundation

/// 从标题里认出年级、学科、版本、册次，并判断一条视频属不属于某个分类。
/// 逐条移植自 Python 原型的 classify.py，改规则时两边一起改，并跑 ClassifyTests。
enum Classify {
    static let studyTid = 208
    /// 义务教育阶段从 2024 年秋季开始逐年换新教材，这之后发布的视频优先。
    static let newTextbookSince = 1_719_763_200 // 2024-07-01 00:00 +08:00

    private static let digit: [Character: Int] = {
        var d: [Character: Int] = [:]
        for (i, c) in Taxonomy.cn.enumerated() { d[c] = i + 1 }
        for i in 1...9 { d[Character(String(i))] = i }
        return d
    }()

    // MARK: 正则工具

    private static var cache: [String: NSRegularExpression] = [:]
    private static let cacheLock = NSLock()

    private static func regex(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        cacheLock.lock(); defer { cacheLock.unlock() }
        let key = pattern + "|\(options.rawValue)"
        if let r = cache[key] { return r }
        let r = try! NSRegularExpression(pattern: pattern, options: options)
        cache[key] = r
        return r
    }

    /// 每个匹配的各个捕获组。
    private static func groups(_ pattern: String, _ text: String) -> [[String]] {
        let ns = text as NSString
        return regex(pattern).matches(in: text, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { i in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
        }
    }

    static func has(_ pattern: String, _ text: String, caseInsensitive: Bool = false) -> Bool {
        regex(pattern, caseInsensitive ? [.caseInsensitive] : []).firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    private static func num(_ s: String) -> Int { s.first.flatMap { digit[$0] } ?? 0 }

    // MARK: 年级

    private static let range = #"\s*[到至~\-—－]\s*"#
    private static let shortTail = #"(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|道德|科学|$|[】\]｜|，,）)])"#

    /// 返回（明确提到的年级，只能推断的年级）。明确年级存在时只用它，避免「七年级……直击中考」被归进九年级。
    static func grades(_ text: String) -> (strong: Set<Int>, weak: Set<Int>) {
        var strong = Set<Int>()
        for g in groups("([一二三四五六七八九1-9])" + range + #"([一二三四五六七八九1-9])\s*年级"#, text) {
            let a = num(g[1]), b = num(g[2])
            if a > 0, b > 0 { strong.formUnion(min(a, b)...max(a, b)) }
        }
        for g in groups(#"([一二三四五六七八九1-9])\s*年级"#, text) { strong.insert(num(g[1])) }
        for g in groups("初([一二三])" + range + "初?([一二三])", text) {
            let a = num(g[1]), b = num(g[2])
            strong.formUnion((6 + min(a, b))...(6 + max(a, b)))
        }
        for g in groups("初([一二三])(?![级中])", text) { strong.insert(6 + num(g[1])) }
        for g in groups("高([一二三])" + range + "高?([一二三])", text) {
            let a = num(g[1]), b = num(g[2])
            strong.formUnion((9 + min(a, b))...(9 + max(a, b)))
        }
        for g in groups("(?<![提最升])高([一二三])(?![级点倍])", text) { strong.insert(9 + num(g[1])) }
        for g in groups("(?<![第每其之初高一二三四五六七八九十])([一二三四五六七八九])([上下])" + shortTail, text) {
            strong.insert(num(g[1]))
        }
        strong.remove(0)
        var weak = Set<Int>()
        if text.contains("小升初") { weak.insert(6) }
        if text.contains("中考") { weak.insert(9) }
        if text.contains("高考") { weak.insert(12) }
        if text.contains("选择性必修") { weak.insert(11) } else if text.contains("必修") { weak.insert(10) }
        return (strong, weak)
    }

    // MARK: 学科

    private static let subjectPatterns: [(String, String)] = [
        ("语文", "语文|文言文|古诗文"),
        ("数学", "数学|奥数"),
        ("英语", "英语|English|新概念"),
        ("物理", "物理"),
        ("化学", "化学"),
        ("生物", "生物"),
        ("历史", "历史"),
        ("地理", "地理"),
        ("道德与法治", "道德与法治|道法|思想品德|思想政治|政治|思政"),
        ("科学", "科学(?!普)"),
    ]

    /// 初中叫道德与法治，高中叫政治，比对时视为同一科。
    static func canonical(_ subject: String) -> String { subject == "政治" ? "道德与法治" : subject }

    static func subjects(_ text: String) -> Set<String> {
        Set(subjectPatterns.filter { has($0.1, text, caseInsensitive: true) }.map(\.0))
    }

    // MARK: 版本

    private static let editionPatterns: [(String, String)] = [
        ("人教版", "人教[AB]?版|新人教|人教"), ("统编版", "统编|部编"), ("北师大版", "北师"), ("苏教版", "苏教"),
        ("沪教版", "沪教"), ("外研版", "外研"), ("译林版", "译林"), ("青岛版", "青岛版"), ("浙教版", "浙教"),
        ("湘教版", "湘教"), ("冀教版", "冀教"), ("鲁教版", "鲁教"), ("沪科版", "沪科"), ("华师大版", "华师"),
        ("北京版", "北京版"), ("教科版", "教科版"), ("粤教版", "粤教"), ("科粤版", "科粤"), ("仁爱版", "仁爱"),
        ("牛津版", "牛津"),
    ]
    /// 这三科全国统一用统编版，人教社出版，很多 UP 主写成「人教版」。
    private static let unified: Set<String> = ["语文", "历史", "道德与法治"]

    static func editions(_ text: String, subject: String?) -> [String] {
        var found: [String] = []
        for (name, pattern) in editionPatterns where has(pattern, text) && !found.contains(name) { found.append(name) }
        if let s = subject, unified.contains(canonical(s)) {
            var mapped: [String] = []
            for e in found.map({ $0 == "人教版" ? "统编版" : $0 }) where !mapped.contains(e) { mapped.append(e) }
            found = mapped
        }
        return found
    }

    // MARK: 册次

    static func volumes(_ text: String) -> Set<String> {
        var found = Set<String>()
        for g in groups(#"选择性必修\s*(?:第)?([一二三123上中下])"#, text) {
            let m = ["上": "一", "中": "二", "下": "三"][g[1]] ?? String(Taxonomy.cn[max(1, num(g[1])) - 1])
            found.insert("选择性必修" + m)
        }
        for g in groups(#"(?<!选择性)必修\s*(?:第)?([一二三123上下])"#, text) {
            let m = ["上": "一", "下": "二"][g[1]] ?? String(Taxonomy.cn[max(1, num(g[1])) - 1])
            found.insert("必修" + m)
        }
        if text.contains("全一册") { found.insert("全一册") }
        if has("上下(?:全)?册|上[/、和及]下册", text) { found.formUnion(["上册", "下册"]) }
        let tail = #"(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|科学|$|[】\]｜，,）)])"#
        if has("(?<!必修)(?<!选择性必修)上册|上学期|[一二三四五六七八九]上" + tail, text) { found.insert("上册") }
        if has("(?<!必修)(?<!选择性必修)下册|下学期|[一二三四五六七八九]下" + tail, text) { found.insert("下册") }
        if text.contains("一轮") { found.insert("一轮复习") }
        if text.contains("二轮") { found.insert("二轮复习") }
        return found
    }

    static func episodes(_ text: String) -> Int {
        for pattern in [#"(?:全|共)\s*(\d{1,4})\s*[集讲节课期P]"#, #"(\d{1,4})\s*集全"#,
                        #"(?<![第\d.])(\d{1,4})\s*(?:集|讲|节课?|[Pp](?![a-zA-Z]))"#] {
            if let g = groups(pattern, text).first, let n = Int(g[1]) { return n }
        }
        return 0
    }

    static func isReview(_ text: String) -> Bool { has("复习|期中|期末|冲刺|真题|押题|备考|考前|速通|速成|刷完|总结|压轴", text) }

    /// 搜索结果的时长是「分:秒」，分可能超过 60，例如 1239:55。
    static func duration(_ text: String) -> Int {
        text.split(separator: ":").compactMap { Int($0) }.reduce(0) { $0 * 60 + $1 }
    }

    // MARK: 文本

    static func clean(_ raw: String?) -> String {
        var text = (raw ?? "").replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (a, b) in [("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"), ("&nbsp;", " ")] {
            text = text.replacingOccurrences(of: a, with: b)
        }
        text = String(String.UnicodeScalarView(text.unicodeScalars.filter { s in
            !(0x1F000...0x1FAFF).contains(s.value) && !(0x2600...0x27BF).contains(s.value)
                && !(0x2B00...0x2BFF).contains(s.value) && s.value != 0xFE0F
        }))
        return text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func https(_ url: String) -> String {
        if url.hasPrefix("//") { return "https:" + url }
        if url.hasPrefix("http://") { return "https://" + url.dropFirst(7) }
        return url
    }

    // MARK: 一条搜索结果

    /// 搜索结果里与分类无关的字段。返回 nil 和原因表示这条没法在应用里播放。
    static func baseItem(_ row: [String: Any]) -> (Course?, String?) {
        let bvid = (row["bvid"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        guard has("^BV[0-9A-Za-z]{10}$", bvid) else { return (nil, "无法播放") }
        if truthy(row["is_pay"]) || truthy(row["is_charge_video"]) || truthy(row["badgepay"]) { return (nil, "付费或充电专属") }
        let title = clean(row["title"] as? String)
        return (Course(
            bvid: bvid, title: title, author: clean(row["author"] as? String),
            cover: https((row["pic"] as? String ?? "").trimmingCharacters(in: .whitespaces)),
            duration: duration(row["duration"].map { "\($0)" } ?? ""),
            play: int(row["play"]), favorites: int(row["favorites"]), pubdate: int(row["pubdate"]),
            typeid: int(row["typeid"]), episodes: episodes(title),
            desc: String(clean(row["description"] as? String).prefix(160)), tagtext: clean(row["tag"] as? String)
        ), nil)
    }

    private static func truthy(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return !s.isEmpty && s != "0" }
        return false
    }

    static func int(_ v: Any?) -> Int {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) ?? 0 }
        return 0
    }

    /// 给任意一条视频认出年级、学科、版本、册次，用于搜索结果的标签。
    static func describe(_ c: Course, subject: String? = nil) -> (Set<Int>, Set<String>, [String], Set<String>) {
        let (strong, weak) = grades(c.title)
        let g = !strong.isEmpty ? strong : (!weak.isEmpty ? weak : grades(c.tagtext).strong)
        let s1 = subjects(c.title)
        let subs = s1.isEmpty ? subjects(c.tagtext) : s1
        let subj = subject ?? (subs.count == 1 ? subs.first : nil)
        let e1 = editions(c.title, subject: subj)
        let eds = e1.isEmpty ? Array(editions(c.tagtext, subject: subj).prefix(1)) : e1
        return (g, subs, eds, volumes(c.title))
    }

    /// 判断一条视频是否属于（年级 n、学科、册次）。返回 nil 表示收录，否则是拒绝原因。
    /// 规则按「宁缺毋滥」：标题里写明的年级、学科、册次和目标不一致就不收；没写的不扣分但排序靠后。
    static func reject(_ c: Course, grade n: Int, subject: String, volume: String) -> String? {
        if c.typeid != studyTid { return "不在学习分区" }
        if c.author.isEmpty || c.author == "账号已注销" { return "账号已注销" }
        if c.duration > 0 && c.duration < 180 { return "短于 3 分钟" }
        let (strong, weak) = grades(c.title)
        let g = strong.isEmpty ? weak : strong
        if !g.isEmpty && !g.contains(n) { return "年级不符" }
        let subs = Set(subjects(c.title).map(canonical))
        if !subs.isEmpty && !subs.contains(canonical(subject)) { return "学科不符" }
        let vols = volumes(c.title)
        if !vols.isEmpty && !vols.contains(volume) { return "册次不符" }
        if g.isEmpty {
            let tagGrades = grades(c.tagtext).strong
            if !tagGrades.isEmpty && !tagGrades.contains(n) { return "年级不符" }
        }
        return nil
    }

    /// 补齐派生字段并算排序分数（volume 为 nil 时不算分，用于搜索结果）。
    static func finish(_ c: Course, grade n: Int, subject: String?, volume: String?) -> Course {
        var c = c
        let (g, subs, eds, vols) = describe(c, subject: subject)
        c.editions = eds
        c.volumes = vols.sorted()
        c.grades = g.sorted()
        c.subjects = subs.sorted()
        c.series = c.episodes >= 4 || c.duration >= 3600
        c.review = isReview(c.title)
        c.newTextbook = n > 0 && n <= 9 && (c.pubdate >= newTextbookSince || has("新教材|新版|202[4-9]", c.title))
        if let v = volume { c.score = score(c, grade: n, volume: v) }
        return c
    }

    static func score(_ c: Course, grade n: Int, volume: String) -> Double {
        var s = log10(Double(c.play) + 1) + 1.5 * log10(Double(c.favorites) + 1)
        if grades(c.title).strong.contains(n) { s += 0.6 }
        if volumes(c.title).contains(volume) { s += 1.0 }
        if c.play > 0 && Double(c.favorites) / Double(c.play) > 0.02 { s += 0.5 } // 收藏率高的通常是真正拿来学的课
        if n <= 9 && c.newTextbook { s += 0.8 }
        if c.series { s += 0.5 }
        return (s * 1000).rounded() / 1000
    }

    // MARK: 大模型结果合并（和 server.py 的 apply_ai 一致）

    static func applyAI(_ catalog: inout Catalog, judged: [String: AIJudgement]) {
        var kept: [Course] = []
        var dropped: [Course] = []
        for var item in catalog.items {
            guard let j = judged[item.bvid] else { kept.append(item); continue }
            if j.fit == "不对口" { dropped.append(item); continue }
            item.ai = j
            if !j.edition.isEmpty && item.editions.isEmpty {
                item.editions = Array(editions(j.edition, subject: catalog.subject).prefix(1))
            }
            item.score = ((item.score + Double(j.quality - 3) * 1.5 - (j.fit == "相关" ? 2 : 0)) * 1000).rounded() / 1000
            kept.append(item)
        }
        kept.sort { $0.score > $1.score }
        catalog.items = kept
        if !dropped.isEmpty {
            catalog.rejected.insert(Rejection(reason: "大模型判断不对口", count: dropped.count,
                                              samples: dropped.prefix(3).map { String($0.title.prefix(60)) }), at: 0)
        }
        // 推荐以大模型的质量判断为主：先比质量分，再比是不是新教材，最后才看播放收藏算出的分数
        let candidates = kept.filter { $0.ai?.fit == "对口" && ($0.ai?.quality ?? 0) >= 4 }.sorted { a, b in
            let qa = a.ai?.quality ?? 0, qb = b.ai?.quality ?? 0
            if qa != qb { return qa > qb }
            if a.newTextbook != b.newTextbook { return a.newTextbook }
            return a.score > b.score
        }
        var picks: [Course] = []
        for c in candidates where picks.count < 3 && !picks.contains(where: { sameCourse(c, $0) }) { picks.append(c) }
        catalog.picks = picks.map(\.bvid)
    }

    /// 同一套课常被多个账号搬运：集数相同或标题高度相似的只推荐一条。
    static func sameCourse(_ a: Course, _ b: Course) -> Bool {
        if a.episodes > 0 && a.episodes == b.episodes { return true }
        func grams(_ t: String) -> Set<String> {
            let chars = Array(t)
            guard chars.count > 1 else { return [] }
            return Set((0..<(chars.count - 1)).map { String(chars[$0...($0 + 1)]) })
        }
        let ga = grams(a.title), gb = grams(b.title)
        guard !ga.isEmpty, !gb.isEmpty else { return false }
        return Double(ga.intersection(gb).count) / Double(min(ga.count, gb.count)) > 0.6
    }
}
