package com.storehop.app.data.prefs

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.preferencesOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins the DataStore preferences round-trip. The repository is small, but
 * the load-bearing contract is "what the user picked yesterday is still
 * what they have today" -- a regression here is invisible until the user
 * notices their theme reset on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class UserPreferencesRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var dataStoreFile: File

    @Before fun setup() {
        dataStoreFile = tempFolder.newFile("prefs.preferences_pb")
        dataStoreFile.delete() // DataStore creates the file itself
    }

    @After fun tearDown() {
        dataStoreFile.delete()
    }

    @Test fun `themeMode flow defaults to SYSTEM when no value is stored`() = runTest {
        val store = newStore()
        val repo = UserPreferencesRepository(store)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `setThemeMode persists across reads`() = runTest {
        val store = newStore()
        val repo = UserPreferencesRepository(store)

        repo.setThemeMode(ThemeMode.DARK)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.DARK)

        repo.setThemeMode(ThemeMode.LIGHT)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.LIGHT)
    }

    @Test fun `themeMode flow falls back to SYSTEM if the stored value is unrecognized`() = runTest {
        // Write a stored value that doesn't match any enum constant -- could
        // happen if the schema changes and an older app version's value
        // lands in storage. Should fall back to SYSTEM, not crash.
        val store = newStore(initial = preferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("theme_mode") to "BANANA",
        ))
        val repo = UserPreferencesRepository(store)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `showPurchased defaults to true when no value is stored`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        assertThat(repo.showPurchased.first()).isTrue()
    }

    @Test fun `setShowPurchased persists across reads`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        repo.setShowPurchased(false)
        assertThat(repo.showPurchased.first()).isFalse()
        repo.setShowPurchased(true)
        assertThat(repo.showPurchased.first()).isTrue()
    }

    @Test fun `shopAtStoreSortMode defaults to CATEGORY (preserves historical layout)`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
    }

    @Test fun `setShopAtStoreSortMode round-trips both values`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        repo.setShopAtStoreSortMode(SortMode.ALPHABETIC)
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
        repo.setShopAtStoreSortMode(SortMode.CATEGORY)
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
    }

    @Test fun `itemsListSortMode defaults to ALPHABETIC (preserves historical layout)`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
    }

    @Test fun `setItemsListSortMode round-trips both values`() = runTest {
        val repo = UserPreferencesRepository(newStore())
        repo.setItemsListSortMode(SortMode.CATEGORY)
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.CATEGORY)
        repo.setItemsListSortMode(SortMode.ALPHABETIC)
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
    }

    @Test fun `unknown SortMode value in storage falls back to the default`() = runTest {
        val store = newStore(initial = preferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("shop_at_store_sort_mode") to "BANANA",
            androidx.datastore.preferences.core.stringPreferencesKey("items_list_sort_mode") to "BANANA",
        ))
        val repo = UserPreferencesRepository(store)
        // Defaults preserve the historical per-screen layout.
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
    }

    private fun newStore(initial: Preferences? = null) = PreferenceDataStoreFactory.create(
        produceFile = { dataStoreFile },
    ).also {
        if (initial != null) {
            kotlinx.coroutines.runBlocking {
                it.updateData { initial }
            }
        }
    }
}
