import SwiftUI

/// 封面：圆角，右下角是总时长；有进度时底部一条粉笔黄进度条。
struct Thumb: View {
    var url: URL?
    var duration: Int?
    var width: CGFloat? = 176
    var radius: CGFloat = 12
    var progress: Double = 0

    var body: some View {
        Color.clear
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(width: width)
            .overlay {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() } else { Theme.thumbBg }
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if let duration, duration > 0 {
                    Text(Formatter.duration(duration))
                        .font(.system(size: 12, weight: .medium).monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 7).padding(.vertical, 4)
                        .background(Theme.screen.opacity(0.74), in: RoundedRectangle(cornerRadius: 6))
                        .padding(7)
                        .accessibilityHidden(true)
                }
            }
            .overlay(alignment: .bottom) {
                if progress > 0 {
                    ProgressBar(value: progress, fill: Theme.yellow, track: .black.opacity(0.3), height: 4)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension Course {
    /// 推荐卡片用大一些的封面。
    var largeThumbURL: URL? {
        URL(string: cover.contains("hdslb.com") ? cover + "@480w_270h_1c.jpg" : cover)
    }
}

/// 课程行：封面、标题、简介、作者和集数、标签，右边是看完几节和收藏。排在一张白纸上，行与行之间一条细线。
struct CourseRow: View {
    @Environment(AppModel.self) private var model
    var course: Course
    var context: CourseContext
    var first = false
    var whereTag: String?

    var body: some View {
        let saved = model.library.isFavorite(course.bvid)
        HStack(alignment: .center, spacing: 8) {
            NavigationLink(value: Route.player(bvid: course.bvid, context: context)) {
                HStack(alignment: .center, spacing: 18) {
                    Thumb(url: course.thumbURL, duration: course.duration)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(course.title).font(.system(size: 16, weight: .semibold)).lineLimit(2).multilineTextAlignment(.leading)
                        if let summary = course.ai?.summary, !summary.isEmpty {
                            Text(summary).font(.system(size: 14)).foregroundStyle(Theme.ink2).lineLimit(1)
                        }
                        CourseMeta(course: course)
                        CourseTags(course: course, whereTag: whereTag)
                    }
                    Spacer(minLength: 0)
                    WatchedCount(bvid: course.bvid)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(course.accessibilityText(withComment: false))
            .accessibilityAddTraits(.isButton)

            Button { model.library.toggleFavorite(course.snapshot(context)) } label: { BookmarkIcon(filled: saved) }
                .buttonStyle(.plain)
                .accessibilityLabel((saved ? "取消收藏：" : "收藏：") + String(course.title.prefix(24)))
        }
        .padding(.vertical, 12).padding(.leading, 14).padding(.trailing, 8)
        .overlay(alignment: .top) { if !first { Rectangle().fill(Theme.rule).frame(height: 1).padding(.horizontal, 14) } }
    }
}

/// 推荐：大封面卡片。标题、批注、简介都预留固定行数，同一行的卡片一样高。
struct PickCard: View {
    @Environment(AppModel.self) private var model
    var course: Course
    var context: CourseContext

    var body: some View {
        let saved = model.library.isFavorite(course.bvid)
        let seen = model.library.watchedCount(course.bvid)
        let total = model.library.historyEntry(course.bvid)?.total ?? 0
        NavigationLink(value: Route.player(bvid: course.bvid, context: context)) {
            VStack(alignment: .leading, spacing: 0) {
                Thumb(url: course.largeThumbURL, duration: course.duration, width: nil, radius: 0,
                      progress: total > 0 ? Double(seen) / Double(total) : 0)
                VStack(alignment: .leading, spacing: 7) {
                    Text(course.title).font(.system(size: 16, weight: .semibold)).lineLimit(2, reservesSpace: true)
                        .multilineTextAlignment(.leading)
                    Text(course.ai?.comment ?? "").font(Theme.kai(17)).foregroundStyle(Theme.red).lineLimit(2, reservesSpace: true)
                    Text(course.ai?.summary ?? "").font(.system(size: 14)).foregroundStyle(Theme.ink2).lineLimit(3, reservesSpace: true)
                    CourseMeta(course: course)
                    CourseTags(course: course)
                }
                .padding(EdgeInsets(top: 12, leading: 16, bottom: 16, trailing: 16))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .card()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(course.accessibilityText(withComment: true))
        .accessibilityAddTraits(.isButton)
        .overlay(alignment: .topTrailing) {
            Button { model.library.toggleFavorite(course.snapshot(context)) } label: {
                BookmarkIcon(filled: saved)
                    .background(Color.white.opacity(0.92), in: Circle())
                    .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
            }
            .buttonStyle(.plain)
            .padding(8)
            .accessibilityLabel((saved ? "取消收藏：" : "收藏：") + String(course.title.prefix(24)))
        }
    }
}

/// 分类页顶部的继续学习：封面、这一节的标题、整门课的进度，右边一个大按钮。
struct ResumeCard: View {
    @Environment(AppModel.self) private var model
    var entry: HistoryEntry

    var body: some View {
        let seen = model.library.watchedCount(entry.bvid)
        NavigationLink(value: Route.player(bvid: entry.bvid, context: CourseContext(grade: entry.grade, subject: entry.subject, volume: entry.volume))) {
            HStack(spacing: 20) {
                Thumb(url: URL(string: entry.cover.contains("hdslb.com") ? entry.cover + "@336w_189h_1c.jpg" : entry.cover), width: 176)
                VStack(alignment: .leading, spacing: 5) {
                    Text(entry.part).font(Theme.kai(21)).lineLimit(1)
                    Text(entry.title).font(.system(size: 13)).foregroundStyle(Theme.ink2).lineLimit(1)
                    HStack(spacing: 10) {
                        ProgressBar(value: Double(seen) / Double(max(1, entry.total))).frame(maxWidth: 260)
                        Text("已看完 \(seen) / \(entry.total) 节").font(.system(size: 12).monospacedDigit()).foregroundStyle(Theme.ink3)
                    }
                    .padding(.top, 6)
                }
                Spacer(minLength: 0)
                Label("继续第 \(entry.index + 1) 节", systemImage: "play.fill")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .frame(minHeight: 44)
                    .background(Theme.board, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .padding(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 20))
            .card()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("继续学习：\(entry.title)，第 \(entry.index + 1) 节 \(entry.part)，已看完 \(seen) 节，共 \(entry.total) 节")
        .accessibilityAddTraits(.isButton)
    }
}

/// 作者、集数、播放量。
struct CourseMeta: View {
    var course: Course
    var body: some View {
        HStack(spacing: 14) {
            Text(course.author).lineLimit(1)
            if course.episodes > 0 { Text("\(course.episodes) 集") }
            Text("\(Formatter.count(course.play)) 次播放")
        }
        .font(.system(size: 13))
        .foregroundStyle(Theme.ink2)
        .lineLimit(1)
    }
}

struct CourseTags: View {
    var course: Course
    var whereTag: String?
    var body: some View {
        HStack(spacing: 6) {
            if let whereTag { Tag(text: whereTag, style: .outline) }
            if let ai = course.ai {
                Tag(text: ai.kind, style: .kind)
                if ai.level != "中等" { Tag(text: ai.level) }
                if ai.fit == "相关" { Tag(text: "可参考") }
            }
            if let e = course.editions.first { Tag(text: e) }
            if course.newTextbook { Tag(text: "新教材", style: .fresh) }
            if course.ai == nil && course.review { Tag(text: "复习") }
        }
    }
}

/// 看完几节：红笔写的数字。
struct WatchedCount: View {
    @Environment(AppModel.self) private var model
    var bvid: String
    var body: some View {
        let seen = model.library.watchedCount(bvid)
        if seen > 0, let total = model.library.historyEntry(bvid)?.total {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text("✓ ").font(.system(size: 13))
                Text("\(seen)").font(Theme.kai(25))
                Text("/\(total)").font(.system(size: 13).monospacedDigit())
            }
            .foregroundStyle(Theme.red)
            .padding(.trailing, 4)
            .accessibilityLabel("已看完 \(seen) 节，共 \(total) 节")
        }
    }
}

extension Course {
    func accessibilityText(withComment: Bool) -> String {
        var parts = [title]
        if let s = ai?.summary, !s.isEmpty { parts.append(s) }
        parts.append(author)
        if episodes > 0 { parts.append("\(episodes) 集") }
        parts.append("时长 \(Formatter.hours(duration))")
        if withComment, let c = ai?.comment, !c.isEmpty { parts.append(c) }
        return parts.joined(separator: "，")
    }

    func snapshot(_ context: CourseContext) -> SavedCourse {
        SavedCourse(bvid: bvid, title: title, cover: cover, author: author, duration: duration,
                    episodes: episodes, grade: context.grade, subject: context.subject, volume: context.volume)
    }
}
