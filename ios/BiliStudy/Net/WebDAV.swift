import Foundation

/// WebDAV 账号。坚果云的地址是 https://dav.jianguoyun.com/dav/，密码是「应用密码」。
struct DavAccount: Codable, Equatable {
    var url: String
    var user: String
    var password: String

    static let jianguoyun = "https://dav.jianguoyun.com/dav/"
}

struct RemoteFile: Hashable {
    var name: String
    var etag: String
}

/// 同步只需要四种请求：MKCOL 建文件夹、PROPFIND 列文件、GET 下载、PUT 上传。都在账号地址下的 bilistudy/ 里。
struct WebDAV {
    static let folderName = "bilistudy"
    let account: DavAccount

    private var folder: URL? {
        let base = account.url.trimmingCharacters(in: .whitespaces)
        return URL(string: base.hasSuffix("/") ? base : base + "/")?.appendingPathComponent(Self.folderName + "/")
    }

    private func request(_ url: URL, method: String) -> URLRequest {
        var r = URLRequest(url: url, timeoutInterval: 20)
        r.httpMethod = method
        let token = Data("\(account.user):\(account.password)".utf8).base64EncodedString()
        r.setValue("Basic \(token)", forHTTPHeaderField: "Authorization")
        return r
    }

    private func send(_ r: URLRequest) async throws -> (Int, Data, HTTPURLResponse?) {
        do {
            let (data, response) = try await URLSession.shared.data(for: r)
            let http = response as? HTTPURLResponse
            return (http?.statusCode ?? 0, data, http)
        } catch {
            throw AppError("连不上网盘：\(error.localizedDescription)")
        }
    }

    private func fail(_ status: Int) -> AppError {
        switch status {
        case 401: return AppError("账号或应用密码不对")
        case 403: return AppError("网盘拒绝访问（403）")
        case 404: return AppError("网盘地址不对（404）")
        case 507: return AppError("网盘空间不足")
        default: return AppError("网盘返回 \(status)")
        }
    }

    /// 建文件夹；已存在也算成功（405，有的服务返回 409 或 301）。
    func ensureFolder() async throws {
        guard let folder else { throw AppError("网盘地址无效") }
        let (status, _, _) = try await send(request(folder, method: "MKCOL"))
        if ![200, 201, 301, 405, 409].contains(status) { throw fail(status) }
    }

    func list() async throws -> [RemoteFile] {
        guard let folder else { throw AppError("网盘地址无效") }
        var r = request(folder, method: "PROPFIND")
        r.setValue("1", forHTTPHeaderField: "Depth")
        r.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        r.httpBody = Data(#"<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"#.utf8)
        let (status, data, _) = try await send(r)
        guard status == 207 || (200..<300).contains(status) else { throw fail(status) }
        return PropfindParser.parse(data).filter { $0.name.hasSuffix(".json") }
    }

    func get(_ name: String) async throws -> (Data, String) {
        guard let url = folder?.appendingPathComponent(name) else { throw AppError("网盘地址无效") }
        let (status, data, http) = try await send(request(url, method: "GET"))
        guard (200..<300).contains(status) else { throw fail(status) }
        return (data, http?.value(forHTTPHeaderField: "ETag") ?? "")
    }

    func put(_ name: String, _ data: Data) async throws {
        guard let url = folder?.appendingPathComponent(name) else { throw AppError("网盘地址无效") }
        var r = request(url, method: "PUT")
        r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        r.httpBody = data
        let (status, _, _) = try await send(r)
        guard (200..<300).contains(status) else { throw fail(status) }
    }
}

/// 解析 PROPFIND 的 207 回复：每个 response 里的 href（取文件名）和 getetag。命名空间前缀各家不同，只看本地名。
final class PropfindParser: NSObject, XMLParserDelegate {
    private var files: [RemoteFile] = []
    private var href = "", etag = "", text = ""

    static func parse(_ data: Data) -> [RemoteFile] {
        let p = PropfindParser()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.delegate = p
        parser.parse()
        return p.files
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        text = ""
        if name == "response" { href = ""; etag = "" }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "href": href = text.trimmingCharacters(in: .whitespacesAndNewlines)
        case "getetag": etag = text.trimmingCharacters(in: .whitespacesAndNewlines)
        case "response":
            let decoded = href.removingPercentEncoding ?? href
            if let last = decoded.split(separator: "/").last, !decoded.hasSuffix("/") {
                files.append(RemoteFile(name: String(last), etag: etag))
            }
        default: break
        }
    }
}
