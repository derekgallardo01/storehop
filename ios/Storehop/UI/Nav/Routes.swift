import Foundation

/// Type-safe NavigationStack destinations. SwiftUI's NavigationStack stores
/// path values that conform to Hashable; using an enum keeps the set
/// closed and makes route changes show up as compile errors.
enum ShopRoute: Hashable, Sendable {
    case shopAtStore(storeId: String)
    case editAisles(storeId: String)
}

enum ItemsRoute: Hashable, Sendable {
    case manageCategories
    case addItem
    case editItem(itemId: String)
}

enum SettingsRoute: Hashable, Sendable {
    case settings
}

/// Top-level tab identifier. Drives `TabView`'s selection.
enum AppTab: Hashable, Sendable {
    case shop
    case items
}
