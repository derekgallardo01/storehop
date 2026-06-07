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
    var updatedAt: Int64

    init(
        themeMode: String = ThemeMode.system.rawValue,
        localeTag: String = "",
        showPurchased: Bool = true,
        shopAtStoreSortMode: String = SortMode.category.rawValue,
        itemsListSortMode: String = SortMode.alphabetic.rawValue,
        updatedAt: Int64 = 0
    ) {
        self.themeMode = themeMode
        self.localeTag = localeTag
        self.showPurchased = showPurchased
        self.shopAtStoreSortMode = shopAtStoreSortMode
        self.itemsListSortMode = itemsListSortMode
        self.updatedAt = updatedAt
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
            updatedAt: updatedAt,
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
            updatedAt: updatedAt,
        )
    }
}
