import SwiftUI

/// iOS-native type scale aligned with the Material 3 hierarchy used on Android.
/// Uses system Dynamic Type so users' accessibility settings are respected.
enum StorehopTypography {
    static let displayLarge   = Font.system(.largeTitle,  design: .default, weight: .semibold)
    static let headlineLarge  = Font.system(.title,       design: .default, weight: .semibold)
    static let headlineSmall  = Font.system(.title2,      design: .default, weight: .semibold)
    static let titleLarge     = Font.system(.title3,      design: .default, weight: .semibold)
    static let titleMedium    = Font.system(.headline,    design: .default, weight: .semibold)
    static let titleSmall     = Font.system(.subheadline, design: .default, weight: .semibold)
    static let bodyLarge      = Font.system(.body,        design: .default, weight: .regular)
    static let bodyMedium     = Font.system(.callout,     design: .default, weight: .regular)
    static let bodySmall      = Font.system(.footnote,    design: .default, weight: .regular)
    static let labelLarge     = Font.system(.subheadline, design: .default, weight: .medium)
    static let labelMedium    = Font.system(.footnote,    design: .default, weight: .medium)
    static let labelSmall     = Font.system(.caption,     design: .default, weight: .medium)
}
