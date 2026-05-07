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
