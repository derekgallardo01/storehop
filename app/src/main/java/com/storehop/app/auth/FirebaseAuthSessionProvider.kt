package com.storehop.app.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires [FirebaseAuth]'s mutable session state into a hot [StateFlow] that the
 * rest of the app can observe. Construction has the side effect of:
 *
 *  1. Subscribing to `FirebaseAuth.addAuthStateListener` and pushing the current
 *     uid into an internal pipe.
 *  2. Kicking off `signInAnonymously()` if no user is present, so the app gets
 *     a uid quickly on first launch and never sits in a "signed-out" state for
 *     longer than a network round-trip.
 *  3. Running the local-only and orphan-uid claim migrations *before* the public
 *     [userId] StateFlow flips to a new uid -- so any downstream observer (Shop
 *     screens, repositories) never sees a window where the uid has changed but
 *     the rows haven't been re-stamped yet. That window is what produced the
 *     brief empty-state flicker on the Shop screen right after sign-in.
 *
 * Anonymous accounts persist across app restarts (FirebaseAuth keeps the
 * credential on disk), so this only actually calls `signInAnonymously()` on
 * very-first-launch or after a "wipe app data" reset.
 */
@Singleton
class FirebaseAuthSessionProvider @Inject constructor(
    private val auth: FirebaseAuth,
    private val migrationDao: LocalOnlyMigrationDao,
    private val applicationScope: CoroutineScope,
) : UserSessionProvider {

    /**
     * Raw pipe from the auth listener. Internal-only -- observers see the gated
     * [userId] flow below, which only flips after migration completes.
     */
    private val rawUid = MutableStateFlow(auth.currentUser?.uid)

    /**
     * Initialized to the disk-cached uid (returning users) so observers don't
     * see `null` on cold launch. Subsequent changes are gated behind a claim
     * migration: when the auth listener flips to a new uid, we re-stamp every
     * orphan row onto it BEFORE updating this flow, so the next observer
     * re-query already finds rows under the new uid.
     */
    private val _userId = MutableStateFlow(auth.currentUser?.uid)
    override val userId: StateFlow<String?> = _userId.asStateFlow()

    init {
        auth.addAuthStateListener { state ->
            val newUid = state.currentUser?.uid
            if (rawUid.value != newUid) {
                Log.i(TAG, "Auth state changed: ${rawUid.value} -> $newUid")
                rawUid.value = newUid
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

        // Gate every uid change behind the claim migration. Runs in
        // applicationScope so the work survives any single ViewModel's
        // lifetime, and serially via collect so two rapid auth changes can't
        // overlap their migrations.
        applicationScope.launch {
            // StateFlow already drops duplicate emissions; no distinctUntilChanged needed.
            rawUid.collect { newUid ->
                    if (newUid == null) {
                        // Sign-out: nothing to migrate, just propagate the null.
                        if (_userId.value != null) {
                            Log.i(TAG, "Signed out; clearing userId")
                            _userId.value = null
                        }
                        return@collect
                    }
                    // Skip the no-op case where the gated flow already matches
                    // (e.g. cold-launch initial value lined up with the
                    // listener's first emission).
                    if (_userId.value == newUid) return@collect

                    runClaimsFor(newUid)
                    _userId.value = newUid
                }
        }
    }

    /**
     * Re-stamp local-only and orphan-uid rows onto the active uid. Catches
     * exceptions so a migration failure (corrupt DB, etc.) doesn't permanently
     * trap the app on the previous uid -- we still publish the new uid and let
     * the user proceed; the data is just invisible until the next migration
     * attempt succeeds.
     */
    private suspend fun runClaimsFor(uid: String) {
        try {
            val localOnly = migrationDao.countLocalOnlyStores()
            if (localOnly > 0) {
                Log.i(TAG, "Claiming $localOnly local-only stores (and cohort) to uid=$uid")
                migrationDao.claimAllLocalOnlyRowsAs(uid)
            }
            val orphans = migrationDao.countOrphanStores(uid)
            if (orphans > 0) {
                Log.i(TAG, "Claiming $orphans orphan-uid stores (and cohort) to uid=$uid")
                migrationDao.claimAllOrphanRowsAs(uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Claim migration failed for uid=$uid; publishing uid anyway", e)
        }
    }

    companion object {
        private const val TAG = "FirebaseAuthSession"
    }
}
