import SwiftUI

/// 第一次打开：选年级。在别的设备上用过的，可以直接登录网盘把进度同步过来。
struct WelcomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 36) {
                VStack(alignment: .leading, spacing: 22) {
                    HStack(spacing: 14) {
                        AppLogo(size: 64)
                        Text("随堂").font(Theme.kai(32)).tracking(3)
                    }
                    .accessibilityElement(children: .combine)
                    Text("你在读几年级？").font(Theme.kai(44)).accessibilityAddTraits(.isHeader)
                }
                ForEach(Taxonomy.stages, id: \.name) { stage in
                    VStack(alignment: .leading, spacing: 14) {
                        Text(stage.name).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.ink2).tracking(1.4)
                        HStack(spacing: 14) {
                            ForEach(stage.grades, id: \.self) { g in
                                Button { model.choose(grade: g) } label: {
                                    ZStack {
                                        TianZiGe(surface: .paper)
                                        Text(Taxonomy.gradeShort(g)).font(Theme.kai(g > 9 ? 30 : 42))
                                    }
                                    .frame(width: 84, height: 84)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Taxonomy.gradeName(g))
                            }
                        }
                    }
                }
                HStack(spacing: 12) {
                    Text("之后随时可以在左侧换年级。在别的设备上用过？").foregroundStyle(Theme.ink2)
                    Button("登录网盘，接着上次的进度学") { model.showSyncSheet = true }
                        .fontWeight(.medium).foregroundStyle(Theme.board).underline(color: Theme.grid).frame(minHeight: 44).buttonStyle(.plain)
                }
                .font(.system(size: 14))
            }
            .padding(.horizontal, 120).padding(.vertical, 96)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Theme.paper)
    }
}
