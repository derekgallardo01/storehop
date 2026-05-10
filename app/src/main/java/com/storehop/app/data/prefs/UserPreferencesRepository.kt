package com.storehop.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin DataStore wrapper for user-tweakable preferences that aren't tied to
 * the Firebase user (so they don't belong in the Room schema or sync to
 * Firestore).
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val themeMode: Flow<ThemeMode> = dataStore.data
        .map { prefs -> ThemeMode.fromName(prefs[KEY_THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /**
     * Whether checked-off rows (any `!isNeeded` row, staple or not) should
     * remain visible struck-through on the Shop-at-Store screen. Default is
     * true to preserve the historical behavior; the toggle is in the screen's
     * top app bar.
     */
    val showPurchased: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_SHOW_PURCHASED] ?: true }

    suspend fun setShowPurchased(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_PURCHASED] = value }
    }

    /**
     * Sort mode for the Shop-at-Store list. Default [SortMode.CATEGORY]
     * preserves the historical aisle-grouped layout. Toggle lives in the
     * Shop-at-Store top app bar -- one preference applies to every store
     * (per-screen scope, not per-store).
     */
    val shopAtStoreSortMode: Flow<SortMode> = dataStore.data
        .map { prefs -> SortMode.fromName(prefs[KEY_SHOP_AT_STORE_SORT_MODE]) ?: SortMode.CATEGORY }

    suspend fun setShopAtStoreSortMode(mode: SortMode) {
        dataStore.edit { it[KEY_SHOP_AT_STORE_SORT_MODE] = mode.name }
    }

    /**
     * Sort mode for the master Items list. Default [SortMode.ALPHABETIC]
     * preserves the historical flat-alphabetic layout.
     */
    val itemsListSortMode: Flow<SortMode> = dataStore.data
        .map { prefs -> SortMode.fromName(prefs[KEY_ITEMS_LIST_SORT_MODE]) ?: SortMode.ALPHABETIC }

    suspend fun setItemsListSortMode(mode: SortMode) {
        dataStore.edit { it[KEY_ITEMS_LIST_SORT_MODE] = mode.name }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SHOW_PURCHASED = booleanPreferencesKey("shop_show_purchased")
        val KEY_SHOP_AT_STORE_SORT_MODE = stringPreferencesKey("shop_at_store_sort_mode")
        val KEY_ITEMS_LIST_SORT_MODE = stringPreferencesKey("items_list_sort_mode")
    }
}

/**
 * How a list of items should be ordered. Each screen that supports a sort
 * toggle keeps its own preference -- the in-store list and the Items list
 * are independent.
 */
enum class SortMode {
    CATEGORY,
    ALPHABETIC;

    companion object {
        fun fromName(name: String?): SortMode? = name?.let {
            entries.firstOrNull { mode -> mode.name == it }
        }
    }
}
