import Foundation

/// Firestore wire format for `/userPrefs/{uid}`. Owned by
/// [UserPreferencesSync] in the v0.7.1 cloud-sync flow.
///
/// Codable so we can encode/decode via Firestore's `setData(from:)` and
/// `data(as:)` extensions. Enums travel as strings so we can reorder /
/// rename / add cases without invalidating older docs in cloud.
///
/// No `householdId` field — preferences are per-user, not per-household.
/// Path scoping (`/userPrefs/{uid}`) carries the access boundary.
///
/// Mirrors Android `UserPreferencesDto`.
struct UserPreferencesDto: Codable, Sendable {
    var themeMode: String
    var localeTag: String
    var showPurchased: Bool
    var shopAtStoreSortMode: String
    var itemsListSortMode: String
    var itemsListShowOnlyNeeded: Bool
    var startScreen: String
    var focusSearchOnStart: Bool
    var updatedAt: Int64

    init(
        themeMode: String = ThemeMode.system.rawValue,
        localeTag: String = "",
        showPurchased: Bool = true,
        shopAtStoreSortMode: String = SortMode.category.rawValue,
        itemsListSortMode: String = SortMode.alphabetic.rawValue,
        itemsListShowOnlyNeeded: Bool = false,
        startScreen: String = StartScreen.shop.rawValue,
        focusSearchOnStart: Bool = true,
        updatedAt: Int64 = 0
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

    enum CodingKeys: String, CodingKey {
        case themeMode
        case localeTag
        case showPurchased
        case shopAtStoreSortMode
        case itemsListSortMode
        case itemsListShowOnlyNeeded
        case startScreen
        case focusSearchOnStart
        case updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        themeMode = try container.decodeIfPresent(String.self, forKey: .themeMode) ?? ThemeMode.system.rawValue
        localeTag = try container.decodeIfPresent(String.self, forKey: .localeTag) ?? ""
        showPurchased = try container.decodeIfPresent(Bool.self, forKey: .showPurchased) ?? true
        shopAtStoreSortMode = try container.decodeIfPresent(String.self, forKey: .shopAtStoreSortMode) ?? SortMode.category.rawValue
        itemsListSortMode = try container.decodeIfPresent(String.self, forKey: .itemsListSortMode) ?? SortMode.alphabetic.rawValue
        itemsListShowOnlyNeeded = try container.decodeIfPresent(Bool.self, forKey: .itemsListShowOnlyNeeded) ?? false
        startScreen = try container.decodeIfPresent(String.self, forKey: .startScreen) ?? StartScreen.shop.rawValue
        focusSearchOnStart = try container.decodeIfPresent(Bool.self, forKey: .focusSearchOnStart) ?? true
        updatedAt = try container.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
    }
}

extension UserPreferencesSnapshot {
    /// Convert a local snapshot to a wire-format DTO.
    func toDto() -> UserPreferencesDto {
        UserPreferencesDto(
            themeMode: themeMode.rawValue,
            localeTag: localeTag,
            showPurchased: showPurchased,
            shopAtStoreSortMode: shopAtStoreSortMode.rawValue,
            itemsListSortMode: itemsListSortMode.rawValue,
            itemsListShowOnlyNeeded: itemsListShowOnlyNeeded,
            startScreen: startScreen.rawValue,
            focusSearchOnStart: focusSearchOnStart,
            updatedAt: updatedAt
        )
    }
}

extension UserPreferencesDto {
    /// Decode a cloud DTO into a local snapshot. Unknown enum values fall
    /// back to defaults so a future v0.8+ client writing
    /// `SortMode.AISLE` won't crash v0.7.x clients reading the same doc.
    func toSnapshot() -> UserPreferencesSnapshot {
        UserPreferencesSnapshot(
            themeMode: ThemeMode.fromName(themeMode),
            localeTag: localeTag,
            showPurchased: showPurchased,
            shopAtStoreSortMode: SortMode.fromName(shopAtStoreSortMode) ?? .category,
            itemsListSortMode: SortMode.fromName(itemsListSortMode) ?? .alphabetic,
            itemsListShowOnlyNeeded: itemsListShowOnlyNeeded,
            startScreen: StartScreen.fromName(startScreen) ?? .shop,
            focusSearchOnStart: focusSearchOnStart,
            updatedAt: updatedAt
        )
    }
}
