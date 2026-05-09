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

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SHOW_PURCHASED = booleanPreferencesKey("shop_show_purchased")
    }
}
