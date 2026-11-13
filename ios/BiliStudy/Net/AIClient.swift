import Foundation

/// 大模型设置。除密钥外存在 UserDefaults；密钥在钥匙串（Keychain.get("ai.secret")）。
/// 字段和 Python 原型的 config.json「ai」一节对应。
struct AIConfig: Codable, Equatable {
    enum Auth: String, Codable, CaseIterable { case bearer, apiKey }
    enum JSONMode: String, Codable, CaseIterable { case auto, schema, tool, prompt }

    var enabled = true
    var baseURL = "https://api.anthropic.com"
    var auth: Auth = .apiKey
    var organizeModel = "claude-opus-5-5"
    var intentModel = "claude-opus-5-5"
    var effort = "low"             // "" 表示请求里不带
    var jsonMode: JSONMode = .auto
    var apiVersion = ""            // "" 表示自动：官方地址用 2026-10-21，其他地址用 2023-06-01
    var batchSize = 10
    var concurrency = 4
    var timeout: Double = 120

    var isOfficial: Bool { baseURL.contains("api.anthropic.com") }
    var effectiveJSONMode: JSONMode { jsonMode == .auto ? (isOfficial ? .schema : .tool) : jsonMode }
    var effectiveAPIVersion: String { apiVersion.isEmpty ? (isOfficial ? "2026-10-21" : "2023-06-01") : apiVersion }

    private static let key = "ai.config"

    static func load() -> AIConfig {
        guard let data = UserDefaults.standard.data(forKey: key),
              let c = try? JSONDecoder().decode(AIConfig.self, from: data) else { return AIConfig() }
        return c
    }

    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: Self.key) }

    static var secret: String? {
        get { Keychain.get("ai.secret") }
        set { Keychain.set("ai.secret", newValue) }
    }

    /// 没启用、没有密钥时返回原因；可以用时返回 nil。
    var unavailableReason: String? {
        if !enabled { return "设置里关闭了大模型整理" }
        if (Self.secret ?? "").isEmpty { return "没有填写大模型密钥" }
        if URL(string: baseURL) == nil { return "大模型接口地址无效" }
        return nil
    }
}

/// 调 Anthropic Messages 接口（或兼容它的网关，如智谱）。Swift 没有官方 SDK，这里直接发 HTTP 请求。
/// 逻辑和 Python 原型的 ai.py 一致：分批并行整理、三种拿 JSON 的方式、结果校验。
struct AIClient {
    let config: AIConfig

    private static let fallbackModels = ["claude-opus-5", "claude-sonnet-5-5", "claude-fable-5"]
    private static let resultTool = "submit_result"

    // MARK: 调用

    func call(model: String, system: String, user: String, schema: [String: Any], maxTokens: Int) async throws -> [String: Any] {
        guard let secret = AIConfig.secret, !secret.isEmpty else { throw AppError("没有填写大模型密钥") }
        let base = config.baseURL.hasSuffix("/") ? String(config.baseURL.dropLast()) : config.baseURL
        guard let url = URL(string: base + "/v1/messages") else { throw AppError("大模型接口地址无效") }
        let mode = config.effectiveJSONMode
        var system = system
        var outputConfig: [String: Any] = [:]
        if !config.effort.isEmpty { outputConfig["effort"] = config.effort }
        var body: [String: Any] = ["model": model, "max_tokens": maxTokens]
        switch mode {
        case .schema, .auto:
            outputConfig["format"] = ["type": "json_schema", "schema": schema]
        case .tool:
            body["tools"] = [["name": Self.resultTool, "description": "提交结果。结果只能通过这个工具提交。", "input_schema": schema]]
            // 新的 Claude 模型不接受强制调用指定工具，改为自动选择加提示；其他模型直接强制调用
            body["tool_choice"] = model.hasPrefix("claude-") ? ["type": "auto"] : ["type": "tool", "name": Self.resultTool]
            system += "\n\n用 \(Self.resultTool) 工具提交结果，不要直接回答。"
        case .prompt:
            let schemaText = String(data: try JSONSerialization.data(withJSONObject: schema), encoding: .utf8) ?? ""
            system += "\n\n只输出一个符合下面 JSON Schema 的 JSON 对象，不要输出其他任何文字：\n" + schemaText
        }
        body["system"] = system
        body["messages"] = [["role": "user", "content": user]]
        if !outputConfig.isEmpty { body["output_config"] = outputConfig }

        var request = URLRequest(url: url, timeoutInterval: config.timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(config.effectiveAPIVersion, forHTTPHeaderField: "anthropic-version")
        if config.auth == .bearer { request.setValue("Bearer \(secret)", forHTTPHeaderField: "authorization") }
        else { request.setValue(secret, forHTTPHeaderField: "x-api-key") }
        // 安全分类器误拒时，由服务端换一个模型重跑；只有官方接口支持
        if config.isOfficial && Self.fallbackModels.contains(where: model.hasPrefix) {
            request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
            body["fallbacks"] = "default"
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data, response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: request) }
        catch { throw AppError((error as NSError).code == NSURLErrorTimedOut ? "大模型接口超时" : "连不上大模型接口 \(base)") }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard status == 200 else {
            let message = ((root["error"] as? [String: Any])?["message"] as? String).map { String($0.prefix(160)) } ?? ""
            switch status {
            case 401: throw AppError("密钥无效")
            case 403: throw AppError("这个密钥没有调用 \(model) 的权限")
            case 404: throw AppError("找不到模型 \(model)，检查设置里的模型名")
            case 429: throw AppError("大模型请求太频繁，稍后再试")
            case 400: throw AppError("大模型接口拒绝了请求参数：\(message)")
            default: throw AppError("大模型接口返回 \(status)")
            }
        }
        switch root["stop_reason"] as? String {
        case "refusal": throw AppError("大模型拒绝了这次请求")
        case "max_tokens": throw AppError("大模型输出被截断，可在设置里调小每批条数")
        default: break
        }
        let blocks = root["content"] as? [[String: Any]] ?? []
        if let tool = blocks.first(where: { $0["type"] as? String == "tool_use" && $0["name"] as? String == Self.resultTool }),
           let input = tool["input"] as? [String: Any] { return input }
        let text = blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
        guard !text.isEmpty else { throw AppError("大模型没有返回结果") }
        return try Self.parseJSONText(text)
    }

    /// 从文字里取出 JSON 对象：容忍 ```json 代码块和前后多余的文字。
    static func parseJSONText(_ raw: String) throws -> [String: Any] {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("```") {
            text = String(text.split(separator: "\n", maxSplits: 1).last ?? "")
            if let end = text.range(of: "```", options: .backwards) { text = String(text[..<end.lowerBound]) }
        }
        if let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] { return obj }
        if let l = text.firstIndex(of: "{"), let r = text.lastIndex(of: "}"), l < r,
           let obj = (try? JSONSerialization.jsonObject(with: Data(text[l...r].utf8))) as? [String: Any] { return obj }
        throw AppError("大模型返回的不是有效 JSON")
    }

    // MARK: 分类整理

    private static let organizeSchema: [String: Any] = [
        "type": "object",
        "properties": ["items": ["type": "array", "items": [
            "type": "object",
            "properties": [
                "bvid": ["type": "string"], "fit": ["type": "string", "enum": Prompts.fits],
                "kind": ["type": "string", "enum": Prompts.kinds], "level": ["type": "string", "enum": Prompts.levels],
                "edition": ["type": "string"], "summary": ["type": "string"], "quality": ["type": "integer"],
                "comment": ["type": "string"],
            ],
            "required": ["bvid", "fit", "kind", "level", "edition", "summary", "quality", "comment"],
            "additionalProperties": false,
        ]]],
        "required": ["items"],
        "additionalProperties": false,
    ]

    private static func describe(_ i: Int, _ c: Course) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM"
        var parts = ["\(i). [\(c.bvid)] 标题：\(c.title)", "UP主：\(c.author)",
                     c.duration >= 3600 ? String(format: "总时长：%.1f小时", Double(c.duration) / 3600) : "时长：\(c.duration / 60)分钟",
                     "播放：\(c.play >= 10000 ? String(format: "%.1f万", Double(c.play) / 1e4) : "\(c.play)")",
                     "收藏：\(c.favorites >= 10000 ? String(format: "%.1f万", Double(c.favorites) / 1e4) : "\(c.favorites)")",
                     "发布：\(c.pubdate > 0 ? f.string(from: Date(timeIntervalSince1970: TimeInterval(c.pubdate))) : "未知")"]
        if c.episodes > 0 { parts.append("集数：\(c.episodes)") }
        if !c.tagtext.isEmpty { parts.append("标签：\(c.tagtext.prefix(100))") }
        if !c.desc.isEmpty { parts.append("简介：\(c.desc)") }
        return parts.joined(separator: "｜")
    }

    private static func pick(_ v: Any?, _ choices: [String], _ fallback: String) -> String {
        (v as? String).flatMap { choices.contains($0) ? $0 : nil } ?? fallback
    }

    /// 按 Schema 校验一条判断：缺字段或取值不合法时用保守的默认值，避免一条坏数据弄坏整页。
    static func judgement(_ row: [String: Any]) -> (String, AIJudgement)? {
        guard let bvid = (row["bvid"] as? String)?.trimmingCharacters(in: .whitespaces), !bvid.isEmpty else { return nil }
        let q = (row["quality"] as? NSNumber)?.intValue ?? Int(row["quality"] as? String ?? "") ?? 3
        return (bvid, AIJudgement(
            fit: pick(row["fit"], Prompts.fits, "相关"), kind: pick(row["kind"], Prompts.kinds, "其他"),
            level: pick(row["level"], Prompts.levels, "中等"), edition: String((row["edition"] as? String ?? "").prefix(12)),
            summary: String((row["summary"] as? String ?? "").prefix(60)), quality: max(1, min(5, q)),
            comment: String((row["comment"] as? String ?? "").prefix(40))))
    }

    /// 返回 [bvid: 判断]。部分批次失败时返回已完成的部分；全部失败才抛错。
    func organize(gradeName: String, stage: String, subject: String, volume: String, items: [Course], model: String) async throws -> [String: AIJudgement] {
        let target = "\(gradeName) \(subject) \(volume)（\(stage)）"
        let size = max(1, config.batchSize)
        let chunks = stride(from: 0, to: items.count, by: size).map { Array(items[$0..<min($0 + size, items.count)]) }
        var results: [String: AIJudgement] = [:]
        var firstError: Error?
        let client = self
        await withTaskGroup(of: Result<[String: AIJudgement], Error>.self) { group in
            var next = 0
            while next < chunks.count && next < max(1, config.concurrency) {
                let chunk = chunks[next]; next += 1
                group.addTask {
                    do { return .success(try await client.organizeChunk(target: target, chunk: chunk, model: model)) }
                    catch { return .failure(error) }
                }
            }
            while let r = await group.next() {
                switch r {
                case .success(let d): results.merge(d) { a, _ in a }
                case .failure(let e): if firstError == nil { firstError = e }
                }
                if next < chunks.count {
                    let chunk = chunks[next]; next += 1
                    group.addTask {
                        do { return .success(try await client.organizeChunk(target: target, chunk: chunk, model: model)) }
                        catch { return .failure(error) }
                    }
                }
            }
        }
        if results.isEmpty, let firstError { throw firstError }
        return results
    }

    private func organizeChunk(target: String, chunk: [Course], model: String) async throws -> [String: AIJudgement] {
        let user = "目标分类：\(target)\n候选视频：\n" + chunk.enumerated().map { Self.describe($0.offset + 1, $0.element) }.joined(separator: "\n")
        let data = try await call(model: model, system: Prompts.organizeSystem, user: user, schema: Self.organizeSchema, maxTokens: 8000)
        var rows = data["items"] as? [[String: Any]]
        if rows == nil, let s = data["items"] as? String, let parsed = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [[String: Any]] {
            rows = parsed // 有的模型会把数组写成字符串
        }
        let wanted = Set(chunk.map(\.bvid))
        var out: [String: AIJudgement] = [:]
        for row in rows ?? [] { if let (id, j) = Self.judgement(row), wanted.contains(id) { out[id] = j } }
        return out
    }

    // MARK: 一句话搜索

    private static let intentSchema: [String: Any] = [
        "type": "object",
        "properties": ["grade": ["type": "integer"], "subject": ["type": "string", "enum": Taxonomy.allSubjects + [""]],
                       "volume": ["type": "string"], "topic": ["type": "string"], "query": ["type": "string"]],
        "required": ["grade", "subject", "volume", "topic", "query"],
        "additionalProperties": false,
    ]

    func parseIntent(_ text: String, currentGrade: Int?, model: String) async throws -> SearchIntent {
        let month = Calendar.current.component(.month, from: Date())
        let term = month >= 8 || month == 1 ? "上学期（秋季，9 月到次年 1 月）" : "下学期（春季，2 月到 7 月）"
        let user = "当前年级：\(currentGrade.map(Taxonomy.gradeName) ?? "未知")\n当前学期：\(term)\n用户的话：\(text)"
        let d = try await call(model: model, system: Prompts.intentSystem, user: user, schema: Self.intentSchema, maxTokens: 2000)
        let g = (d["grade"] as? NSNumber)?.intValue ?? 0
        var intent = SearchIntent(grade: (1...12).contains(g) ? g : 0, subject: Self.pick(d["subject"], Taxonomy.allSubjects, ""),
                                  volume: String((d["volume"] as? String ?? "").prefix(12)),
                                  topic: String((d["topic"] as? String ?? "").prefix(30)), query: String((d["query"] as? String ?? "").prefix(40)))
        // 初中叫道德与法治，高中叫政治；不在这个年级科目表里的学科不给跳转
        if intent.grade > 0 {
            if intent.subject == "道德与法治" || intent.subject == "政治" { intent.subject = intent.grade >= 10 ? "政治" : "道德与法治" }
            if !Taxonomy.subjects(intent.grade).contains(intent.subject) { intent.subject = "" }
        }
        if intent.grade == 0 || intent.subject.isEmpty || !Taxonomy.volumes(intent.grade, intent.subject).contains(intent.volume) {
            intent.volume = ""
        }
        return intent
    }
}
