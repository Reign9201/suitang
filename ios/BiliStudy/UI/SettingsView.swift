import SwiftUI

struct SettingsView: View {
    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("设置").font(Theme.kai(30)).accessibilityAddTraits(.isHeader)
                    // 横屏够宽时左右两栏，竖屏上下排
                    if geo.size.width >= 900 {
                        HStack(alignment: .top, spacing: 24) {
                            card { SyncForm() }
                            card { AIForm() }
                        }
                    } else {
                        VStack(spacing: 24) {
                            card { SyncForm() }
                            card { AIForm() }
                        }
                    }
                }
                .padding(.horizontal, 32).padding(.top, 26).padding(.bottom, 40)
            }
        }
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        content()
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .card()
    }
}

struct LabeledField: View {
    var label: String
    @Binding var text: String
    var secure = false
    var hint: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 14, weight: .medium))
            Group {
                if secure { SecureField("", text: $text) } else { TextField("", text: $text) }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 15))
            .padding(.horizontal, 14)
            .frame(minHeight: 46)
            .background(Color(hex: 0xF8FAF8), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.rule, lineWidth: 1))
            .accessibilityLabel(label)
            if let hint { Text(hint).font(.system(size: 13)).foregroundStyle(Theme.ink2).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

/// 多端同步：WebDAV 地址、账号、应用密码。保存时先实际访问一次网盘。
struct SyncForm: View {
    @Environment(AppModel.self) private var model
    var onDone: (() -> Void)?
    @State private var url = DavAccount.jianguoyun
    @State private var user = ""
    @State private var password = ""
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("多端同步").font(Theme.kai(22)).accessibilityAddTraits(.isHeader)
                Text(model.sync.statusText).font(.system(size: 13)).foregroundStyle(model.sync.error == nil ? Theme.ink2 : Theme.red)
            }
            LabeledField(label: "WebDAV 地址", text: $url)
            LabeledField(label: "账号", text: $user)
            LabeledField(label: "应用密码", text: $password, secure: true,
                         hint: "坚果云在网页版「账户信息 → 安全选项 → 第三方应用管理」生成，不是登录密码")
            Text("同步收藏、学习进度、最近在学，以及选过的年级和课本版本").font(.system(size: 13)).foregroundStyle(Theme.ink2)
            if let error { Text(error).font(.system(size: 13)).foregroundStyle(Theme.red) }
            HStack(spacing: 10) {
                Button(saving ? "正在连接…" : "保存并同步") { Task { await save() } }
                    .buttonStyle(OutlineButtonStyle(primary: true))
                    .disabled(saving || user.isEmpty || password.isEmpty)
                if model.sync.account != nil {
                    Button("停止同步") { model.sync.logout(); password = "" }
                        .font(.system(size: 15)).foregroundStyle(Theme.ink2).frame(minHeight: 44).buttonStyle(.plain)
                }
            }
        }
        .onAppear {
            if let a = model.sync.account { url = a.url; user = a.user; password = a.password }
        }
    }

    private func save() async {
        saving = true
        error = nil
        defer { saving = false }
        do {
            try await model.sync.login(DavAccount(url: url.trimmingCharacters(in: .whitespaces), user: user.trimmingCharacters(in: .whitespaces), password: password))
            onDone?()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// 大模型整理：接口地址、密钥、两个任务的模型；高级选项里是鉴权方式、思考强度、取结果方式、API 版本。
struct AIForm: View {
    @Environment(AppModel.self) private var model
    @State private var config = AIConfig.load()
    @State private var secret = AIConfig.secret ?? ""
    @State private var testing = false
    @State private var result = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle(isOn: $config.enabled) { Text("大模型整理").font(Theme.kai(22)) }
                .tint(Theme.board)
                .frame(minHeight: 44)
            LabeledField(label: "接口地址", text: $config.baseURL)
            LabeledField(label: "密钥", text: $secret, secure: true, hint: "只存在本机钥匙串，不会同步到网盘")
            HStack(spacing: 12) {
                LabeledField(label: "整理用模型", text: $config.organizeModel)
                LabeledField(label: "搜索解析用模型", text: $config.intentModel)
            }
            DisclosureGroup("高级选项") {
                VStack(alignment: .leading, spacing: 12) {
                    picker("鉴权方式", selection: $config.auth, options: [(.bearer, "Bearer 令牌"), (.apiKey, "API Key")])
                    picker("思考强度", selection: $config.effort, options: [("low", "low"), ("medium", "medium"), ("high", "high"), ("", "不传")])
                    picker("取结果方式", selection: $config.jsonMode,
                           options: [(.auto, "自动"), (.schema, "结构化输出"), (.tool, "工具调用"), (.prompt, "提示词")])
                    LabeledField(label: "API 版本", text: $config.apiVersion, hint: "留空时官方地址用 2026-10-21，其他地址用 2023-06-01")
                    Stepper("每批 \(config.batchSize) 条", value: $config.batchSize, in: 1...50).frame(minHeight: 44)
                    Stepper("同时 \(config.concurrency) 个请求", value: $config.concurrency, in: 1...16).frame(minHeight: 44)
                }
                .padding(.top, 8)
            }
            .font(.system(size: 14))
            .frame(minHeight: 44)
            HStack(spacing: 12) {
                Button(testing ? "测试中…" : "测试连接") { Task { await test() } }
                    .buttonStyle(OutlineButtonStyle()).disabled(testing)
                Text(result.isEmpty && !config.enabled ? "关闭后只按标题规则归类" : result)
                    .font(.system(size: 13)).foregroundStyle(Theme.ink2)
            }
        }
        .onChange(of: config) { _, c in c.save(); model.catalogs.resetAI() }
        .onChange(of: secret) { _, s in AIConfig.secret = s; model.catalogs.resetAI() }
    }

    private func picker<T: Hashable>(_ label: String, selection: Binding<T>, options: [(T, String)]) -> some View {
        HStack {
            Text(label).font(.system(size: 14, weight: .medium))
            Spacer()
            Picker(label, selection: selection) { ForEach(options, id: \.0) { Text($0.1).tag($0.0) } }
                .pickerStyle(.menu)
        }
        .frame(minHeight: 44)
    }

    private func test() async {
        testing = true
        defer { testing = false }
        config.save()
        AIConfig.secret = secret
        let started = Date()
        do {
            let i = try await AIClient(config: config).parseIntent("我初二，浮力听不懂", currentGrade: 7, model: config.intentModel)
            result = String(format: "连接正常：%@，搜索解析用时 %.1f 秒（%@ %@）", config.intentModel, Date().timeIntervalSince(started),
                            i.grade > 0 ? Taxonomy.gradeName(i.grade) : "", i.subject)
        } catch {
            result = error.localizedDescription
        }
    }
}
