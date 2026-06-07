import Foundation

/// Type-safe NavigationStack destinations. SwiftUI's NavigationStack stores
/// path values that conform to Hashable; using an enum keeps the set
/// closed and makes route changes show up as compile errors.
enum ShopRoute: Hashable, Sendable {
    case shopAtStore(storeId: String)
    case editAisles(storeId: String)
    /// Pushed when the user invokes the context-menu "Edit" on a row inside
    /// a store. The same ItemFormView the Items tab uses, just hosted inside
    /// the Shop tab's NavigationStack so the Back button returns to the
    /// store list rather than dropping the user onto the Items tab.
    case editItem(itemId: String)
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
