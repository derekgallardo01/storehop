import Foundation
import ObjectiveC

/// Runtime language switching without an app restart.
///
/// iOS caches the resolved bundle for `Bundle.main` at process startup
/// based on `AppleLanguages` in `UserDefaults`. Changing
/// `AppleLanguages` mid-session updates the UserDefaults value but does
/// **not** refresh that cache — so `NSLocalizedString` (which
/// `String(localized:)` ultimately calls into) keeps returning strings
/// from the old `.lproj`. Force-restarting the app is one way around
/// this; users hate it. The accepted alternative on iOS is to swap
/// `Bundle.main`'s class to a subclass whose `localizedString` looks up
/// the active `.lproj` at call time.
///
/// `LanguageBundle` is that subclass. `installSwizzle()` swaps
/// `Bundle.main`'s class to `LanguageBundle` once at app startup
/// (idempotent on repeat calls). After that, `setLanguage(_:)` updates
/// the active locale and every subsequent localized-string lookup reads
/// from the matching `.lproj` directly.
///
/// Combined with SwiftUI's view-tree rebuild on
/// `RootView`'s `.id(localeTag)`, the result is: pick a language in
/// Settings → every visible string flips to the new locale in <100ms
/// without a restart.
final class LanguageBundle: Bundle, @unchecked Sendable {
    /// Active locale tag (e.g. `"en"`, `"pt-PT"`, `"es"`, `"it"`). Empty
    /// string means "follow system" — fall through to the parent
    /// implementation which reads `AppleLanguages` like normal.
    nonisolated(unsafe) static var languageTag: String = ""

    /// Cache resolved bundles per locale tag so we're not stat'ing the
    /// app bundle on every NSLocalizedString call.
    private nonisolated(unsafe) static var bundleCache: [String: Bundle] = [:]
    private static let cacheLock = NSLock()

    override func localizedString(
        forKey key: String,
        value: String?,
        table tableName: String?
    ) -> String {
        let tag = LanguageBundle.languageTag
        guard !tag.isEmpty else {
            return super.localizedString(forKey: key, value: value, table: tableName)
        }
        guard let bundle = LanguageBundle.bundleForLanguage(tag) else {
            return super.localizedString(forKey: key, value: value, table: tableName)
        }
        return bundle.localizedString(forKey: key, value: value, table: tableName)
    }

    /// iOS 17+ `String(localized:)` / `LocalizedStringResource` resolve
    /// the active locale by reading `Bundle.preferredLocalizations` on the
    /// owning bundle *before* it ever calls `localizedString(forKey:)`.
    /// That property is computed once from `AppleLanguages` at process
    /// start and cached, so mid-session changes don't propagate — meaning
    /// Foundation picks the old `.lproj` even when our `localizedString`
    /// override would have redirected to the new one. Overriding this
    /// property here forces every internal locale-resolution path to see
    /// the user's current pick, which is what makes the Settings sheet
    /// flip instantly without a restart.
    override var preferredLocalizations: [String] {
        let tag = LanguageBundle.languageTag
        guard !tag.isEmpty else { return super.preferredLocalizations }
        // Keep `en` as the last-resort fallback so a missing key still
        // resolves to something readable instead of returning the raw
        // key string.
        return [tag, "en"]
    }

    /// Bundle.localizations is the full list of bundled localizations.
    /// Some lookup paths intersect this with the system's preferred
    /// languages to pick a match. Surfacing the user's tag at the front
    /// keeps that intersection deterministic.
    override var localizations: [String] {
        let tag = LanguageBundle.languageTag
        let base = super.localizations
        guard !tag.isEmpty else { return base }
        if base.contains(tag) {
            return [tag] + base.filter { $0 != tag }
        }
        return base
    }

    fileprivate static func bundleForLanguage(_ tag: String) -> Bundle? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let cached = bundleCache[tag] { return cached }

        // Apple writes `.lproj` directories with dashes converted to
        // underscores in some build configurations and as-is in others.
        // Try the literal tag first, then a couple of fallbacks before
        // giving up.
        let candidates: [String] = [tag, tag.replacingOccurrences(of: "-", with: "_"),
                                     String(tag.prefix(2))]
        for candidate in candidates {
            if let path = Bundle.main.path(forResource: candidate, ofType: "lproj"),
               let bundle = Bundle(path: path) {
                bundleCache[tag] = bundle
                return bundle
            }
        }
        return nil
    }
}

/// Runtime-switchable replacement for `String(localized:)`. Reads from
/// the `.lproj` bundle for the current `LanguageBundle.languageTag`,
/// calling that bundle's `localizedString(forKey:value:table:)` directly
/// — which bypasses Foundation's compile-time `LocalizedStringResource`
/// baking. That baking is what makes `object_setClass`-style swizzling
/// of `Bundle.main` ineffective for live language switching on iOS 17+
/// String Catalogs: `String(localized:)` captures the owning bundle at
/// compile time and resolves through CFBundle-level machinery that
/// never asks our Objective-C override. By calling the per-locale
/// bundle's `localizedString` directly here, we sidestep the cache
/// entirely.
///
/// `value: key` matches Foundation's default-when-missing behaviour so
/// a missing key surfaces as the key itself instead of an empty string.
@inline(__always)
func L(_ key: String) -> String {
    let tag = LanguageBundle.languageTag
    let bundle: Bundle = tag.isEmpty
        ? .main
        : (LanguageBundle.bundleForLanguage(tag) ?? .main)
    return bundle.localizedString(forKey: key, value: key, table: nil)
}

/// Overload for format-string keys (e.g. `"undo_category_deleted %@"`).
/// Replaces the previous String Catalog interpolation form
/// `String(localized: "key \(arg)")`, which baked the bundle reference
/// at compile time. Callers must supply the `printf`-style format spec
/// in the key itself just like the existing String Catalog entries.
@inline(__always)
func L(_ key: String, _ args: CVarArg...) -> String {
    return String(format: L(key), arguments: args)
}

/// The `.lproj` bundle for the active language tag, or `Bundle.main`
/// when "follow system" is selected. Used as the `bundle:` parameter
/// for the handful of remaining `String(localized:)` calls that need
/// Foundation's String Catalog interpolation magic (plurals, typed
/// substitution). Passing this here makes those lookups read from our
/// per-locale bundle instead of `Bundle.main`'s process-start-cached
/// preferred localization.
@inline(__always)
func currentLanguageBundle() -> Bundle {
    let tag = LanguageBundle.languageTag
    guard !tag.isEmpty else { return .main }
    return LanguageBundle.bundleForLanguage(tag) ?? .main
}

extension Bundle {
    /// Swap `Bundle.main`'s class to `LanguageBundle`. Call once at app
    /// startup before any localized-string lookup happens. Idempotent —
    /// subsequent calls are no-ops.
    static func installLanguageSwizzle() {
        if Bundle.main.isKind(of: LanguageBundle.self) { return }
        object_setClass(Bundle.main, LanguageBundle.self)
    }

    /// Update the active language. Call this every time the user changes
    /// the language preference; the next view rebuild picks it up.
    /// Passing `""` reverts to "follow system" (the parent
    /// implementation's default behaviour).
    static func setActiveLanguage(_ tag: String) {
        LanguageBundle.languageTag = tag
    }
}
