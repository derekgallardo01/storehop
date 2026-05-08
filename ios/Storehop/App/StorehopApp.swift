import SwiftUI
import FirebaseCore

@main
struct StorehopApp: App {
    @State private var container: AppContainer

    init() {
        // Firebase must be configured before any Firebase-dependent type
        // is instantiated. `FirebaseApp.configure()` reads the bundled
        // `GoogleService-Info.plist` (gitignored — see ios/README.md).
        FirebaseApp.configure()
        _container = State(initialValue: AppContainer.live())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
                .task {
                    await container.session.start()
                    await container.syncEngine.start()
                }
        }
    }
}

/// Two-tab shell with Shop + Items, plus a sheet-presented Settings entered
/// from each tab's top bar. NavigationStack lives inside each tab so
/// pushing into Shop-at-Store from the Shop picker keeps the tab bar
/// visible (matches Android's nested-route pattern).
struct RootView: View {
    @Environment(AppContainer.self) private var container
    @State private var selectedTab: AppTab = .shop
    @State private var shopPath: [ShopRoute] = []
    @State private var itemsPath: [ItemsRoute] = []
    @State private var showSettings = false
    @State private var themeMode: ThemeMode = .system
    @State private var localeTag: String = ""

    var body: some View {
        TabView(selection: $selectedTab) {
            shopTab
                .tabItem {
                    Label(String(localized: "nav_shop"), systemImage: "cart")
                }
                .tag(AppTab.shop)

            itemsTab
                .tabItem {
                    Label(String(localized: "nav_items"), systemImage: "list.bullet")
                }
                .tag(AppTab.items)
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsView(onDismiss: { showSettings = false })
                    .environment(container)
            }
            .preferredColorScheme(themeMode.preferredColorScheme)
            .environment(\.locale, currentLocale)
        }
        .tint(StorehopColors.primary)
        .preferredColorScheme(themeMode.preferredColorScheme)
        // `.id(localeTag)` rebuilds the entire tree when the language flips,
        // so already-rendered Text/String(localized:) lookups pick up the
        // new bundle. `Bundle.main.preferredLocalizations` re-reads
        // `AppleLanguages` on each lookup, so the rebuild is enough.
        .id(localeTag)
        .environment(\.locale, currentLocale)
        .task {
            await observePreferences()
        }
    }

    private var currentLocale: Locale {
        localeTag.isEmpty ? .autoupdatingCurrent : Locale(identifier: localeTag)
    }

    private func observePreferences() async {
        async let theme: Void = {
            for await mode in container.userPreferences.themeModeStream {
                self.themeMode = mode
            }
        }()
        async let locale: Void = {
            for await tag in container.userPreferences.localeTagStream {
                self.localeTag = tag
            }
        }()
        _ = await (theme, locale)
    }

    private var shopTab: some View {
        NavigationStack(path: $shopPath) {
            StorePickerView(
                onPickStore: { storeId in
                    shopPath.append(.shopAtStore(storeId: storeId))
                },
                onEditAisles: { storeId in
                    shopPath.append(.editAisles(storeId: storeId))
                },
                onOpenSettings: { showSettings = true }
            )
            .navigationDestination(for: ShopRoute.self) { route in
                switch route {
                case .shopAtStore(let storeId):
                    ShopAtStoreView(
                        storeId: storeId,
                        onEditAisles: {
                            shopPath.append(.editAisles(storeId: storeId))
                        }
                    )
                case .editAisles(let storeId):
                    EditAisleOrderView(storeId: storeId)
                }
            }
        }
    }

    private var itemsTab: some View {
        NavigationStack(path: $itemsPath) {
            ItemsListView(
                onAddItem: { itemsPath.append(.addItem) },
                onEditItem: { id in itemsPath.append(.editItem(itemId: id)) },
                onManageCategories: { itemsPath.append(.manageCategories) },
                onOpenSettings: { showSettings = true }
            )
            .navigationDestination(for: ItemsRoute.self) { route in
                switch route {
                case .manageCategories:
                    ManageCategoriesView()
                case .addItem:
                    ItemFormView(itemId: nil) { itemsPath.removeLast() }
                case .editItem(let itemId):
                    ItemFormView(itemId: itemId) { itemsPath.removeLast() }
                }
            }
        }
    }
}

#Preview {
    RootView()
        .environment(AppContainer.preview())
}
