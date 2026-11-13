import SwiftUI

struct CatalogView: View {
    @Environment(AppModel.self) private var model
    @State private var kind = ""

    var body: some View {
        let g = model.grade ?? 7
        let subject = model.subject(for: g)
        let volume = model.volume(for: g, subject)
        let key = CatalogStore.key(g, subject, volume)
        VStack(alignment: .leading, spacing: 0) {
            NavigationLink(value: Route.search("")) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").font(.system(size: 15))
                    Text(AIConfig.load().unavailableReason == nil ? "搜索课程、知识点，或直接说想学什么" : "搜索课程、知识点或老师")
                    Spacer(minLength: 0)
                }
                .font(.system(size: 15))
                .foregroundStyle(Theme.ink3)
                .padding(.horizontal, 18)
                .frame(maxWidth: 560, minHeight: 46)
                .background(Color.white, in: Capsule())
                .overlay(Capsule().strokeBorder(Theme.rule, lineWidth: 1))
                .shadow(color: Theme.ink.opacity(0.04), radius: 2, y: 1)
            }
            .buttonStyle(.plain)

            Shelf(grade: g, active: subject) { model.choose(subject: $0); kind = "" }
                .padding(.top, 8)

            if let catalog = model.catalogs.catalogs[key] {
                FilterRow(grade: g, subject: subject, volume: volume, catalog: catalog, kind: $kind)
                    .padding(.top, 18)
                CourseList(catalog: catalog, kind: kind, status: model.catalogs.aiStatus[key])
            } else if let error = model.catalogs.errors[key] {
                VStack(alignment: .leading, spacing: 14) {
                    Text(error).foregroundStyle(Theme.ink2)
                    Button("重试") { model.catalogs.load(grade: g, subject: subject, volume: volume, refresh: true) }
                        .buttonStyle(OutlineButtonStyle())
                }
                .padding(.top, 40)
                Spacer()
            } else {
                ProgressView().padding(.top, 60).frame(maxWidth: .infinity)
                Spacer()
            }
        }
        .padding(.horizontal, 34)
        .padding(.top, 20)
        .task(id: key) { model.catalogs.load(grade: g, subject: subject, volume: volume) }
    }
}

/// 学科：书架上的书脊，竖排书名，当前学科高出一截。
struct Shelf: View {
    var grade: Int
    var active: String
    var pick: (String) -> Void
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(Array(Taxonomy.subjects(grade).enumerated()), id: \.element) { i, s in
                        let on = s == active
                        let h = Theme.spineHeights[i % Theme.spineHeights.count]
                        Button { pick(s) } label: {
                            VStack(spacing: 0) {
                                Capsule().fill(Color.white.opacity(0.55)).frame(height: 2).padding(.horizontal, 9).padding(.top, 10)
                                VerticalText(text: s, size: s.count > 2 ? 15 : 23, spacing: s.count > 2 ? 0 : 3)
                                    .padding(.top, s.count > 2 ? 6 : 10)
                                Spacer(minLength: 4)
                                Text(Taxonomy.volumeShort(grade, model.volume(for: grade, s))).font(.system(size: 12)).opacity(0.9)
                                    .padding(.bottom, 12)
                            }
                            .foregroundStyle(.white)
                            .frame(width: on ? 66 : 58, height: on ? h + 26 : h)
                            .background(Theme.subjectColors[s] ?? Theme.board)
                            .overlay {
                                LinearGradient(stops: [.init(color: .white.opacity(0.16), location: 0), .init(color: .clear, location: 0.35),
                                                       .init(color: .black.opacity(0.12), location: 1)], startPoint: .leading, endPoint: .trailing)
                            }
                            .clipShape(UnevenRoundedRectangle(topLeadingRadius: 6, bottomLeadingRadius: 2, bottomTrailingRadius: 2, topTrailingRadius: 6))
                            .overlay(alignment: .topTrailing) {
                                // 当前学科：顶上夹一条粉笔黄书签
                                if on { Ribbon().fill(Theme.yellow).frame(width: 12, height: 30).offset(x: -9, y: -12) }
                            }
                            .animation(.easeOut(duration: 0.2), value: on)
                        }
                        .buttonStyle(.plain)
                        .id(s)
                        .accessibilityLabel(s)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                    Spacer(minLength: 24)
                    Text(Taxonomy.gradeName(grade)).font(Theme.kai(46)).padding(.bottom, 8).accessibilityHidden(true)
                }
                .padding(.horizontal, 14)
                .padding(.top, 18)
                .frame(minHeight: 212, alignment: .bottom)
                .frame(minWidth: 940, alignment: .leading)
            }
            .overlay(alignment: .bottom) { UnevenRoundedRectangle(bottomLeadingRadius: 4, bottomTrailingRadius: 4).fill(Theme.board).frame(height: 10) }
            .onAppear { proxy.scrollTo(active, anchor: .center) }
        }
    }
}

/// 竖排文字：一个字一行。
struct VerticalText: View {
    var text: String
    var size: CGFloat
    var spacing: CGFloat = 3
    var body: some View {
        VStack(spacing: spacing) {
            ForEach(Array(text.enumerated()), id: \.offset) { _, ch in Text(String(ch)).font(Theme.kai(size)) }
        }
    }
}

/// 册次、版本、内容类型放在一行，放不下时横向滑动。
struct FilterRow: View {
    @Environment(AppModel.self) private var model
    var grade: Int
    var subject: String
    var volume: String
    var catalog: Catalog
    @Binding var kind: String

    var body: some View {
        let editions = Dictionary(grouping: catalog.items.flatMap(\.editions), by: { $0 }).mapValues(\.count)
            .filter { $0.value >= 2 }.sorted { $0.value > $1.value }
        let kinds = Dictionary(grouping: catalog.items.compactMap { $0.ai?.kind }, by: { $0 }).mapValues(\.count)
            .sorted { $0.value > $1.value }
        let edition = editions.contains { $0.key == model.edition(for: subject) } ? model.edition(for: subject) : ""
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                let volumes = Taxonomy.volumes(grade, subject)
                if volumes.count > 1 {
                    HStack(spacing: 0) {
                        ForEach(volumes, id: \.self) { v in
                            Button { model.choose(volume: v); kind = "" } label: {
                                Text(v).font(.system(size: 15, weight: v == volume ? .medium : .regular)).padding(.horizontal, 18).frame(minHeight: 38)
                                    .foregroundStyle(v == volume ? Color.white : Theme.ink2)
                                    .background(v == volume ? Theme.board : Color.clear, in: Capsule())
                                    .frame(minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(v == volume ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, 3)
                    .background(Color.white, in: Capsule())
                    .overlay(Capsule().strokeBorder(Theme.rule, lineWidth: 1))
                    .padding(.trailing, 10)
                }
                if !editions.isEmpty {
                    Chip(title: "全部", count: catalog.items.count, on: edition.isEmpty) { model.choose(edition: "", for: subject) }
                    ForEach(editions, id: \.key) { e in
                        Chip(title: e.key, count: e.value, on: edition == e.key) { model.choose(edition: e.key, for: subject) }
                    }
                }
                if kinds.count > 1 {
                    Rectangle().fill(Theme.rule).frame(width: 1, height: 24).padding(.horizontal, 8)
                    ForEach(kinds, id: \.key) { k in
                        Chip(title: k.key, count: k.value, on: kind == k.key) { kind = kind == k.key ? "" : k.key }
                    }
                }
            }
        }
    }
}

struct CourseList: View {
    @Environment(AppModel.self) private var model
    var catalog: Catalog
    var kind: String
    var status: AIStatus?

    var body: some View {
        let ed = model.edition(for: catalog.subject)
        let editionOK = catalog.items.filter { $0.editions.contains(ed) }.count >= 2
        let items = catalog.items.filter { (ed.isEmpty || !editionOK || $0.editions.contains(ed)) && (kind.isEmpty || $0.ai?.kind == kind) }
        let picks = Set(catalog.picks)
        let top = items.filter { picks.contains($0.bvid) }
        let rest = items.filter { !picks.contains($0.bvid) }
        let context = CourseContext(grade: catalog.grade, subject: catalog.subject, volume: catalog.volume)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if status == .pending {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.mini)
                        Text("大模型整理中")
                    }
                    .font(.system(size: 13)).foregroundStyle(Theme.ink3).padding(.top, 18)
                }
                if let h = model.library.history.first(where: { $0.grade == catalog.grade && $0.subject == catalog.subject }) {
                    ResumeCard(entry: h).padding(.top, 20)
                }
                if items.isEmpty {
                    Text("这个分类下还没有找到合适的视频。").foregroundStyle(Theme.ink2).padding(.vertical, 40)
                }
                section("推荐", top, context: context, pick: true)
                section("整套课程", rest.filter(\.series), context: context)
                section("单个讲解", rest.filter { !$0.series }, context: context)
                FilteredNote(catalog: catalog, status: status).padding(.top, 26).padding(.bottom, 40)
            }
            .padding(.horizontal, 12)
        }
        .scrollIndicators(.hidden)
        .padding(.horizontal, -12)
    }

    @ViewBuilder
    private func section(_ title: String, _ list: [Course], context: CourseContext, pick: Bool = false) -> some View {
        if !list.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title).font(Theme.kai(23))
                Text("\(list.count)").font(.system(size: 13)).foregroundStyle(Theme.ink3)
            }
            .padding(.top, 30).padding(.bottom, 12)
            .accessibilityAddTraits(.isHeader)
            if pick {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 250), spacing: 18, alignment: .top)], spacing: 18) {
                    ForEach(list) { PickCard(course: $0, context: context) }
                }
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, c in CourseRow(course: c, context: context, first: i == 0) }
                }
                .padding(.vertical, 6)
                .card()
            }
        }
    }
}

struct FilteredNote: View {
    var catalog: Catalog
    var status: AIStatus?

    var body: some View {
        let count = catalog.rejected.reduce(0) { $0 + $1.count }
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(catalog.rejected, id: \.reason) { r in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(r.reason)　\(r.count) 条").foregroundStyle(Theme.ink2)
                        if !r.samples.isEmpty { Text(r.samples.joined(separator: "；")).foregroundStyle(Theme.ink3) }
                    }
                }
                Text("搜索词：" + catalog.queries.joined(separator: "、"))
                switch status {
                case .ready(let m): Text("大模型整理：\(m)")
                case .failed(let e): Text("大模型整理没有完成：\(e)")
                case .off(let r): Text("未启用大模型整理（\(r)），当前只按标题规则归类。")
                default: EmptyView()
                }
            }
            .font(.system(size: 13)).foregroundStyle(Theme.ink3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        } label: {
            Text(count > 0 ? "已过滤 \(count) 条" : "整理说明").font(.system(size: 13)).foregroundStyle(Theme.ink3)
        }
        .tint(Theme.ink3)
    }
}
