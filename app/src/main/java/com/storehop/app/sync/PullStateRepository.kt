package com.storehop.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.storehop.app.di.PullStatePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-uid pull state, persisted in its own DataStore file so corruption of the
 * user-preferences store (theme, locale) can't poison sync decisions and
 * vice-versa. Keyed `pull_state_<uid>` to scope per-account: a uid that's
 * never been pulled returns [PullState.NEEDED].
 *
 * The Flow returned by [observe] is what gates [SyncEngine] -- push jobs
 * only launch for a uid whose state is [PullState.SUCCEEDED].
 */
@Singleton
class PullStateRepository @Inject constructor(
    @PullStatePrefs private val dataStore: DataStore<Preferences>,
) {

    fun observe(uid: String): Flow<PullState> = dataStore.data.map { prefs ->
        prefs[keyFor(uid)]?.let { runCatching { PullState.valueOf(it) }.getOrNull() }
            ?: PullState.NEEDED
    }

    suspend fun set(uid: String, state: PullState) {
        dataStore.edit { it[keyFor(uid)] = state.name }
    }

    private fun keyFor(uid: String) = stringPreferencesKey("pull_state_$uid")
}
