package com.storehop.app.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires [FirebaseAuth]'s mutable session state into a hot [StateFlow] that the
 * rest of the app can observe. Construction has the side effect of:
 *
 *  1. Subscribing to `FirebaseAuth.addAuthStateListener` and pushing the current
 *     uid (or null) into [_userId] every time it changes.
 *  2. Kicking off `signInAnonymously()` if no user is present, so the app gets
 *     a uid quickly on first launch and never sits in a "signed-out" state for
 *     longer than a network round-trip.
 *
 * Anonymous accounts persist across app restarts (FirebaseAuth keeps the
 * credential on disk), so this only actually calls `signInAnonymously()` on
 * very-first-launch or after a "wipe app data" reset.
 */
@Singleton
class FirebaseAuthSessionProvider @Inject constructor(
    private val auth: FirebaseAuth,
) : UserSessionProvider {

    private val _userId = MutableStateFlow(auth.currentUser?.uid)
    override val userId: StateFlow<String?> = _userId.asStateFlow()

    init {
        auth.addAuthStateListener { state ->
            val newUid = state.currentUser?.uid
            if (_userId.value != newUid) {
                Log.i(TAG, "Auth state changed: ${_userId.value} -> $newUid")
                _userId.value = newUid
            }
        }
        if (auth.currentUser == null) {
            Log.i(TAG, "No current user; signing in anonymously.")
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    Log.i(TAG, "Anonymous sign-in succeeded: ${result.user?.uid}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign-in failed", e)
                }
        }
    }

    companion object {
        private const val TAG = "FirebaseAuthSession"
    }
}
