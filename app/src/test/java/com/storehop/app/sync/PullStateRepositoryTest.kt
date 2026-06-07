package com.storehop.app.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Pin the per-uid pull state round-trip. The state survives process death
 * via DataStore -- otherwise a crash mid-pull would leave us with a
 * SUCCEEDED state on disk and no data, which would silently allow the
 * push side to leak seeded data to the cloud.
 */
@RunWith(RobolectricTestRunner::class)
class PullStateRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var dataStoreFile: File

    @Before fun setup() {
        dataStoreFile = tempFolder.newFile("pull_state.preferences_pb")
        dataStoreFile.delete()
    }

    @After fun tearDown() {
        dataStoreFile.delete()
    }

    @Test fun `observe returns NEEDED for a uid that's never been written`() = runTest {
        val repo = PullStateRepository(newStore())
        assertThat(repo.observe("never-seen-uid").first()).isEqualTo(PullState.NEEDED)
    }

    @Test fun `set + observe round trips per uid`() = runTest {
        val repo = PullStateRepository(newStore())

        repo.set("uid-a", PullState.IN_PROGRESS)
        repo.set("uid-b", PullState.SUCCEEDED)

        assertThat(repo.observe("uid-a").first()).isEqualTo(PullState.IN_PROGRESS)
        assertThat(repo.observe("uid-b").first()).isEqualTo(PullState.SUCCEEDED)
    }

    @Test fun `transitions preserve only the most recent state`() = runTest {
        val repo = PullStateRepository(newStore())

        repo.set("uid-a", PullState.IN_PROGRESS)
        repo.set("uid-a", PullState.SUCCEEDED)
        assertThat(repo.observe("uid-a").first()).isEqualTo(PullState.SUCCEEDED)

        repo.set("uid-a", PullState.FAILED)
        assertThat(repo.observe("uid-a").first()).isEqualTo(PullState.FAILED)
    }

    @Test fun `falls back to NEEDED if the persisted value is unrecognized`() = runTest {
        // Older app version may have written an enum constant we don't know.
        // Falls back to NEEDED (safe default: triggers a pull on next sign-in).
        val store = newStore(
            initial = preferencesOf(
                stringPreferencesKey("pull_state_uid-a") to "BANANA",
            ),
        )
        val repo = PullStateRepository(store)
        assertThat(repo.observe("uid-a").first()).isEqualTo(PullState.NEEDED)
    }

    private fun newStore(initial: androidx.datastore.preferences.core.Preferences? = null) =
        PreferenceDataStoreFactory.create(
            produceFile = { dataStoreFile },
        ).also {
            if (initial != null) {
                kotlinx.coroutines.runBlocking {
                    it.updateData { initial }
                }
            }
        }
}
