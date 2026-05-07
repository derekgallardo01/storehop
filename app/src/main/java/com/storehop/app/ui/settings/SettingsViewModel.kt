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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: FirebaseAuth,
    private val googleSignIn: GoogleSignInUseCase,
    private val userPrefs: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Theme-mode pref for the Theme section's selection state. */
    val themeMode: StateFlow<ThemeMode> = userPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

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
     */
    fun setLocale(tag: String) {
        Log.i(TAG, "setLocale(tag='$tag') sdk=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (tag.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            // The system service is application-scoped; ApplicationContext is
            // fine here. Setting locales triggers a configuration change and
            // automatic activity recreation by the system.
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
                .onSuccess { _state.value = snapshot().copy(busy = false) }
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
