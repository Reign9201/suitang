import SwiftUI

@main
struct BiliStudyApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .id(model.fontVersion) // 楷体下载好后重绘
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.sync.syncIfStale() }
                }
                #if DEBUG
                .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { AppModel.debugRotate() } }
                #endif
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.grade == nil {
                WelcomeView()
            } else {
                NavigationStack(path: $model.path) {
                    HStack(spacing: 0) {
                        Rail()
                        Group {
                            switch model.section {
                            case .catalog: CatalogView()
                            case .mine: MineView()
                            case .settings: SettingsView()
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .background(Theme.paper)
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .player(let bvid, let context): PlayerView(bvid: bvid, context: context).toolbar(.hidden, for: .navigationBar)
                        case .search(let q): SearchView(initial: q).toolbar(.hidden, for: .navigationBar)
                        }
                    }
                }
            }
        }
        .foregroundStyle(Theme.ink)
        .tint(Theme.board)
        .sheet(isPresented: $model.showSyncSheet) {
            NavigationStack { SyncForm(onDone: { model.showSyncSheet = false }).padding(24) }
        }
    }
}

/// 左侧黑板：随堂的标、田字格年级（当前年级填粉笔黄）、我的、设置。
struct Rail: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Button { model.section = .catalog } label: {
                HStack(spacing: 10) {
                    BrandMark().frame(width: 36, height: 36)
                    Text("随堂").font(Theme.kai(30)).tracking(2)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("随堂 首页")
            ForEach(Taxonomy.stages, id: \.name) { stage in
                VStack(alignment: .leading, spacing: 10) {
                    Text(stage.name).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.chalk2).tracking(1.5)
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(46), spacing: 9), count: 3), alignment: .leading, spacing: 9) {
                        ForEach(stage.grades, id: \.self) { g in
                            let on = model.section == .catalog && model.grade == g
                            Button { model.choose(grade: g) } label: {
                                ZStack {
                                    TianZiGe(on: on)
                                    Text(Taxonomy.gradeShort(g))
                                        .font(Theme.kai(g > 9 ? 17 : 24))
                                        .foregroundStyle(on ? Theme.boardDeep : Theme.chalk)
                                }
                                .frame(width: 46, height: 46)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Taxonomy.gradeName(g))
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 4) {
                railLink("我的", icon: "bookmark", onIcon: "bookmark.fill", section: .mine)
                railLink("设置", icon: "slider.horizontal.3", onIcon: "slider.horizontal.3", section: .settings)
            }
        }
        .foregroundStyle(Theme.chalk)
        .padding(EdgeInsets(top: 26, leading: 22, bottom: 20, trailing: 22))
        .frame(width: 200, alignment: .leading)
        .frame(maxHeight: .infinity, alignment: .top)
        .background {
            ZStack {
                Theme.board
                RadialGradient(colors: [Color.white.opacity(0.07), .clear], center: UnitPoint(x: 0.2, y: 0), startRadius: 0, endRadius: 420)
            }
            .ignoresSafeArea()
        }
    }

    private func railLink(_ title: String, icon: String, onIcon: String, section: Section) -> some View {
        let on = model.section == section
        return Button { model.section = section } label: {
            HStack(spacing: 10) {
                Image(systemName: on ? onIcon : icon).font(.system(size: 17)).foregroundStyle(on ? Theme.yellow : Theme.chalk2)
                Text(title).font(.system(size: 15, weight: on ? .semibold : .regular))
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            .foregroundStyle(Theme.chalk)
            .background(on ? Color.white.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, -12)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
