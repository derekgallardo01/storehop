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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC)

    @Before fun setup() {
        dataStoreFile = tempFolder.newFile("prefs.preferences_pb")
        dataStoreFile.delete() // DataStore creates the file itself
    }

    @After fun tearDown() {
        dataStoreFile.delete()
    }

    @Test fun `themeMode flow defaults to SYSTEM when no value is stored`() = runTest {
        val store = newStore()
        val repo = newRepo(store)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `setThemeMode persists across reads`() = runTest {
        val store = newStore()
        val repo = newRepo(store)

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
        val repo = newRepo(store)
        assertThat(repo.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `showPurchased defaults to true when no value is stored`() = runTest {
        val repo = newRepo(newStore())
        assertThat(repo.showPurchased.first()).isTrue()
    }

    @Test fun `setShowPurchased persists across reads`() = runTest {
        val repo = newRepo(newStore())
        repo.setShowPurchased(false)
        assertThat(repo.showPurchased.first()).isFalse()
        repo.setShowPurchased(true)
        assertThat(repo.showPurchased.first()).isTrue()
    }

    @Test fun `shopAtStoreSortMode defaults to CATEGORY (preserves historical layout)`() = runTest {
        val repo = newRepo(newStore())
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
    }

    @Test fun `setShopAtStoreSortMode round-trips both values`() = runTest {
        val repo = newRepo(newStore())
        repo.setShopAtStoreSortMode(SortMode.ALPHABETIC)
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
        repo.setShopAtStoreSortMode(SortMode.CATEGORY)
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
    }

    @Test fun `itemsListSortMode defaults to ALPHABETIC (preserves historical layout)`() = runTest {
        val repo = newRepo(newStore())
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
    }

    @Test fun `setItemsListSortMode round-trips both values`() = runTest {
        val repo = newRepo(newStore())
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
        val repo = newRepo(store)
        // Defaults preserve the historical per-screen layout.
        assertThat(repo.shopAtStoreSortMode.first()).isEqualTo(SortMode.CATEGORY)
        assertThat(repo.itemsListSortMode.first()).isEqualTo(SortMode.ALPHABETIC)
    }

    // MARK: - v0.7.1 additions (localeTag + snapshot + applyRemoteSnapshot)

    @Test fun `localeTag defaults to empty string (follow system)`() = runTest {
        val repo = newRepo(newStore())
        assertThat(repo.localeTag.first()).isEqualTo("")
    }

    @Test fun `setLocaleTag round-trips and bumps updatedAt`() = runTest {
        val repo = newRepo(newStore())
        repo.setLocaleTag("pt-PT")
        assertThat(repo.localeTag.first()).isEqualTo("pt-PT")
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(50_000L)
    }

    @Test fun `every setter bumps updatedAt to the clock value`() = runTest {
        val repo = newRepo(newStore())
        // updatedAt is 0 (default) until any setter runs.
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(0L)

        repo.setThemeMode(ThemeMode.DARK)
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(50_000L)

        repo.setShowPurchased(false)
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(50_000L)

        repo.setShopAtStoreSortMode(SortMode.ALPHABETIC)
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(50_000L)

        repo.setItemsListSortMode(SortMode.CATEGORY)
        assertThat(repo.currentSnapshot().updatedAt).isEqualTo(50_000L)
    }

    @Test fun `snapshot reflects every cloud-syncable field`() = runTest {
        val repo = newRepo(newStore())
        repo.setThemeMode(ThemeMode.DARK)
        repo.setLocaleTag("it")
        repo.setShowPurchased(false)
        repo.setShopAtStoreSortMode(SortMode.ALPHABETIC)
        repo.setItemsListSortMode(SortMode.CATEGORY)

        val s = repo.currentSnapshot()
        assertThat(s.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(s.localeTag).isEqualTo("it")
        assertThat(s.showPurchased).isFalse()
        assertThat(s.shopAtStoreSortMode).isEqualTo(SortMode.ALPHABETIC)
        assertThat(s.itemsListSortMode).isEqualTo(SortMode.CATEGORY)
        assertThat(s.updatedAt).isEqualTo(50_000L)
    }

    @Test fun `applyRemoteSnapshot writes every field including the cloud updatedAt`() = runTest {
        // Cloud snapshot from another device, with an updatedAt > local.
        val repo = newRepo(newStore())
        val cloud = UserPreferencesSnapshot(
            themeMode = ThemeMode.DARK,
            localeTag = "es",
            showPurchased = false,
            shopAtStoreSortMode = SortMode.ALPHABETIC,
            itemsListSortMode = SortMode.CATEGORY,
            updatedAt = 999_000L,
        )

        repo.applyRemoteSnapshot(cloud)

        // Every field landed. updatedAt is the CLOUD value (load-bearing for LWW —
        // if applyRemoteSnapshot stamped now() it would immediately re-push the
        // cloud value back as if it were a new local change).
        assertThat(repo.currentSnapshot()).isEqualTo(cloud)
    }

    private fun newRepo(store: androidx.datastore.core.DataStore<Preferences>) =
        UserPreferencesRepository(store, fixedClock)

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
