import SwiftUI
import CoreText

/// 随堂的颜色和字体：左侧年级栏是黑板，右侧是纸面。数值和 web/app.css、Android 的 Theme.kt 一致，规范见 designs/brand/README.md。
enum Theme {
    // 黑板
    static let board = Color(hex: 0x1F5C47)
    static let boardDeep = Color(hex: 0x174636)
    static let chalk = Color(hex: 0xF4F0E3)
    static let chalk2 = Color(hex: 0xF4F0E3).opacity(0.74)
    static let chalkLine = Color(hex: 0xF4F0E3).opacity(0.5)
    static let yellow = Color(hex: 0xF4C24D)
    // 纸面
    static let paper = Color(hex: 0xF3F6F3)
    static let card = Color.white
    static let grid = Color(hex: 0xA9CBB9)
    static let ink = Color(hex: 0x1C2B26)
    static let ink2 = Color(hex: 0x52605A)
    static let ink3 = Color(hex: 0x64716B)
    static let rule = Color(hex: 0xE2E8E4)
    static let tint = Color(hex: 0xE5F0EA)
    static let red = Color(hex: 0xC23B2B)
    static let tagBg = Color(hex: 0xEDF2EF)
    static let freshBg = Color(hex: 0xFCEFC7)
    static let freshFg = Color(hex: 0x6E5200)
    static let thumbBg = Color(hex: 0xDFE6E2)
    static let screen = Color(hex: 0x0E1A16)

    static let subjectColors: [String: Color] = [
        "语文": Color(hex: 0xB5443B), "数学": Color(hex: 0x2E64A8), "英语": Color(hex: 0x6B4E9E),
        "物理": Color(hex: 0x1E7F80), "化学": Color(hex: 0x9A5E17), "生物": Color(hex: 0x427A39),
        "历史": Color(hex: 0x875A3D), "地理": Color(hex: 0x33789A), "道德与法治": Color(hex: 0xA23C66),
        "政治": Color(hex: 0xA23C66), "科学": Color(hex: 0x54792A),
    ]

    /// 书脊高度略有错落，像真的一排书。
    static let spineHeights: [CGFloat] = [152, 144, 158, 140, 150, 146, 156, 142, 150]

    static func kai(_ size: CGFloat) -> Font {
        FontLoader.kaiName.map { Font.custom($0, size: size) } ?? .system(size: size, design: .serif)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

extension View {
    /// 白色卡片：大圆角加一层很淡的阴影。
    func card(_ radius: CGFloat = 18) -> some View {
        background(Theme.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .shadow(color: Theme.ink.opacity(0.07), radius: 9, y: 4)
    }
}

/// iPadOS 不预装楷体，向系统按需下载苹果提供的「楷体-简」；下载不了时用系统衬线字体。
enum FontLoader {
    static let candidates = ["STKaitiSC-Regular", "STKaiti"]
    private(set) static var kaiName: String? = candidates.first { UIFont(name: $0, size: 12) != nil }

    static func ensureKai(_ done: @escaping () -> Void) {
        guard kaiName == nil else { return }
        let descriptors = candidates.map { CTFontDescriptorCreateWithAttributes([kCTFontNameAttribute: $0] as CFDictionary) }
        CTFontDescriptorMatchFontDescriptorsWithProgressHandler(descriptors as CFArray, nil) { state, _ in
            if state == .didFinish {
                DispatchQueue.main.async {
                    kaiName = candidates.first { UIFont(name: $0, size: 12) != nil }
                    if kaiName != nil { done() }
                }
            }
            return true
        }
    }
}

/// 随堂的标：田字格里一个播放键。黑板上直接用；纸面上放进黑板绿的圆角方块（见 AppLogo）。
struct BrandMark: View {
    var body: some View {
        Canvas { ctx, size in
            let s = size.width / 64
            ctx.stroke(Path(roundedRect: CGRect(x: 4 * s, y: 4 * s, width: 56 * s, height: 56 * s), cornerRadius: 1.5 * s),
                       with: .color(Theme.chalk), lineWidth: 3 * s)
            var cross = Path()
            cross.move(to: CGPoint(x: 32 * s, y: 8.5 * s)); cross.addLine(to: CGPoint(x: 32 * s, y: 55.5 * s))
            cross.move(to: CGPoint(x: 8.5 * s, y: 32 * s)); cross.addLine(to: CGPoint(x: 55.5 * s, y: 32 * s))
            ctx.stroke(cross, with: .color(Theme.chalk.opacity(0.55)),
                       style: StrokeStyle(lineWidth: 1.4 * s, lineCap: .round, dash: [2.5 * s, 2.3 * s]))
            var play = Path()
            play.move(to: CGPoint(x: 26.2 * s, y: 21.2 * s)); play.addLine(to: CGPoint(x: 26.2 * s, y: 42.8 * s))
            play.addLine(to: CGPoint(x: 44.9 * s, y: 32 * s)); play.closeSubpath()
            ctx.fill(play, with: .color(Theme.yellow))
            ctx.stroke(play, with: .color(Theme.yellow), style: StrokeStyle(lineWidth: 5 * s, lineJoin: .round))
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// 和 App 图标一样的完整标志：黑板绿圆角方块里放 BrandMark。
struct AppLogo: View {
    var size: CGFloat = 72
    var body: some View {
        BrandMark()
            .padding(size * 0.177)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Color(hex: 0x23674F), Color(hex: 0x174836)], startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: size * 0.235, style: .continuous))
            .shadow(color: Theme.ink.opacity(0.12), radius: 8, y: 4)
    }
}

/// 田字格：实线外框，中间一横一竖两条虚线。黑板上用粉笔线，当前年级填粉笔黄；纸面上用浅绿线。
struct TianZiGe: View {
    enum Surface { case board, paper }
    var surface: Surface = .board
    var on = false

    var body: some View {
        let line: Color = surface == .paper ? Theme.grid : (on ? Theme.boardDeep.opacity(0.22) : Theme.chalkLine)
        let fill: Color = surface == .paper ? .white : (on ? Theme.yellow : .clear)
        let border: Color = surface == .paper ? Theme.grid : (on ? Theme.yellow : Theme.chalkLine)
        ZStack {
            RoundedRectangle(cornerRadius: 3).fill(fill)
            GeometryReader { g in
                Path { p in
                    p.move(to: CGPoint(x: 0, y: g.size.height / 2)); p.addLine(to: CGPoint(x: g.size.width, y: g.size.height / 2))
                    p.move(to: CGPoint(x: g.size.width / 2, y: 0)); p.addLine(to: CGPoint(x: g.size.width / 2, y: g.size.height))
                }
                .stroke(line, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
            RoundedRectangle(cornerRadius: 3).strokeBorder(border, lineWidth: 1.5)
        }
    }
}

/// 当前学科书脊顶上夹的书签条。
struct Ribbon: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.midX, y: r.minY + r.height * 0.8))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY)); p.closeSubpath()
        return p
    }
}

/// 进度条：圆头，黑板绿填充；放在封面上时用粉笔黄。
struct ProgressBar: View {
    var value: Double
    var fill: Color = Theme.board
    var track: Color = Theme.tint
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                if value > 0 { Capsule().fill(fill).frame(width: max(height, g.size.width * min(1, value))) }
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

struct Chip: View {
    var title: String
    var count: Int?
    var on: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(title)
                if let count { Text("\(count)").foregroundStyle(on ? Theme.chalk2 : Theme.ink3).font(.system(size: 13)) }
            }
            .font(.system(size: 14))
            .foregroundStyle(on ? Color.white : Theme.ink2)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(on ? Theme.board : Color.white, in: Capsule())
            .overlay(Capsule().strokeBorder(on ? Theme.board : Theme.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct Tag: View {
    enum Style { case plain, kind, fresh, outline }
    var text: String
    var style: Style = .plain

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: style == .kind ? .medium : .regular))
            .padding(.horizontal, 8).padding(.vertical, 5)
            .foregroundStyle(style == .fresh ? Theme.freshFg : (style == .kind ? Theme.board : Theme.ink2))
            .background(style == .fresh ? Theme.freshBg : (style == .kind ? Theme.tint : (style == .outline ? .clear : Theme.tagBg)),
                        in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(style == .outline ? Theme.rule : .clear))
    }
}

struct BookmarkIcon: View {
    var filled: Bool
    var body: some View {
        Image(systemName: filled ? "bookmark.fill" : "bookmark")
            .font(.system(size: 19))
            .foregroundStyle(filled ? Theme.red : Theme.ink3)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
    }
}

struct OutlineButtonStyle: ButtonStyle {
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(primary ? Color.white : Theme.ink)
            .padding(.horizontal, 20)
            .frame(minHeight: 44)
            .background(primary ? Theme.board : Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(primary ? Theme.board : Theme.rule, lineWidth: 1.5))
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}
