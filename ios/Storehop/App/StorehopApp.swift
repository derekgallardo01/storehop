import SwiftUI
@preconcurrency import FirebaseCore

@main
struct StorehopApp: App {
    @State private var container: AppContainer

    init() {
        // v0.8.1.3 — install runtime language-switch swizzle BEFORE any
        // localized-string lookup happens. Idempotent, and a prerequisite
        // for Settings → Language to update the UI without an app
        // restart on iOS.
        Bundle.installLanguageSwizzle()
        // Apply the persisted language tag synchronously so the very
        // first view render lands in the user's chosen language. Without
        // this the cold-launch first frame flashes English before
        // observePreferences() catches up.
        let storedTag = UserDefaults.standard.string(forKey: "storehop.localeTag") ?? ""
        Bundle.setActiveLanguage(storedTag)

        // E2E UI-test mode: XCUITest launches us with `-UITestE2E` and
        // optionally `-E2ESeedFixtures`. In that mode we skip
        // `FirebaseApp.configure()` (the test target writes a CI-style
        // placeholder plist, but the e2e container uses no-op Firebase
        // stubs so there's no need to initialize the real SDK) and swap
        // in the in-memory test container. Production launches see
        // neither flag and follow the normal Firebase + live path.
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-UITestE2E") {
            let seed = args.contains("-E2ESeedFixtures")
            let withCritical = args.contains("-E2ESeedCriticalFixture")
            let forceTheme: ThemeMode? = {
                if args.contains("-E2EForceLightTheme") { return .light }
                if args.contains("-E2EForceDarkTheme")  { return .dark }
                return nil
            }()
            _container = State(initialValue: AppContainer.e2e(
                seedFixtures: seed,
                seedCriticalCoffee: withCritical,
                forceTheme: forceTheme
            ))
        } else {
            // Firebase must be configured before any Firebase-dependent
            // type is instantiated. `FirebaseApp.configure()` reads the
            // bundled `GoogleService-Info.plist` (gitignored — see
            // ios/README.md).
            FirebaseApp.configure()
            _container = State(initialValue: AppContainer.live())
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
                .task {
                    await container.session.start()
                    await container.syncEngine.start()
                    // v0.8: StoreKit2 connection + transaction listener.
                    // Idempotent; safe to call from the app's .task.
                    if let storeKit = container.storeKitManager {
                        await storeKit.start()
                    }
                    container.entitlementRepository?.start()
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
                    Label(L("nav_shop"), systemImage: "cart")
                }
                .tag(AppTab.shop)

            itemsTab
                .tabItem {
                    Label(L("nav_items"), systemImage: "list.bullet")
                }
                .tag(AppTab.items)
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsView(onDismiss: { showSettings = false })
                    .environment(container)
                    // The Settings sheet is presented modally OUTSIDE the
                    // TabView's view tree, so the `.id(localeTag)` on
                    // TabView below doesn't rebuild it when the locale
                    // flips. Mirror the same trick on the sheet so picking
                    // a new language immediately re-runs every
                    // `String(localized:)` inside Settings against the
                    // swizzled Bundle.
                    .id(localeTag)
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
        // `async let` spins up child tasks that do NOT inherit the
        // surrounding `@MainActor` isolation, so the assignments below
        // were happening off the main actor. SwiftUI silently dropped the
        // @State invalidation, the `.id(localeTag)` on the Settings sheet
        // never flipped, and the open sheet kept showing stale strings
        // until app restart. Explicitly hopping back to the main actor
        // before each assignment is what makes the live rebuild fire.
        async let theme: Void = {
            for await mode in container.userPreferences.themeModeStream {
                await MainActor.run { self.themeMode = mode }
            }
        }()
        async let locale: Void = {
            for await tag in container.userPreferences.localeTagStream {
                // v0.8.1.3: runtime language switching. Setting
                // `AppleLanguages` in UserDefaults alone doesn't refresh
                // Bundle.main's preferred-localization cache, so
                // String(localized:) keeps returning the old language
                // until the next app restart. Routing localized-string
                // lookups through `LanguageBundle` (installed once at
                // app init) means the next view-tree rebuild picks up
                // the new language without a restart.
                //
                // Update the bundle *before* assigning `self.localeTag`
                // so the SwiftUI invalidation triggered by the @State
                // change reads strings from the new `.lproj` on its
                // very first re-evaluation.
                Bundle.setActiveLanguage(tag)
                await MainActor.run { self.localeTag = tag }
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
                        },
                        onEditItem: { itemId in
                            shopPath.append(.editItem(itemId: itemId))
                        }
                    )
                case .editAisles(let storeId):
                    EditAisleOrderView(storeId: storeId)
                case .editItem(let itemId):
                    ItemFormView(itemId: itemId) { shopPath.removeLast() }
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
