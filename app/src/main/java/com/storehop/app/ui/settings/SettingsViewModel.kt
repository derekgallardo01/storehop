package com.storehop.app.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.auth.GoogleSignInUseCase
import com.storehop.app.data.prefs.ThemeMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import com.storehop.app.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class AccountState(
    val isAnonymous: Boolean = true,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    /** True while a sign-in or sign-out is in flight. */
    val busy: Boolean = false,
    /** Error to surface in the UI, cleared after the user dismisses. */
    val error: String? = null,
)

/**
 * v0.7.1: state machine for the Settings → Data → "Force sync now" UX.
 *
 * The user taps the button when about to uninstall (Mike's sideload →
 * Play migration is the load-bearing case). We push every pending row
 * + the user-prefs doc, then surface "Safe to uninstall" when the
 * queue drains — or "Some items still pending" if the timeout fires.
 */
sealed class ForceSyncState {
    /** No flush in flight; the button is in its default state. */
    data object Idle : ForceSyncState()
    /** Flush running; UI shows a spinner + "Syncing N items..." */
    data class Syncing(val pendingCount: Int) : ForceSyncState()
    /** Queue drained; UI shows "Safe to uninstall." */
    data object SafeToUninstall : ForceSyncState()
    /** Timed out with rows still pending; UI shows the stuck count and a retry. */
    data class Failed(val pendingCount: Int) : ForceSyncState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: FirebaseAuth,
    private val googleSignIn: GoogleSignInUseCase,
    private val userPrefs: UserPreferencesRepository,
    private val sessionProvider: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
    private val pullCoordinator: PullCoordinator,
    private val pullStateRepo: PullStateRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Theme-mode pref for the Theme section's selection state. */
    val themeMode: StateFlow<ThemeMode> = userPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /**
     * v0.4: per-uid sync state for the cloud-sync banner. Tracks the most
     * recent peek/pull outcome for the active uid. The Settings screen shows
     * a "Cloud sync incomplete" banner when this is [PullState.FAILED].
     *
     * Resolves to [PullState.SUCCEEDED] when there's no signed-in uid -- the
     * banner only makes sense for an authenticated session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pullState: StateFlow<PullState> = sessionProvider.userId
        .flatMapLatest { uid ->
            if (uid == null) flowOf(PullState.SUCCEEDED) else pullStateRepo.observe(uid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PullState.SUCCEEDED)

    /**
     * Current per-app locale tag ("en", "pt-PT", or empty for "follow system").
     * Initialized from AppCompatDelegate at construction; updated optimistically
     * by [setLocale] so the radio selection reflects the choice instantly. The
     * activity is recreated by the screen after [setLocale] returns, which
     * remakes this VM with a fresh read -- so the optimistic value and the
     * post-recreate value should match.
     */
    private val _localeTag = MutableStateFlow(readLocaleTag())
    val currentLocaleTag: StateFlow<String> = _localeTag.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { _state.value = snapshot() }

    init { auth.addAuthStateListener(authListener) }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authListener)
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPrefs.setThemeMode(mode) }
    }

    /**
     * Switch the per-app locale. Pass `""` for "follow system."
     *
     * Goes straight to the system [LocaleManager] on API 33+ -- that's the
     * authoritative API for per-app languages on modern Android, persists at
     * the OS level, and auto-recreates the activity. AppCompat is supposed to
     * delegate here when its host is an AppCompatActivity, but we use
     * ComponentActivity (Compose-only); going direct skips any AppCompat
     * preconditions that fail silently in that setup.
     *
     * Falls back to AppCompat's backport on API 26-32 (our minSdk floor),
     * which uses its own SharedPreferences storage on those versions.
     *
     * (v0.5.9 tried routing through `AppCompatDelegate` on every API level
     * to work around an Italian/Portuguese-not-applying issue on Pixel; that
     * regressed Spanish too. Reverted in 0.5.10.)
     */
    fun setLocale(tag: String) {
        Log.i(TAG, "setLocale(tag='$tag') sdk=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (tag.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            val lm = appContext.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = locales
            Log.i(TAG, "LocaleManager.applicationLocales set; readback=${lm.applicationLocales.toLanguageTags()}")
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag),
            )
            Log.i(TAG, "AppCompat fallback: setApplicationLocales done")
        }
        _localeTag.value = tag
    }

    private fun readLocaleTag(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = appContext.getSystemService(LocaleManager::class.java)
            val list = lm.applicationLocales
            if (list.isEmpty) "" else list.toLanguageTags()
        } else {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) "" else locales.toLanguageTags()
        }
    }

    private companion object {
        const val TAG = "SettingsLocale"
    }

    /**
     * Sign in with Google. Wraps [GoogleSignInUseCase] (which uses Credential
     * Manager + linkWithCredential when the current user is anonymous, so the
     * uid is preserved through the upgrade and existing data stays attached).
     *
     * Caller passes the current Activity context -- Credential Manager attaches
     * its sheet to the calling activity, so Application context isn't enough.
     */
    fun signInWithGoogle(activityContext: Context) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            googleSignIn.signIn(activityContext)
                .onSuccess {
                    // Wait until the gated userId flow flips to the new uid.
                    // FirebaseAuthSessionProvider holds back that flip until
                    // its claim migration has re-stamped the previous uid's
                    // rows onto the new one -- so by the time the busy flag
                    // clears, the Shop screen's next query already sees a
                    // populated list. No empty-state flicker.
                    val targetUid = auth.currentUser?.uid
                    if (targetUid != null) {
                        sessionProvider.userId.first { it == targetUid }
                    }
                    _state.value = snapshot().copy(busy = false)
                }
                .onFailure { e ->
                    _state.value = snapshot().copy(
                        busy = false,
                        // GetCredentialCancellationException -> user dismissed
                        // the sheet; no error worth surfacing. Other failures
                        // bubble up.
                        error = if (e is androidx.credentials.exceptions.GetCredentialCancellationException) null
                                else e.message ?: "Could not sign in",
                    )
                }
        }
    }

    /**
     * Sign out and immediately sign back in anonymously so the app keeps
     * working without an authenticated user. This loses access to the prior
     * Google-linked uid's cloud data on this device (the new anon uid is a
     * fresh slate); the cloud data is still safe under the Google account
     * and reappears on the next sign-in.
     */
    fun signOut() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.signOut()
                auth.signInAnonymously().await()
                // Same as signInWithGoogle: wait for the gated userId flow
                // to flip to the new anon uid before clearing busy. Keeps
                // the Shop screen from rendering empty during the in-flight
                // claim migration that re-stamps the previous Google uid's
                // rows onto the fresh anon uid.
                val targetUid = auth.currentUser?.uid
                if (targetUid != null) {
                    sessionProvider.userId.first { it == targetUid }
                }
                _state.value = snapshot().copy(busy = false)
            } catch (e: Exception) {
                _state.value = snapshot().copy(
                    busy = false,
                    error = e.message ?: "Could not sign out",
                )
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    /**
     * v0.7.1: live count of `pendingSync = 1` rows across every entity for
     * the active household + the household_members table. Powers the
     * "Syncing N items..." UI text. Resolves to 0 when no household
     * is active (e.g. signed-out state).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingPushCount: StateFlow<Int> = householdSession.householdId
        .flatMapLatest { hid ->
            if (hid == null) flowOf(0) else syncEngine.observeAllPendingCount(hid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    /**
     * v0.7.1: state machine for the Force-sync-now button. Idle by default;
     * `forceSyncNow()` moves it through Syncing → SafeToUninstall (success)
     * or Syncing → Failed (timeout).
     */
    private val _forceSyncState = MutableStateFlow<ForceSyncState>(ForceSyncState.Idle)
    val forceSyncState: StateFlow<ForceSyncState> = _forceSyncState.asStateFlow()

    /**
     * v0.7.1: synchronously push every pending row + the user-prefs doc, wait
     * until the queue drains. Sets [forceSyncState] for the UI.
     *
     * Mike's sideload → Play upgrade tap: opens Settings → Data → taps the
     * button → reads "Safe to uninstall" → uninstalls the sideloaded build.
     */
    fun forceSyncNow() {
        if (_forceSyncState.value is ForceSyncState.Syncing) return
        val uid = sessionProvider.userId.value ?: return
        val hid = householdSession.householdId.value ?: return
        viewModelScope.launch {
            _forceSyncState.value = ForceSyncState.Syncing(pendingPushCount.value)
            val drained = syncEngine.flushAllPending(householdId = hid, uid = uid)
            _forceSyncState.value = if (drained) {
                ForceSyncState.SafeToUninstall
            } else {
                ForceSyncState.Failed(pendingPushCount.value)
            }
        }
    }

    /** Reset Force-sync UX back to Idle, e.g. when the user navigates away
     *  and back. */
    fun acknowledgeForceSync() {
        _forceSyncState.value = ForceSyncState.Idle
    }

    /**
     * Retry the cloud-sync pull for the currently signed-in user. Wired to
     * the "Retry" button on the cloud-sync banner. Mutex-guarded inside
     * [PullCoordinator] so a double-tap can't race itself.
     *
     * No-op when there's no active uid (banner shouldn't be visible in that
     * case anyway; defensive guard).
     */
    fun retryPull() {
        val uid = sessionProvider.userId.value ?: return
        // v0.7.0: single-member household → householdId == uid. Phase 2 will
        // publish a real householdId via HouseholdSessionProvider; until then,
        // mirror uid so the legacy retry button keeps working.
        val householdId = uid
        viewModelScope.launch {
            pullStateRepo.set(uid, PullState.IN_PROGRESS)
            val result = pullCoordinator.pullForHousehold(householdId)
            pullStateRepo.set(
                uid,
                if (result is PullCoordinator.PullResult.Success) PullState.SUCCEEDED
                else PullState.FAILED,
            )
        }
    }

    private fun snapshot(): AccountState {
        val user = auth.currentUser
        return AccountState(
            isAnonymous = user?.isAnonymous ?: true,
            email = user?.email,
            displayName = user?.displayName,
            photoUrl = user?.photoUrl?.toString(),
        )
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
