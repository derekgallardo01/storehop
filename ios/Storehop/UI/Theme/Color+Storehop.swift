import SwiftUI

/// Named accessors for the Storehop sage palette. Backed by Color Sets in the
/// Asset Catalog so light/dark variants resolve automatically. Hex values are
/// duplicated in the Color Set JSON; this file is the API surface.
///
/// Palette mirrors the Android Material 3 ColorScheme from `ui/theme/Color.kt`.
enum StorehopColors {
    static let primary           = Color("Brand/Primary")
    static let onPrimary         = Color("Brand/OnPrimary")
    static let primaryContainer  = Color("Brand/PrimaryContainer")
    static let onPrimaryContainer = Color("Brand/OnPrimaryContainer")
    static let secondary         = Color("Brand/Secondary")
    static let onSecondary       = Color("Brand/OnSecondary")

    static let background        = Color("Surface/Background")
    static let onBackground      = Color("Text/OnBackground")
    static let surface           = Color("Surface/Surface")
    static let onSurface         = Color("Text/OnSurface")
    static let surfaceVariant    = Color("Surface/SurfaceVariant")
    static let onSurfaceVariant  = Color("Text/OnSurfaceVariant")
    static let outline           = Color("Surface/Outline")

    static let error             = Color("Brand/Error")
    static let onError           = Color("Brand/OnError")
}
