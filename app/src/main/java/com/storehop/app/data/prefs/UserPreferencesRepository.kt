package com.storehop.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin DataStore wrapper for user-tweakable preferences.
 *
 * v0.7.1: preferences are now cloud-synced via Firestore (`/userPrefs/{uid}`)
 * through [UserPreferencesSync], so they survive an uninstall + reinstall
 * across signing-cert boundaries. Every setter bumps [KEY_UPDATED_AT] so
 * the sync layer can run last-write-wins against the cloud doc.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) {
    val themeMode: Flow<ThemeMode> = dataStore.data
        .map { prefs -> ThemeMode.fromName(prefs[KEY_THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit {
            it[KEY_THEME_MODE] = mode.name
            it[KEY_UPDATED_AT] = clock.millis()
        }
    }

    /**
     * Locale tag the user picked in Settings. "" means "follow system."
     *
     * v0.7.1 added this DataStore field so cloud sync has a stable
     * canonical value to push. Pre-v0.7.1 the language picker only
     * called `LocaleManager.setApplicationLocales(...)`, which is
     * persisted by the OS but tied to the app's signing certificate —
     * lost on cert-change uninstall.
     */
    val localeTag: Flow<String> = dataStore.data
        .map { prefs -> prefs[KEY_LOCALE_TAG] ?: "" }

    suspend fun setLocaleTag(tag: String) {
        dataStore.edit {
            it[KEY_LOCALE_TAG] = tag
            it[KEY_UPDATED_AT] = clock.millis()
        }
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
        dataStore.edit {
            it[KEY_SHOW_PURCHASED] = value
            it[KEY_UPDATED_AT] = clock.millis()
        }
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
        dataStore.edit {
            it[KEY_SHOP_AT_STORE_SORT_MODE] = mode.name
            it[KEY_UPDATED_AT] = clock.millis()
        }
    }

    /**
     * Sort mode for the master Items list. Default [SortMode.ALPHABETIC]
     * preserves the historical flat-alphabetic layout.
     */
    val itemsListSortMode: Flow<SortMode> = dataStore.data
        .map { prefs -> SortMode.fromName(prefs[KEY_ITEMS_LIST_SORT_MODE]) ?: SortMode.ALPHABETIC }

    suspend fun setItemsListSortMode(mode: SortMode) {
        dataStore.edit {
            it[KEY_ITEMS_LIST_SORT_MODE] = mode.name
            it[KEY_UPDATED_AT] = clock.millis()
        }
    }

    /**
     * Aggregated snapshot of every cloud-syncable preference plus
     * the LWW [UserPreferencesSnapshot.updatedAt]. Re-emits whenever any
     * field changes — [UserPreferencesSync] subscribes to debounce-push.
     */
    val snapshot: Flow<UserPreferencesSnapshot> = dataStore.data.map { prefs ->
        UserPreferencesSnapshot(
            themeMode = ThemeMode.fromName(prefs[KEY_THEME_MODE]),
            localeTag = prefs[KEY_LOCALE_TAG] ?: "",
            showPurchased = prefs[KEY_SHOW_PURCHASED] ?: true,
            shopAtStoreSortMode = SortMode.fromName(prefs[KEY_SHOP_AT_STORE_SORT_MODE]) ?: SortMode.CATEGORY,
            itemsListSortMode = SortMode.fromName(prefs[KEY_ITEMS_LIST_SORT_MODE]) ?: SortMode.ALPHABETIC,
            updatedAt = prefs[KEY_UPDATED_AT] ?: 0L,
        )
    }

    suspend fun currentSnapshot(): UserPreferencesSnapshot = snapshot.first()

    /**
     * Atomically write every field from a cloud-pulled snapshot. Preserves
     * the cloud's [UserPreferencesSnapshot.updatedAt] so LWW doesn't
     * re-trigger a push on the next observation tick.
     */
    suspend fun applyRemoteSnapshot(s: UserPreferencesSnapshot) {
        dataStore.edit {
            it[KEY_THEME_MODE] = s.themeMode.name
            it[KEY_LOCALE_TAG] = s.localeTag
            it[KEY_SHOW_PURCHASED] = s.showPurchased
            it[KEY_SHOP_AT_STORE_SORT_MODE] = s.shopAtStoreSortMode.name
            it[KEY_ITEMS_LIST_SORT_MODE] = s.itemsListSortMode.name
            it[KEY_UPDATED_AT] = s.updatedAt
        }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_LOCALE_TAG = stringPreferencesKey("locale_tag")
        val KEY_SHOW_PURCHASED = booleanPreferencesKey("shop_show_purchased")
        val KEY_SHOP_AT_STORE_SORT_MODE = stringPreferencesKey("shop_at_store_sort_mode")
        val KEY_ITEMS_LIST_SORT_MODE = stringPreferencesKey("items_list_sort_mode")
        val KEY_UPDATED_AT = longPreferencesKey("user_prefs_updated_at")
    }
}

/**
 * Aggregated user preferences DTO used by [UserPreferencesSync] to ferry
 * values between local DataStore and `/userPrefs/{uid}` in Firestore.
 *
 * [updatedAt] is the LWW arbiter: cloud writes only land locally if their
 * [updatedAt] exceeds the local snapshot's, and local writes only push to
 * cloud if local is newer than (or equal-to-absent from) the remote doc.
 */
data class UserPreferencesSnapshot(
    val themeMode: ThemeMode,
    val localeTag: String,
    val showPurchased: Boolean,
    val shopAtStoreSortMode: SortMode,
    val itemsListSortMode: SortMode,
    val updatedAt: Long,
)

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
