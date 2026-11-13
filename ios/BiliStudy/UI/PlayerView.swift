import SwiftUI
import AVKit
import Observation

@MainActor
@Observable
final class PlayerModel {
    var detail: CourseDetail?
    var error: String?
    var index = 0
    var quality = ""
    var speed: Float = UserDefaults.standard.object(forKey: "player.speed") as? Float ?? 1
    @ObservationIgnored let player = AVPlayer()
    @ObservationIgnored private var observer: Any?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored weak var library: LibraryStore?
    @ObservationIgnored var context = CourseContext()
    @ObservationIgnored private var loadToken = 0

    var lesson: Lesson? { detail.flatMap { index < $0.lessons.count ? $0.lessons[index] : nil } }

    func load(bvid: String) async {
        do {
            let d = try await BiliClient.shared.detail(bvid)
            detail = d
            let last = library?.historyEntry(bvid)?.lesson
            await select(d.lessons.firstIndex { $0.key == last } ?? 0)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func select(_ i: Int) async {
        guard let detail, detail.lessons.indices.contains(i) else { return }
        saveProgress()
        index = i
        let lesson = detail.lessons[i]
        library?.record(HistoryEntry(bvid: detail.bvid, title: detail.displayTitle, cover: detail.cover, author: detail.author,
                                     lesson: lesson.key, index: i, part: lesson.part, total: detail.lessons.count,
                                     grade: context.grade, subject: context.subject, volume: context.volume))
        loadToken += 1
        let token = loadToken
        do {
            let src = try await BiliClient.shared.playSource(bvid: lesson.bvid, cid: lesson.cid)
            guard token == loadToken else { return }
            quality = src.quality
            error = nil
            // B 站视频服务器要求带 B 站的 Referer 和桌面浏览器的 User-Agent
            let asset = AVURLAsset(url: src.url, options: ["AVURLAssetHTTPHeaderFieldsKey": src.headers])
            let item = AVPlayerItem(asset: asset)
            player.replaceCurrentItem(with: item)
            if let p = library?.progress(detail.bvid, lesson.key), p.position > 5, p.position < p.duration - 10 {
                await player.seek(to: CMTime(seconds: p.position, preferredTimescale: 600))
            }
            player.defaultRate = speed
            player.playImmediately(atRate: speed)
            observe(item: item)
        } catch {
            guard token == loadToken else { return }
            self.error = error.localizedDescription
        }
    }

    private func observe(item: AVPlayerItem) {
        if let observer { player.removeTimeObserver(observer) }
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.saveProgress() }
        }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let d = self.detail, let l = self.lesson else { return }
                self.library?.markWatched(d.bvid, l.key)
                self.library?.setProgress(d.bvid, l.key, position: 0, duration: 0)
                if self.index + 1 < d.lessons.count { Task { await self.select(self.index + 1) } }
            }
        }
    }

    func saveProgress() {
        guard let d = detail, let l = lesson, let item = player.currentItem else { return }
        let pos = player.currentTime().seconds, dur = item.duration.seconds
        guard pos.isFinite, dur.isFinite, dur > 0, pos > 1 else { return }
        library?.setProgress(d.bvid, l.key, position: pos, duration: dur)
        if pos / dur > 0.9 { library?.markWatched(d.bvid, l.key) } // 看过 90% 算看完
    }

    func cycleSpeed() {
        let all: [Float] = [1, 1.25, 1.5, 2]
        speed = all[((all.firstIndex(of: speed) ?? 0) + 1) % all.count]
        UserDefaults.standard.set(speed, forKey: "player.speed")
        player.defaultRate = speed
        if player.rate > 0 { player.rate = speed }
    }

    func stop() {
        saveProgress()
        player.pause()
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
    }
}

/// 分集标题里的「1.2.1」「第一单元」「Unit 3」→ 按章分组；能认出章节的不到一半时不分组。
enum Chapters {
    private static let cnNum = Array("零一二三四五六七八九十")

    static func label(_ part: String) -> String? {
        if let m = part.range(of: #"第\s*([一二三四五六七八九十]+|\d+)\s*(章|单元|模块|专题)"#, options: .regularExpression) {
            return String(part[m]).replacingOccurrences(of: " ", with: "")
        }
        if let m = part.range(of: #"^\s*(Unit|UNIT|unit)\s*\d+"#, options: .regularExpression) {
            return "Unit " + part[m].filter(\.isNumber)
        }
        if let m = part.range(of: #"^\s*(?:P\d+[\s._-]+|\d+[\s、_-]+)?\d{1,2}\.\d+"#, options: .regularExpression) {
            let head = part[m].replacingOccurrences(of: #"^\s*(?:P\d+[\s._-]+|\d+[\s、_-]+)?"#, with: "", options: .regularExpression)
            if let n = Int(head.split(separator: ".").first ?? "") { return n <= 10 ? "第\(cnNum[n])章" : "第\(n)章" }
        }
        return nil
    }

    static func group(_ lessons: [Lesson]) -> [(label: String?, items: [Int])] {
        var groups: [(label: String?, items: [Int])] = []
        var labelled = 0
        for (i, l) in lessons.enumerated() {
            let lab = label(l.part)
            if lab != nil { labelled += 1 }
            if groups.isEmpty || (lab != nil && lab != groups[groups.count - 1].label) { groups.append((lab, [])) }
            groups[groups.count - 1].items.append(i)
        }
        if Double(labelled) < Double(lessons.count) * 0.5 || groups.filter({ $0.label != nil }).count < 2 {
            return [(nil, Array(lessons.indices))]
        }
        return groups
    }
}

struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var bvid: String
    var context: CourseContext
    @State private var pm = PlayerModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { dismiss() } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.left")
                    Text(backTitle)
                }
                .font(.system(size: 15)).foregroundStyle(Theme.ink2)
                .frame(minHeight: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            HStack(alignment: .top, spacing: 24) {
                VStack(alignment: .leading, spacing: 0) {
                    VideoPlayer(player: pm.player)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .background(Theme.screen)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .shadow(color: Theme.ink.opacity(0.08), radius: 9, y: 4)
                    info
                    Spacer(minLength: 0)
                }
                toc.frame(width: 350)
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 28).padding(.top, 16).padding(.bottom, 20)
        .background(Theme.paper)
        .task {
            pm.library = model.library
            pm.context = context
            await pm.load(bvid: bvid)
        }
        .onDisappear {
            pm.stop()
            model.sync.schedule(after: 0.5) // 退出播放页时同步进度
        }
    }

    private var backTitle: String {
        if let g = context.grade, let s = context.subject { return "\(Taxonomy.gradeName(g)) \(s)" }
        return "返回"
    }

    @ViewBuilder private var info: some View {
        if let d = pm.detail, let lesson = pm.lesson {
            let saved = model.library.isFavorite(d.bvid)
            Text("第 \(pm.index + 1) 节，共 \(d.lessons.count) 节").font(.system(size: 13)).foregroundStyle(Theme.ink3).padding(.top, 16)
            Text(lesson.part).font(Theme.kai(30)).padding(.top, 2).padding(.bottom, 8)
            Text(d.displayTitle).font(.system(size: 14)).foregroundStyle(Theme.ink2).lineLimit(1)
            HStack(spacing: 16) {
                Text(d.author)
                Text("\(d.lessons.count) 节")
                Text(Formatter.hours(d.lessons.reduce(0) { $0 + $1.duration }))
                Text("\(Formatter.count(d.play)) 次播放")
                if !pm.quality.isEmpty { Text(pm.quality) }
            }
            .font(.system(size: 13)).foregroundStyle(Theme.ink3).padding(.top, 4)
            HStack(spacing: 10) {
                Button("上一节") { Task { await pm.select(pm.index - 1) } }
                    .buttonStyle(OutlineButtonStyle()).disabled(pm.index == 0).opacity(pm.index == 0 ? 0.35 : 1)
                Button("下一节") { Task { await pm.select(pm.index + 1) } }
                    .buttonStyle(OutlineButtonStyle(primary: true)).disabled(pm.index + 1 >= d.lessons.count)
                Button("\(String(format: pm.speed == 1 || pm.speed == 2 ? "%.0f" : "%.2g", pm.speed))×") { pm.cycleSpeed() }
                    .buttonStyle(OutlineButtonStyle())
                    .accessibilityLabel("播放速度 \(pm.speed) 倍")
                Button {
                    model.library.toggleFavorite(SavedCourse(bvid: d.bvid, title: d.displayTitle, cover: d.cover, author: d.author,
                                                             duration: d.lessons.reduce(0) { $0 + $1.duration }, episodes: d.lessons.count,
                                                             grade: context.grade, subject: context.subject, volume: context.volume))
                } label: {
                    Label(saved ? "已收藏" : "收藏", systemImage: saved ? "bookmark.fill" : "bookmark")
                }
                .buttonStyle(OutlineButtonStyle())
                .foregroundStyle(saved ? Theme.red : Theme.ink)
                .accessibilityLabel(saved ? "取消收藏" : "收藏这门课")
            }
            .padding(.top, 18)
            if let e = pm.error { Text(e).font(.system(size: 13)).foregroundStyle(Theme.red).padding(.top, 10) }
        } else if let e = pm.error {
            Text(e).foregroundStyle(Theme.ink2).padding(.top, 16)
        } else {
            ProgressView().padding(.top, 24)
        }
    }

    @ViewBuilder private var toc: some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("目录").font(Theme.kai(21))
                Spacer()
                if let d = pm.detail {
                    Text("已看完 \(model.library.watchedCount(d.bvid)) / \(d.lessons.count)")
                        .font(.system(size: 13).monospacedDigit()).foregroundStyle(Theme.red)
                }
            }
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 12, trailing: 16))
            Rectangle().fill(Theme.rule).frame(height: 1)
            if let d = pm.detail {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(Chapters.group(d.lessons).enumerated()), id: \.offset) { _, group in
                                if let label = group.label {
                                    Text(label).font(Theme.kai(16)).foregroundStyle(Theme.ink2)
                                        .padding(EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: 16))
                                        .accessibilityAddTraits(.isHeader)
                                }
                                ForEach(group.items, id: \.self) { i in lessonRow(d, i).id(i) }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .onChange(of: pm.index) { _, i in withAnimation { proxy.scrollTo(i, anchor: .center) } }
                }
            } else {
                Spacer()
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .card()
    }

    private func lessonRow(_ d: CourseDetail, _ i: Int) -> some View {
        let l = d.lessons[i]
        let on = i == pm.index
        let done = model.library.isWatched(d.bvid, l.key)
        return Button { Task { await pm.select(i) } } label: {
            HStack(spacing: 8) {
                Text("\(i + 1)").font(.system(size: 12).monospacedDigit()).foregroundStyle(Theme.ink3).frame(width: 24, alignment: .trailing)
                Text(l.part).font(.system(size: 14, weight: on ? .semibold : .regular)).foregroundStyle(on ? Theme.boardDeep : Theme.ink).lineLimit(1)
                Spacer(minLength: 4)
                if l.duration > 0 { Text(Formatter.duration(l.duration)).font(.system(size: 12).monospacedDigit()).foregroundStyle(Theme.ink3) }
                Text(done ? "✓" : " ").font(Theme.kai(16)).foregroundStyle(Theme.red).frame(width: 16)
            }
            .padding(.leading, 11).padding(.trailing, 14)
            .frame(minHeight: 44)
            .background(on ? Theme.tint : .clear)
            .overlay(alignment: .leading) { Rectangle().fill(on ? Theme.board : .clear).frame(width: 3) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("第 \(i + 1) 节，\(l.part)\(done ? "，已看完" : "")")
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
