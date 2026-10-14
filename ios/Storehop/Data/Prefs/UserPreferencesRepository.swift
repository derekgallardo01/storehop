import Foundation

/// Stores theme + locale prefs in UserDefaults. Mirrors Android
/// `UserPreferencesRepository` (DataStore-backed there). Locale on iOS is
/// also written to `AppleLanguages` so `Bundle.main.preferredLocalizations`
/// honors the override on the next view-tree rebuild.
///
/// v0.7.1: every setter now bumps `updatedAt` so the cloud-sync layer
/// ([UserPreferencesSync]) can run last-write-wins against the Firestore
/// `/userPrefs/{uid}` doc. The new `snapshot` accessor + `snapshotStream`
/// re-emit on any change so the sync layer can debounce-push.
///
/// Backed by UserDefaults — small enough that persistence is synchronous
/// and the read API can stay async-free (which keeps the SwiftUI binding
/// surface simple).

/// How a list of items should be ordered. Mirrors Android's
/// `com.storehop.app.data.prefs.SortMode`. Each screen with a sort
/// toggle holds its own preference (in-store list and Items list are
/// independent).
enum SortMode: String, Sendable {
    case category = "CATEGORY"
    case alphabetic = "ALPHABETIC"

    static func fromName(_ name: String?) -> SortMode? {
        guard let name = name else { return nil }
        return SortMode(rawValue: name)
    }
}

/// Which screen appears on app launch. Mirrors Android's
/// `com.storehop.app.data.prefs.StartScreen`.
enum StartScreen: String, Sendable {
    case shop = "SHOP"
    case items = "ITEMS"

    static func fromName(_ name: String?) -> StartScreen? {
        guard let name = name else { return nil }
        return StartScreen(rawValue: name)
    }
}

/// Aggregated user preferences DTO used by [UserPreferencesSync] to ferry
/// values between local UserDefaults and `/userPrefs/{uid}` in Firestore.
///
/// `updatedAt` is the LWW arbiter: cloud writes only land locally if their
/// `updatedAt` exceeds the local snapshot's, and local writes only push
/// to cloud if local is newer than (or equal-to-absent from) the remote
/// doc. Mirrors Android's `UserPreferencesSnapshot`.
struct UserPreferencesSnapshot: Equatable, Sendable {
    let themeMode: ThemeMode
    let localeTag: String
    let showPurchased: Bool
    let shopAtStoreSortMode: SortMode
    let itemsListSortMode: SortMode
    let itemsListShowOnlyNeeded: Bool
    let startScreen: StartScreen
    let focusSearchOnStart: Bool
    let updatedAt: Int64

    init(
        themeMode: ThemeMode,
        localeTag: String,
        showPurchased: Bool,
        shopAtStoreSortMode: SortMode,
        itemsListSortMode: SortMode,
        itemsListShowOnlyNeeded: Bool = false,
        startScreen: StartScreen = .shop,
        focusSearchOnStart: Bool = true,
        updatedAt: Int64
    ) {
        self.themeMode = themeMode
        self.localeTag = localeTag
        self.showPurchased = showPurchased
        self.shopAtStoreSortMode = shopAtStoreSortMode
        self.itemsListSortMode = itemsListSortMode
        self.itemsListShowOnlyNeeded = itemsListShowOnlyNeeded
        self.startScreen = startScreen
        self.focusSearchOnStart = focusSearchOnStart
        self.updatedAt = updatedAt
    }
}

protocol UserPreferencesRepository: Sendable {
    var themeMode: ThemeMode { get }

    /// Empty string = follow system. "en" / "pt-PT" otherwise.
    var localeTag: String { get }

    /// Whether checked-off rows (any `!isNeeded` row, staple or not)
    /// should remain visible struck-through on the Shop-at-Store screen.
    /// Default is true to preserve historical behavior.
    var showPurchased: Bool { get }

    /// Sort mode for the in-store list (ShopAtStoreView). Default
    /// `.category` preserves the historical aisle-grouped layout.
    /// Per-screen scope -- one preference applies to every store.
    var shopAtStoreSortMode: SortMode { get }

    /// Sort mode for the master Items list. Default `.alphabetic`
    /// preserves the historical flat-alphabetic layout.
    var itemsListSortMode: SortMode { get }

    /// Whether the master Items list is filtered to show only items currently
    /// needed at one or more stores (`neededItemIds`). Default is false.
    var itemsListShowOnlyNeeded: Bool { get }

    /// Which screen appears on app launch. Default `.shop`.
    var startScreen: StartScreen { get }

    /// Whether to auto-focus search on launch when startScreen == .items.
    /// Default true.
    var focusSearchOnStart: Bool { get }

    /// LWW timestamp for the cloud-sync layer. Bumped to `clock.nowMs()`
    /// on every setter call. 0 if no setter has ever run on this device.
    var updatedAt: Int64 { get }

    /// Aggregated snapshot of every cloud-syncable preference field
    /// plus [updatedAt]. Used by [UserPreferencesSync] to push to
    /// Firestore.
    var snapshot: UserPreferencesSnapshot { get }

    func setThemeMode(_ mode: ThemeMode)
    func setLocaleTag(_ tag: String)
    func setShowPurchased(_ value: Bool)
    func setShopAtStoreSortMode(_ mode: SortMode)
    func setItemsListSortMode(_ mode: SortMode)
    func setItemsListShowOnlyNeeded(_ value: Bool)
    func setStartScreen(_ mode: StartScreen)
    func setFocusSearchOnStart(_ value: Bool)

    /// Atomically write every field from a cloud-pulled snapshot.
    /// Preserves the cloud's [UserPreferencesSnapshot.updatedAt] so LWW
    /// doesn't re-trigger a push on the next observation tick.
    func applyRemoteSnapshot(_ s: UserPreferencesSnapshot)

    /// Stream of theme-mode changes for the SettingsView to redraw the
    /// radio selection without polling. Emits the current value on
    /// subscription.
    var themeModeStream: AsyncStream<ThemeMode> { get }
    var localeTagStream: AsyncStream<String> { get }
    var showPurchasedStream: AsyncStream<Bool> { get }
    var shopAtStoreSortModeStream: AsyncStream<SortMode> { get }
    var itemsListSortModeStream: AsyncStream<SortMode> { get }
    var itemsListShowOnlyNeededStream: AsyncStream<Bool> { get }
    var startScreenStream: AsyncStream<StartScreen> { get }
    var focusSearchOnStartStream: AsyncStream<Bool> { get }

    /// Re-emits the aggregated [snapshot] whenever any field changes.
    /// [UserPreferencesSync] subscribes to this to debounce-push changes
    /// to Firestore.
    var snapshotStream: AsyncStream<UserPreferencesSnapshot> { get }

    // ---- v0.8 Premium entitlement state (LOCAL-ONLY, never cloud-synced) -
    //
    // Per Apple / Google IAP policy, entitlements are per-platform and
    // per-device. These three keys back the EntitlementRepository
    // grandfather logic + fast-startup cache. They are deliberately
    // **NOT** included in [snapshot] above and therefore never reach
    // the /userPrefs/{uid} Firestore doc.

    /// True if this device has been granted the legacy_user
    /// entitlement (Firebase account creationDate predated v0.8 OR
    /// email is in PREMIUM_VIP_EMAILS).
    var legacyUserGranted: Bool { get }
    func setLegacyUserGranted(_ value: Bool)

    /// Which uid we've already run the grandfather check for. Prevents
    /// re-running on every uid emission.
    var legacyCheckDoneForUid: String { get }
    func setLegacyCheckDoneForUid(_ uid: String)

    /// Last-known Entitlement.cacheString. Persisted so cold launch
    /// starts in the right state instead of flickering through
    /// .notEntitled while StoreKit's first scan settles.
    var cachedEntitlement: String { get }
    func setCachedEntitlement(_ value: String)
}

final class LiveUserPreferencesRepository: UserPreferencesRepository, @unchecked Sendable {
    private let defaults: UserDefaults
    private let clock: any Clock
    private let lock = NSLock()
    private var themeModeContinuations: [UUID: AsyncStream<ThemeMode>.Continuation] = [:]
    private var localeContinuations: [UUID: AsyncStream<String>.Continuation] = [:]
    private var showPurchasedContinuations: [UUID: AsyncStream<Bool>.Continuation] = [:]
    private var shopSortModeContinuations: [UUID: AsyncStream<SortMode>.Continuation] = [:]
    private var itemsSortModeContinuations: [UUID: AsyncStream<SortMode>.Continuation] = [:]
    private var itemsShowOnlyNeededContinuations: [UUID: AsyncStream<Bool>.Continuation] = [:]
    private var startScreenContinuations: [UUID: AsyncStream<StartScreen>.Continuation] = [:]
    private var focusSearchOnStartContinuations: [UUID: AsyncStream<Bool>.Continuation] = [:]
    private var snapshotContinuations: [UUID: AsyncStream<UserPreferencesSnapshot>.Continuation] = [:]

    private static let themeModeKey = "storehop.themeMode"
    private static let localeTagKey = "storehop.localeTag"
    private static let appleLanguagesKey = "AppleLanguages"
    // Mirrors Android's DataStore key names so a developer reading either
    // platform sees the same identifier.
    private static let showPurchasedKey = "shop_show_purchased"
    private static let shopAtStoreSortModeKey = "shop_at_store_sort_mode"
    private static let itemsListSortModeKey = "items_list_sort_mode"
    private static let itemsListShowOnlyNeededKey = "items_list_show_only_needed"
    private static let startScreenKey = "start_screen"
    private static let focusSearchOnStartKey = "focus_search_on_start"
    private static let updatedAtKey = "user_prefs_updated_at"
    // v0.8 entitlement keys (local-only, never cloud-synced).
    private static let legacyUserGrantedKey = "legacy_user_granted"
    private static let legacyCheckDoneForUidKey = "legacy_check_done_for_uid"
    private static let cachedEntitlementKey = "cached_entitlement"

    init(defaults: UserDefaults = .standard, clock: any Clock = SystemClock()) {
        self.defaults = defaults
        self.clock = clock
    }

    var themeMode: ThemeMode {
        ThemeMode.fromName(defaults.string(forKey: Self.themeModeKey))
    }

    var localeTag: String {
        defaults.string(forKey: Self.localeTagKey) ?? ""
    }

    var showPurchased: Bool {
        // Default true (preserve historical visibility) when the key is
        // absent. UserDefaults.bool returns false for missing keys, so we
        // can't use it directly.
        defaults.object(forKey: Self.showPurchasedKey) as? Bool ?? true
    }

    var shopAtStoreSortMode: SortMode {
        SortMode.fromName(defaults.string(forKey: Self.shopAtStoreSortModeKey)) ?? .category
    }

    var itemsListSortMode: SortMode {
        SortMode.fromName(defaults.string(forKey: Self.itemsListSortModeKey)) ?? .alphabetic
    }

    var itemsListShowOnlyNeeded: Bool {
        defaults.object(forKey: Self.itemsListShowOnlyNeededKey) as? Bool ?? false
    }

    var startScreen: StartScreen {
        StartScreen.fromName(defaults.string(forKey: Self.startScreenKey)) ?? .shop
    }

    var focusSearchOnStart: Bool {
        defaults.object(forKey: Self.focusSearchOnStartKey) as? Bool ?? true
    }

    var updatedAt: Int64 {
        // UserDefaults stores Int64 as NSNumber. Cast via the Number
        // bridge so we don't lose precision on values > Int32.max.
        (defaults.object(forKey: Self.updatedAtKey) as? NSNumber)?.int64Value ?? 0
    }

    var snapshot: UserPreferencesSnapshot {
        UserPreferencesSnapshot(
            themeMode: themeMode,
            localeTag: localeTag,
            showPurchased: showPurchased,
            shopAtStoreSortMode: shopAtStoreSortMode,
            itemsListSortMode: itemsListSortMode,
            itemsListShowOnlyNeeded: itemsListShowOnlyNeeded,
            startScreen: startScreen,
            focusSearchOnStart: focusSearchOnStart,
            updatedAt: updatedAt
        )
    }

    func setThemeMode(_ mode: ThemeMode) {
        defaults.set(mode.rawValue, forKey: Self.themeModeKey)
        bumpUpdatedAt()
        broadcast(themeMode: mode)
        broadcastSnapshot()
    }

    func setShowPurchased(_ value: Bool) {
        defaults.set(value, forKey: Self.showPurchasedKey)
        bumpUpdatedAt()
        broadcast(showPurchased: value)
        broadcastSnapshot()
    }

    func setShopAtStoreSortMode(_ mode: SortMode) {
        defaults.set(mode.rawValue, forKey: Self.shopAtStoreSortModeKey)
        bumpUpdatedAt()
        broadcast(shopAtStoreSortMode: mode)
        broadcastSnapshot()
    }

    func setItemsListSortMode(_ mode: SortMode) {
        defaults.set(mode.rawValue, forKey: Self.itemsListSortModeKey)
        bumpUpdatedAt()
        broadcast(itemsListSortMode: mode)
        broadcastSnapshot()
    }

    func setItemsListShowOnlyNeeded(_ value: Bool) {
        defaults.set(value, forKey: Self.itemsListShowOnlyNeededKey)
        bumpUpdatedAt()
        broadcast(itemsListShowOnlyNeeded: value)
        broadcastSnapshot()
    }

    func setStartScreen(_ mode: StartScreen) {
        defaults.set(mode.rawValue, forKey: Self.startScreenKey)
        bumpUpdatedAt()
        broadcast(startScreen: mode)
        broadcastSnapshot()
    }

    func setFocusSearchOnStart(_ value: Bool) {
        defaults.set(value, forKey: Self.focusSearchOnStartKey)
        bumpUpdatedAt()
        broadcast(focusSearchOnStart: value)
        broadcastSnapshot()
    }

    func setLocaleTag(_ tag: String) {
        if tag.isEmpty {
            defaults.removeObject(forKey: Self.localeTagKey)
            // Clear AppleLanguages so the system falls back to the OS
            // language. Setting nil/empty isn't enough — UserDefaults keeps
            // the prior value.
            defaults.removeObject(forKey: Self.appleLanguagesKey)
        } else {
            defaults.set(tag, forKey: Self.localeTagKey)
            // The system reads `AppleLanguages` (an array) to resolve
            // `Bundle.main.preferredLocalizations`. Forcing it here makes
            // `Text(_:)` and `String(localized:)` lookups follow the
            // user's selection on the next view-tree rebuild.
            defaults.set([tag], forKey: Self.appleLanguagesKey)
        }
        bumpUpdatedAt()
        broadcast(locale: tag)
        broadcastSnapshot()
    }

    // MARK: - v0.8 entitlement state (local-only)

    var legacyUserGranted: Bool {
        defaults.object(forKey: Self.legacyUserGrantedKey) as? Bool ?? false
    }

    func setLegacyUserGranted(_ value: Bool) {
        defaults.set(value, forKey: Self.legacyUserGrantedKey)
    }

    var legacyCheckDoneForUid: String {
        defaults.string(forKey: Self.legacyCheckDoneForUidKey) ?? ""
    }

    func setLegacyCheckDoneForUid(_ uid: String) {
        defaults.set(uid, forKey: Self.legacyCheckDoneForUidKey)
    }

    var cachedEntitlement: String {
        defaults.string(forKey: Self.cachedEntitlementKey) ?? "NOT_ENTITLED"
    }

    func setCachedEntitlement(_ value: String) {
        defaults.set(value, forKey: Self.cachedEntitlementKey)
    }

    func applyRemoteSnapshot(_ s: UserPreferencesSnapshot) {
        // Write each field. Preserve the cloud's updatedAt (do NOT bump
        // to now) — load-bearing for LWW so a pulled value doesn't
        // immediately re-push.
        defaults.set(s.themeMode.rawValue, forKey: Self.themeModeKey)
        if s.localeTag.isEmpty {
            defaults.removeObject(forKey: Self.localeTagKey)
            defaults.removeObject(forKey: Self.appleLanguagesKey)
        } else {
            defaults.set(s.localeTag, forKey: Self.localeTagKey)
            defaults.set([s.localeTag], forKey: Self.appleLanguagesKey)
        }
        defaults.set(s.showPurchased, forKey: Self.showPurchasedKey)
        defaults.set(s.shopAtStoreSortMode.rawValue, forKey: Self.shopAtStoreSortModeKey)
        defaults.set(s.itemsListSortMode.rawValue, forKey: Self.itemsListSortModeKey)
        defaults.set(s.itemsListShowOnlyNeeded, forKey: Self.itemsListShowOnlyNeededKey)
        defaults.set(s.startScreen.rawValue, forKey: Self.startScreenKey)
        defaults.set(s.focusSearchOnStart, forKey: Self.focusSearchOnStartKey)
        defaults.set(NSNumber(value: s.updatedAt), forKey: Self.updatedAtKey)
        broadcast(themeMode: s.themeMode)
        broadcast(locale: s.localeTag)
        broadcast(showPurchased: s.showPurchased)
        broadcast(shopAtStoreSortMode: s.shopAtStoreSortMode)
        broadcast(itemsListSortMode: s.itemsListSortMode)
        broadcast(itemsListShowOnlyNeeded: s.itemsListShowOnlyNeeded)
        broadcast(startScreen: s.startScreen)
        broadcast(focusSearchOnStart: s.focusSearchOnStart)
        broadcastSnapshot()
    }

    private func bumpUpdatedAt() {
        defaults.set(NSNumber(value: clock.nowMs()), forKey: Self.updatedAtKey)
    }

    var themeModeStream: AsyncStream<ThemeMode> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.themeModeContinuations[id] = continuation
            let initial = self.themeMode
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.themeModeContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var localeTagStream: AsyncStream<String> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.localeContinuations[id] = continuation
            let initial = self.localeTag
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.localeContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var showPurchasedStream: AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.showPurchasedContinuations[id] = continuation
            let initial = self.showPurchased
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.showPurchasedContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var shopAtStoreSortModeStream: AsyncStream<SortMode> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.shopSortModeContinuations[id] = continuation
            let initial = self.shopAtStoreSortMode
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.shopSortModeContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var itemsListSortModeStream: AsyncStream<SortMode> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.itemsSortModeContinuations[id] = continuation
            let initial = self.itemsListSortMode
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.itemsSortModeContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var itemsListShowOnlyNeededStream: AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.itemsShowOnlyNeededContinuations[id] = continuation
            let initial = self.itemsListShowOnlyNeeded
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.itemsShowOnlyNeededContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var startScreenStream: AsyncStream<StartScreen> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.startScreenContinuations[id] = continuation
            let initial = self.startScreen
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.startScreenContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var focusSearchOnStartStream: AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.focusSearchOnStartContinuations[id] = continuation
            let initial = self.focusSearchOnStart
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.focusSearchOnStartContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var snapshotStream: AsyncStream<UserPreferencesSnapshot> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.snapshotContinuations[id] = continuation
            let initial = self.snapshot
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.snapshotContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    private func broadcast(themeMode: ThemeMode) {
        lock.lock()
        let conts = Array(themeModeContinuations.values)
        lock.unlock()
        for c in conts { c.yield(themeMode) }
    }

    private func broadcast(locale: String) {
        lock.lock()
        let conts = Array(localeContinuations.values)
        lock.unlock()
        for c in conts { c.yield(locale) }
    }

    private func broadcast(showPurchased: Bool) {
        lock.lock()
        let conts = Array(showPurchasedContinuations.values)
        lock.unlock()
        for c in conts { c.yield(showPurchased) }
    }

    private func broadcast(shopAtStoreSortMode: SortMode) {
        lock.lock()
        let conts = Array(shopSortModeContinuations.values)
        lock.unlock()
        for c in conts { c.yield(shopAtStoreSortMode) }
    }

    private func broadcast(itemsListSortMode: SortMode) {
        lock.lock()
        let conts = Array(itemsSortModeContinuations.values)
        lock.unlock()
        for c in conts { c.yield(itemsListSortMode) }
    }

    private func broadcast(itemsListShowOnlyNeeded: Bool) {
        lock.lock()
        let conts = Array(itemsShowOnlyNeededContinuations.values)
        lock.unlock()
        for c in conts { c.yield(itemsListShowOnlyNeeded) }
    }

    private func broadcast(startScreen: StartScreen) {
        lock.lock()
        let conts = Array(startScreenContinuations.values)
        lock.unlock()
        for c in conts { c.yield(startScreen) }
    }

    private func broadcast(focusSearchOnStart: Bool) {
        lock.lock()
        let conts = Array(focusSearchOnStartContinuations.values)
        lock.unlock()
        for c in conts { c.yield(focusSearchOnStart) }
    }

    private func broadcastSnapshot() {
        let snap = snapshot
        lock.lock()
        let conts = Array(snapshotContinuations.values)
        lock.unlock()
        for c in conts { c.yield(snap) }
    }
}
