package com.storehop.app.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 *  3. Running the appropriate sync flow for each uid change *before* the public
 *     [userId] StateFlow flips. v0.4 adds a peek + branch:
 *     - If cloud has data for this uid → pull (skip orphan-claim; cloud wins).
 *     - If cloud is empty → orphan-claim (existing path; push afterwards
 *       populates the cloud).
 *     This closes the silent-corruption bug where a fresh-install user signing
 *     in to an existing Google account would have their cloud data overwritten
 *     by the locally-seeded baseline.
 *
 * Anonymous accounts persist across app restarts (FirebaseAuth keeps the
 * credential on disk), so this only actually calls `signInAnonymously()` on
 * very-first-launch or after a "wipe app data" reset.
 */
@Singleton
class FirebaseAuthSessionProvider @Inject constructor(
    private val auth: FirebaseAuth,
    private val migrationDao: LocalOnlyMigrationDao,
    private val pullCoordinator: PullCoordinator,
    private val pullStateRepo: PullStateRepository,
    private val applicationScope: CoroutineScope,
) : UserSessionProvider {

    /**
     * Raw pipe from the auth listener. Internal-only -- observers see the gated
     * [userId] flow below, which only flips after sync completes.
     */
    private val rawUid = MutableStateFlow(auth.currentUser?.uid)

    /**
     * Initialized to the disk-cached uid (returning users) so observers don't
     * see `null` on cold launch. Subsequent changes are gated behind a sync
     * step (pull or claim): when the auth listener flips to a new uid, we run
     * the appropriate sync BEFORE updating this flow, so the next observer
     * re-query already finds the right rows under the new uid.
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

        // Gate every uid change behind the sync step. Runs in applicationScope
        // so the work survives any single ViewModel's lifetime, and serially
        // via collect so two rapid auth changes can't overlap.
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
                // Skip only when this uid has already been fully synced
                // (SUCCEEDED). For the cold-launch initial-value case where
                // _userId.value already matches but pullState is NEEDED,
                // FAILED, or stuck IN_PROGRESS (process died mid-pull), we
                // still need to run sync -- otherwise push stays paused
                // forever and the user accumulates dirty rows that never
                // ship to cloud.
                if (_userId.value == newUid) {
                    val state = pullStateRepo.observe(newUid).first()
                    if (state == PullState.SUCCEEDED) return@collect
                    Log.i(TAG, "Re-running sync for uid=$newUid (pullState=$state)")
                }

                runSyncFor(newUid)
                _userId.value = newUid
            }
        }
    }

    /**
     * v0.4: peek Firestore. If cloud has data → pull (cloud is authoritative;
     * skip orphan-claim entirely). If cloud is empty → existing claim path.
     *
     * Always sets `pullState` before returning; the [com.storehop.app.sync.SyncEngine]
     * gates push jobs on this state, so push is paused until the state lands
     * on `SUCCEEDED`. A failure here means the user signs in but pushes are
     * paused — Settings shows a Retry banner.
     */
    private suspend fun runSyncFor(uid: String) {
        // v0.7.0: until Phase 2 wires the real first-launch bootstrap,
        // single-member households mirror uid → householdId. The peek + pull
        // hit `/users/{householdId}/...` on the wire, which equals
        // `/users/{uid}/...` here, so existing v0.6.x cloud data stays at
        // the same path. Phase 2 will publish the resolved household id via
        // HouseholdSessionProvider so this provider can read it from there
        // rather than aliasing it from uid.
        val householdId = uid
        pullStateRepo.set(uid, PullState.IN_PROGRESS)
        try {
            val cloudHasData = pullCoordinator.peek(householdId)
            if (cloudHasData) {
                Log.i(TAG, "Cloud has data for hid=$householdId; pulling")
                val result = pullCoordinator.pullForHousehold(householdId)
                val finalState = when (result) {
                    is PullCoordinator.PullResult.Success -> PullState.SUCCEEDED
                    is PullCoordinator.PullResult.Failure -> PullState.FAILED
                }
                pullStateRepo.set(uid, finalState)
            } else {
                Log.i(TAG, "Cloud is empty for hid=$householdId; running orphan-claim path")
                runClaimsFor(uid)
                pullStateRepo.set(uid, PullState.SUCCEEDED)
            }
        } catch (e: Exception) {
            // Peek failure or unexpected error during sync. Fail closed:
            // pullState=FAILED keeps push paused so seeded data can't leak
            // to the cloud. The user sees a Retry banner.
            Log.e(TAG, "Sync failed for uid=$uid", e)
            pullStateRepo.set(uid, PullState.FAILED)
        }
    }

    /**
     * Re-stamp local-only and orphan-uid rows onto the active uid. Used when
     * the cloud is empty (first-ever Google sign-in for this account) -- those
     * rows were either pre-Firebase data or under a previous anonymous uid,
     * and now need to be tagged to the active user so the push side can
     * publish them.
     *
     * Catches exceptions so a migration failure (corrupt DB, etc.) doesn't
     * permanently trap the app on the previous uid -- we still proceed; the
     * data is just invisible until the next attempt succeeds.
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
