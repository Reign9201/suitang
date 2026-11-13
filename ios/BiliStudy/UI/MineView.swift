import SwiftUI

struct MineView: View {
    @Environment(AppModel.self) private var model
    @State private var subject = ""

    var body: some View {
        let favorites = model.library.favorites
        let subjects = Array(Set(favorites.compactMap(\.subject))).sorted()
        let shown = favorites.filter { subject.isEmpty || $0.subject == subject }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 16) {
                    Text("我的").font(Theme.kai(30)).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Text(model.sync.account == nil ? "" : model.sync.statusText).font(.system(size: 13)).foregroundStyle(Theme.ink3)
                    if model.sync.account == nil {
                        Button("登录网盘同步") { model.section = .settings }.buttonStyle(OutlineButtonStyle())
                    } else {
                        Button { Task { await model.sync.syncNow() } } label: {
                            Label("立即同步", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .buttonStyle(OutlineButtonStyle()).disabled(model.sync.running)
                    }
                }

                let history = model.library.history
                if !history.isEmpty {
                    Text("继续学习").font(Theme.kai(23)).padding(.top, 28).padding(.bottom, 12).accessibilityAddTraits(.isHeader)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 18), GridItem(.flexible(), spacing: 18)], spacing: 18) {
                        ForEach(history.prefix(4)) { h in resumeCard(h) }
                    }
                }

                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("收藏").font(Theme.kai(23))
                    Text("\(favorites.count)").font(.system(size: 13)).foregroundStyle(Theme.ink3)
                }
                .padding(.top, 30).padding(.bottom, 12)
                .accessibilityAddTraits(.isHeader)
                if subjects.count > 1 {
                    // 收藏超过一个学科时才给筛选
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            Chip(title: "全部", count: favorites.count, on: subject.isEmpty) { subject = "" }
                            ForEach(subjects, id: \.self) { s in
                                Chip(title: s, count: favorites.filter { $0.subject == s }.count, on: subject == s) { subject = s }
                            }
                        }
                    }
                    .padding(.bottom, 8)
                }
                if favorites.isEmpty {
                    Text("在课程旁点书签就能收藏，换设备登录同一个网盘后也能看到。")
                        .font(.system(size: 15)).foregroundStyle(Theme.ink2).padding(.vertical, 20)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(shown.enumerated()), id: \.element.id) { i, f in favoriteRow(f, first: i == 0) }
                    }
                    .padding(.vertical, 6)
                    .card()
                }
            }
            .padding(.horizontal, 34).padding(.top, 26).padding(.bottom, 40)
        }
    }

    private func resumeCard(_ h: HistoryEntry) -> some View {
        let done = model.library.watchedCount(h.bvid)
        let where_ = [h.grade.map(Taxonomy.gradeName), h.subject, h.volume].compactMap { $0 }.joined(separator: " ")
        return NavigationLink(value: Route.player(bvid: h.bvid, context: CourseContext(grade: h.grade, subject: h.subject, volume: h.volume))) {
            HStack(alignment: .top, spacing: 14) {
                Thumb(url: URL(string: h.cover.contains("hdslb.com") ? h.cover + "@336w_189h_1c.jpg" : h.cover), width: 144)
                VStack(alignment: .leading, spacing: 6) {
                    if !where_.isEmpty { Text(where_).font(.system(size: 13)).foregroundStyle(Theme.ink2) }
                    Text("第 \(h.index + 1) 节　\(h.part)").font(.system(size: 16, weight: .medium)).lineLimit(1)
                    Text(h.title).font(.system(size: 13)).foregroundStyle(Theme.ink2).lineLimit(1)
                    HStack(spacing: 10) {
                        ProgressBar(value: Double(done) / Double(max(1, h.total)))
                        Text("\(done) / \(h.total)").font(.system(size: 13).monospacedDigit()).foregroundStyle(Theme.ink3)
                    }
                    .padding(.top, 2)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("继续学习：\(h.title)，第 \(h.index + 1) 节 \(h.part)，已看完 \(done) 节，共 \(h.total) 节")
        .accessibilityAddTraits(.isButton)
    }

    private func favoriteRow(_ f: SavedCourse, first: Bool) -> some View {
        let where_ = [f.grade.map(Taxonomy.gradeName), f.subject].compactMap { $0 }.joined(separator: " ")
        return HStack(spacing: 16) {
            NavigationLink(value: Route.player(bvid: f.bvid, context: CourseContext(grade: f.grade, subject: f.subject, volume: f.volume))) {
                HStack(spacing: 18) {
                    Thumb(url: URL(string: f.cover.contains("hdslb.com") ? f.cover + "@336w_189h_1c.jpg" : f.cover), duration: f.duration, width: 160)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(f.title).font(.system(size: 16, weight: .semibold)).lineLimit(2).multilineTextAlignment(.leading)
                        HStack(spacing: 14) {
                            if !where_.isEmpty { Tag(text: where_, style: .outline) }
                            Text(f.author)
                            if f.episodes > 1 { Text("\(f.episodes) 集") }
                        }
                        .font(.system(size: 13)).foregroundStyle(Theme.ink2)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(f.title)，\(f.author)")
            .accessibilityAddTraits(.isButton)
            Button { model.library.toggleFavorite(f) } label: { BookmarkIcon(filled: true) }
                .buttonStyle(.plain)
                .accessibilityLabel("取消收藏：" + String(f.title.prefix(24)))
        }
        .padding(.vertical, 12).padding(.leading, 14).padding(.trailing, 8)
        .overlay(alignment: .top) { if !first { Rectangle().fill(Theme.rule).frame(height: 1).padding(.horizontal, 14) } }
    }
}
