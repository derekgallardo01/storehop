import Foundation
import SwiftUI

/// User's theme preference. `.system` follows the OS dark-mode setting;
/// `.light` and `.dark` override regardless of system. Persisted as the
/// raw string in UserDefaults.
enum ThemeMode: String, Sendable, CaseIterable, Codable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case dark = "DARK"

    /// Safe parse: unknown / corrupted values fall back to system.
    static func fromName(_ name: String?) -> ThemeMode {
        guard let name else { return .system }
        return ThemeMode(rawValue: name) ?? .system
    }

    /// Maps to a SwiftUI `ColorScheme?` to drive `.preferredColorScheme()`.
    /// `.system` returns nil so SwiftUI honors the OS setting.
    var preferredColorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light:  return .light
        case .dark:   return .dark
        }
    }
}
