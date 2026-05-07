package com.storehop.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.auth.GoogleSignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val auth: FirebaseAuth,
    private val googleSignIn: GoogleSignInUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { _state.value = snapshot() }

    init { auth.addAuthStateListener(authListener) }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authListener)
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
