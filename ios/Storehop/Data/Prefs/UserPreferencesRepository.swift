import Foundation

/// Stores theme + locale prefs in UserDefaults. Mirrors Android
/// `UserPreferencesRepository` (DataStore-backed there). Locale on iOS is
/// also written to `AppleLanguages` so `Bundle.main.preferredLocalizations`
/// honors the override on the next view-tree rebuild.
///
/// Backed by UserDefaults — small enough that persistence is synchronous
/// and the read API can stay async-free (which keeps the SwiftUI binding
/// surface simple).
protocol UserPreferencesRepository: Sendable {
    var themeMode: ThemeMode { get }

    /// Empty string = follow system. "en" / "pt-PT" otherwise.
    var localeTag: String { get }

    func setThemeMode(_ mode: ThemeMode)
    func setLocaleTag(_ tag: String)

    /// Stream of theme-mode changes for the SettingsView to redraw the
    /// radio selection without polling. Emits the current value on
    /// subscription.
    var themeModeStream: AsyncStream<ThemeMode> { get }
    var localeTagStream: AsyncStream<String> { get }
}

final class LiveUserPreferencesRepository: UserPreferencesRepository, @unchecked Sendable {
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var themeModeContinuations: [UUID: AsyncStream<ThemeMode>.Continuation] = [:]
    private var localeContinuations: [UUID: AsyncStream<String>.Continuation] = [:]

    private static let themeModeKey = "storehop.themeMode"
    private static let localeTagKey = "storehop.localeTag"
    private static let appleLanguagesKey = "AppleLanguages"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var themeMode: ThemeMode {
        ThemeMode.fromName(defaults.string(forKey: Self.themeModeKey))
    }

    var localeTag: String {
        defaults.string(forKey: Self.localeTagKey) ?? ""
    }

    func setThemeMode(_ mode: ThemeMode) {
        defaults.set(mode.rawValue, forKey: Self.themeModeKey)
        broadcast(themeMode: mode)
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
}
