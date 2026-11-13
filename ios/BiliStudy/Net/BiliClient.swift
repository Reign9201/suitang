import Foundation

struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// B 站公开网页接口，只用访客身份（buvid3 Cookie），不登录、不处理付费内容。
/// 做法和 Montage 的 BilibiliSearch / BilibiliVideoClient、原型的 server.py 一致。
actor BiliClient {
    static let shared = BiliClient()

    private static let apiUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    /// 视频服务器拒绝手机浏览器的 UA，桌面 UA 可以。
    static let mediaUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        return URLSession(configuration: config)
    }()
    private var cookie = ""
    private var cookieAt = Date.distantPast
    private var lastCall = Date.distantPast

    private func throttle() async {
        let wait = lastCall.addingTimeInterval(0.35).timeIntervalSinceNow
        if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1e9)) }
        lastCall = Date()
    }

    private func raw(_ url: URL, referer: String, cookie: String = "") async throws -> (Int, Data) {
        await throttle()
        var request = URLRequest(url: url)
        request.setValue(Self.apiUA, forHTTPHeaderField: "User-Agent")
        request.setValue(referer, forHTTPHeaderField: "Referer")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        if !cookie.isEmpty { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
        do {
            let (data, response) = try await session.data(for: request)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
        } catch {
            throw AppError("连不上 B 站：\(error.localizedDescription)")
        }
    }

    private func visitorCookie(force: Bool = false) async -> String {
        if !force, !cookie.isEmpty, Date().timeIntervalSince(cookieAt) < 12 * 3600 { return cookie }
        guard let (_, data) = try? await raw(URL(string: "https://api.bilibili.com/x/frontend/finger/spi")!,
                                             referer: "https://www.bilibili.com/"),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let d = root["data"] as? [String: Any], let b3 = d["b_3"] as? String, !b3.isEmpty
        else { cookie = ""; return "" }
        let b4 = d["b_4"] as? String ?? ""
        cookie = b4.isEmpty ? "buvid3=\(b3)" : "buvid3=\(b3); buvid4=\(b4)"
        cookieAt = Date()
        return cookie
    }

    /// 取 data 字段；遇到 412 风控换一个访客 Cookie 重试两次。
    private func get(_ url: URL, referer: String) async throws -> [String: Any] {
        var c = await visitorCookie()
        for attempt in 0..<3 {
            let (status, data) = try await raw(url, referer: referer, cookie: c)
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            let code = (root?["code"] as? NSNumber)?.intValue
            if status != 412 && code != -412 {
                guard status == 200, let root else { throw AppError("B 站返回 HTTP \(status)") }
                guard code == 0 else { throw AppError("B 站返回错误 \(code ?? -1)：\(root["message"] as? String ?? "")") }
                return root["data"] as? [String: Any] ?? [:]
            }
            try? await Task.sleep(nanoseconds: UInt64(400_000_000 * (attempt + 1)))
            c = await visitorCookie(force: true)
        }
        throw AppError("B 站暂时拒绝了请求，过一会儿再试")
    }

    func search(_ keyword: String, page: Int = 1, pageSize: Int = 30, tids: Int? = Classify.studyTid) async throws -> [String: Any] {
        var c = URLComponents(string: "https://api.bilibili.com/x/web-interface/search/type")!
        c.queryItems = [
            .init(name: "search_type", value: "video"), .init(name: "keyword", value: keyword),
            .init(name: "order", value: "totalrank"), .init(name: "page", value: "\(page)"),
            .init(name: "page_size", value: "\(pageSize)"),
        ] + (tids.map { [URLQueryItem(name: "tids", value: "\($0)")] } ?? [])
        return try await get(c.url!, referer: "https://search.bilibili.com/")
    }

    func detail(_ bvid: String) async throws -> CourseDetail {
        var c = URLComponents(string: "https://api.bilibili.com/x/web-interface/view")!
        c.queryItems = [.init(name: "bvid", value: bvid)]
        let d = try await get(c.url!, referer: "https://www.bilibili.com/video/\(bvid)/")
        let title = Classify.clean(d["title"] as? String)
        let pages = (d["pages"] as? [[String: Any]] ?? []).map { p -> Lesson in
            let page = Classify.int(p["page"])
            return Lesson(key: "p\(page)", bvid: bvid, cid: Classify.int(p["cid"]), page: page,
                          part: Classify.clean(p["part"] as? String), duration: Classify.int(p["duration"]))
        }
        // B 站「合集」：每一节是独立的 BV 号；分 P 视频没有这一项
        var season: [Lesson] = []
        var seasonTitle: String?
        if let s = d["ugc_season"] as? [String: Any] {
            seasonTitle = Classify.clean(s["title"] as? String)
            for section in s["sections"] as? [[String: Any]] ?? [] {
                for ep in section["episodes"] as? [[String: Any]] ?? [] {
                    guard let id = ep["bvid"] as? String else { continue }
                    let cid = Classify.int(ep["cid"]) != 0 ? Classify.int(ep["cid"]) : Classify.int((ep["page"] as? [String: Any])?["cid"])
                    let dur = Classify.int((ep["arc"] as? [String: Any])?["duration"])
                    season.append(Lesson(key: id, bvid: id, cid: cid, page: 1, part: Classify.clean(ep["title"] as? String), duration: dur))
                }
            }
        }
        let lessons: [Lesson]
        if pages.count > 1 { lessons = pages; seasonTitle = nil }
        else if season.count > 1 { lessons = season }
        else {
            seasonTitle = nil
            lessons = [Lesson(key: "p1", bvid: bvid, cid: pages.first?.cid ?? Classify.int(d["cid"]), page: 1,
                              part: title, duration: Classify.int(d["duration"]))]
        }
        let stat = d["stat"] as? [String: Any]
        return CourseDetail(bvid: bvid, title: title, author: Classify.clean((d["owner"] as? [String: Any])?["name"] as? String),
                            cover: Classify.https(d["pic"] as? String ?? ""), duration: Classify.int(d["duration"]),
                            play: Classify.int(stat?["view"]), lessons: lessons, seasonTitle: seasonTitle)
    }

    /// 访客可取的 MP4 地址（目前只有 360P）。播放时必须带 B 站的 Referer。
    func playSource(bvid: String, cid: Int) async throws -> PlaySource {
        var c = URLComponents(string: "https://api.bilibili.com/x/player/playurl")!
        c.queryItems = [.init(name: "bvid", value: bvid), .init(name: "cid", value: "\(cid)"), .init(name: "qn", value: "64"),
                        .init(name: "fnval", value: "1"), .init(name: "fnver", value: "0"), .init(name: "fourk", value: "0")]
        let referer = "https://www.bilibili.com/video/\(bvid)/"
        let d = try await get(c.url!, referer: referer)
        guard let first = (d["durl"] as? [[String: Any]])?.first, let s = first["url"] as? String, let url = URL(string: Classify.https(s))
        else { throw AppError("这个视频暂时只能在 B 站播放") }
        let quality = [16: "360P", 32: "480P", 64: "720P", 80: "1080P"][Classify.int(d["quality"])] ?? "\(Classify.int(d["quality"]))P"
        return PlaySource(url: url, quality: quality,
                          headers: ["User-Agent": Self.mediaUA, "Referer": referer, "Origin": "https://www.bilibili.com"])
    }
}
