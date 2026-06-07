package com.storehop.app.data.prefs

import androidx.annotation.Keep

/**
 * Firestore wire format for `/userPrefs/{uid}`. Owned by
 * [UserPreferencesSync] in the v0.7.1 cloud-sync flow.
 *
 * Shape mirrors every other sync DTO in [com.storehop.app.sync.dto.Dtos.kt]:
 * `@Keep` + `var` + default-value constructor so Firestore's reflective
 * `toObject()` / `fromObject()` work. Enums travel as strings so we can
 * reorder / rename / add cases without invalidating older docs.
 *
 * No `householdId` field — preferences are per-user, not per-household.
 * Path scoping (`/userPrefs/{uid}`) carries the access boundary.
 */
@Keep
class UserPreferencesDto(
    var themeMode: String = ThemeMode.SYSTEM.name,
    var localeTag: String = "",
    var showPurchased: Boolean = true,
    var shopAtStoreSortMode: String = SortMode.CATEGORY.name,
    var itemsListSortMode: String = SortMode.ALPHABETIC.name,
    var updatedAt: Long = 0L,
)

/** Convert a local snapshot to a wire-format DTO. */
fun UserPreferencesSnapshot.toDto(): UserPreferencesDto = UserPreferencesDto(
    themeMode = themeMode.name,
    localeTag = localeTag,
    showPurchased = showPurchased,
    shopAtStoreSortMode = shopAtStoreSortMode.name,
    itemsListSortMode = itemsListSortMode.name,
    updatedAt = updatedAt,
)

/**
 * Decode a cloud DTO into a local snapshot. Unknown enum values fall back to
 * defaults so a future v0.8 client writing `SortMode.AISLE` won't crash
 * v0.7.x clients reading the same doc.
 */
fun UserPreferencesDto.toSnapshot(): UserPreferencesSnapshot = UserPreferencesSnapshot(
    themeMode = ThemeMode.fromName(themeMode),
    localeTag = localeTag,
    showPurchased = showPurchased,
    shopAtStoreSortMode = SortMode.fromName(shopAtStoreSortMode) ?: SortMode.CATEGORY,
    itemsListSortMode = SortMode.fromName(itemsListSortMode) ?: SortMode.ALPHABETIC,
    updatedAt = updatedAt,
)
