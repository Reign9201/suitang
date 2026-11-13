import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var initial: String
    @State private var text = ""
    @State private var intent: SearchIntent?
    @State private var keyword = ""
    @State private var results: [Course] = []
    @State private var page = 1
    @State private var pages = 0
    @State private var loading = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.system(size: 18)).frame(width: 44, height: 44)
                }
                .buttonStyle(.plain).foregroundStyle(Theme.ink2).accessibilityLabel("返回")
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Theme.ink3)
                    TextField(AIConfig.load().unavailableReason == nil ? "搜索课程、知识点，或直接说想学什么" : "搜索课程、知识点或老师", text: $text)
                        .focused($focused)
                        .submitLabel(.search)
                        .onSubmit { Task { await search(reset: true) } }
                }
                .font(.system(size: 15))
                .padding(.horizontal, 18)
                .frame(maxWidth: 640, minHeight: 46)
                .background(Color.white, in: Capsule())
                .overlay(Capsule().strokeBorder(Theme.board, lineWidth: 1.5))
            }

            if let intent, intent.grade > 0 || !intent.topic.isEmpty {
                HStack(spacing: 8) {
                    ForEach([intent.grade > 0 ? Taxonomy.gradeName(intent.grade) : "", intent.subject, intent.volume, intent.topic].filter { !$0.isEmpty }, id: \.self) {
                        Text($0).font(.system(size: 14)).foregroundStyle(Theme.board).padding(.horizontal, 10).padding(.vertical, 7)
                            .background(Theme.tint, in: RoundedRectangle(cornerRadius: 8))
                    }
                    if intent.grade > 0, !intent.subject.isEmpty {
                        Button("打开\(Taxonomy.gradeName(intent.grade))\(intent.subject)\(intent.volume)") {
                            model.open(grade: intent.grade, subject: intent.subject, volume: intent.volume)
                        }
                        .buttonStyle(OutlineButtonStyle(primary: true)).padding(.leading, 6)
                    }
                }
                .padding(.top, 18)
            }
            if !keyword.isEmpty {
                Text("只搜索 B 站校园学习分区" + (keyword != text ? "，搜索词「\(keyword)」" : ""))
                    .font(.system(size: 13)).foregroundStyle(Theme.ink3).padding(.top, 4)
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !results.isEmpty {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(results.enumerated()), id: \.element.id) { i, c in
                                CourseRow(course: c, context: context(for: c), first: i == 0, whereTag: whereTag(c))
                            }
                        }
                        .padding(.vertical, 6)
                        .card()
                    }
                    if loading { ProgressView().padding(30).frame(maxWidth: .infinity) }
                    if let error {
                        Text(error).foregroundStyle(Theme.ink2).padding(.vertical, 30)
                    } else if !loading && results.isEmpty && !keyword.isEmpty {
                        Text("没有找到「\(text)」相关的学习视频，换个说法试试，比如加上年级或课本版本。")
                            .foregroundStyle(Theme.ink2).padding(.vertical, 30)
                    }
                    if !loading && page < pages {
                        Button("加载更多") { Task { await search(reset: false) } }
                            .buttonStyle(OutlineButtonStyle()).frame(maxWidth: .infinity).padding(.vertical, 20)
                    }
                }
                .padding(.top, 14)
                .padding(.horizontal, 12)
            }
            .padding(.horizontal, -12)
        }
        .padding(.horizontal, 34).padding(.top, 20)
        .background(Theme.paper)
        .onAppear {
            text = initial
            if initial.isEmpty { focused = true } else { Task { await search(reset: true) } }
        }
    }

    private func search(reset: Bool) async {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        loading = true
        error = nil
        defer { loading = false }
        if reset {
            results = []; page = 1; intent = nil; keyword = q
            // 有大模型时先把一句话解析成年级、学科、知识点，再用改写后的搜索词去搜
            let config = AIConfig.load()
            if config.unavailableReason == nil {
                if let i = try? await AIClient(config: config).parseIntent(q, currentGrade: model.grade, model: config.intentModel) {
                    intent = i
                    if !i.query.isEmpty { keyword = i.query }
                }
            }
        } else {
            page += 1
        }
        do {
            let data = try await BiliClient.shared.search(keyword, page: page, pageSize: 20)
            pages = Classify.int(data["numPages"])
            var items: [Course] = []
            for row in data["result"] as? [[String: Any]] ?? [] {
                guard let c = Classify.baseItem(row).0 else { continue }
                let g = Classify.describe(c).0
                items.append(Classify.finish(c, grade: g.count == 1 ? g.first! : 0, subject: nil, volume: nil))
            }
            if let i = intent, i.grade > 0 {
                // 和解析出的年级、学科一致的排前面，其余保持 B 站的顺序
                let subj = Classify.canonical(i.subject)
                func rank(_ c: Course) -> Int {
                    let sameSubject = subj.isEmpty || c.subjects.map(Classify.canonical).contains(subj)
                    return c.grades.contains(i.grade) && sameSubject ? 0 : (sameSubject ? 1 : 2)
                }
                items = items.enumerated().sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }.map(\.element)
            }
            results += items.filter { n in !results.contains { $0.bvid == n.bvid } }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func whereTag(_ c: Course) -> String? {
        let volume = Set(c.volumes).isSuperset(of: ["上册", "下册"]) ? "上下册" : (c.volumes.first ?? "")
        let parts = [c.grades.count == 1 ? Taxonomy.gradeName(c.grades[0]) : "", c.subjects.count == 1 ? c.subjects[0] : "", volume]
            .filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    private func context(for c: Course) -> CourseContext {
        CourseContext(grade: c.grades.count == 1 ? c.grades[0] : intent?.grade, subject: c.subjects.count == 1 ? c.subjects[0] : nil,
                      volume: c.volumes.first)
    }
}
