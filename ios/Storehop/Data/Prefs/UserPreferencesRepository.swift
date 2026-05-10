import Foundation

/// Stores theme + locale prefs in UserDefaults. Mirrors Android
/// `UserPreferencesRepository` (DataStore-backed there). Locale on iOS is
/// also written to `AppleLanguages` so `Bundle.main.preferredLocalizations`
/// honors the override on the next view-tree rebuild.
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

    func setThemeMode(_ mode: ThemeMode)
    func setLocaleTag(_ tag: String)
    func setShowPurchased(_ value: Bool)
    func setShopAtStoreSortMode(_ mode: SortMode)
    func setItemsListSortMode(_ mode: SortMode)

    /// Stream of theme-mode changes for the SettingsView to redraw the
    /// radio selection without polling. Emits the current value on
    /// subscription.
    var themeModeStream: AsyncStream<ThemeMode> { get }
    var localeTagStream: AsyncStream<String> { get }
    var showPurchasedStream: AsyncStream<Bool> { get }
    var shopAtStoreSortModeStream: AsyncStream<SortMode> { get }
    var itemsListSortModeStream: AsyncStream<SortMode> { get }
}

final class LiveUserPreferencesRepository: UserPreferencesRepository, @unchecked Sendable {
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var themeModeContinuations: [UUID: AsyncStream<ThemeMode>.Continuation] = [:]
    private var localeContinuations: [UUID: AsyncStream<String>.Continuation] = [:]
    private var showPurchasedContinuations: [UUID: AsyncStream<Bool>.Continuation] = [:]
    private var shopSortModeContinuations: [UUID: AsyncStream<SortMode>.Continuation] = [:]
    private var itemsSortModeContinuations: [UUID: AsyncStream<SortMode>.Continuation] = [:]

    private static let themeModeKey = "storehop.themeMode"
    private static let localeTagKey = "storehop.localeTag"
    private static let appleLanguagesKey = "AppleLanguages"
    // Mirrors Android's DataStore key names so a developer reading either
    // platform sees the same identifier.
    private static let showPurchasedKey = "shop_show_purchased"
    private static let shopAtStoreSortModeKey = "shop_at_store_sort_mode"
    private static let itemsListSortModeKey = "items_list_sort_mode"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
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

    func setThemeMode(_ mode: ThemeMode) {
        defaults.set(mode.rawValue, forKey: Self.themeModeKey)
        broadcast(themeMode: mode)
    }

    func setShowPurchased(_ value: Bool) {
        defaults.set(value, forKey: Self.showPurchasedKey)
        broadcast(showPurchased: value)
    }

    func setShopAtStoreSortMode(_ mode: SortMode) {
        defaults.set(mode.rawValue, forKey: Self.shopAtStoreSortModeKey)
        broadcast(shopAtStoreSortMode: mode)
    }

    func setItemsListSortMode(_ mode: SortMode) {
        defaults.set(mode.rawValue, forKey: Self.itemsListSortModeKey)
        broadcast(itemsListSortMode: mode)
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
        broadcast(locale: tag)
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
}
