package com.storehop.app.auth

import android.util.Log
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the gap between the pre-Firebase data layer (where every row carries
 * `userId = "local-only"`) and the post-Firebase one (every row tagged with a
 * real Firebase uid). Also handles in-session uid switches (Google Sign-In's
 * linkWithCredential -> signInWithCredential fallback path).
 *
 * Started once from [com.storehop.app.StorehopApplication.onCreate]; collects
 * every distinct non-null uid the session emits and runs two claims per uid:
 *  1. local-only -> uid (only finds rows on first launch after a fresh install)
 *  2. orphan-uid -> uid (catches data left under a previous uid after an auth
 *     state change, e.g. anonymous-then-Google or Google-account-switch)
 *
 * Idempotent: when no matching rows exist, both UPDATEs are no-ops.
 */
@Singleton
class SignInBootstrapper @Inject constructor(
    private val session: UserSessionProvider,
    private val migrationDao: LocalOnlyMigrationDao,
    private val applicationScope: CoroutineScope,
) {
    fun start() {
        applicationScope.launch {
            // Listen for every uid the session emits, not just the first.
            // This matters when Google Sign-In flips the uid mid-session
            // (linkWithCredential fails, signInWithCredential succeeds
            // under a different uid -- the data we just claimed under the
            // anonymous uid is suddenly orphaned). distinctUntilChanged
            // skips duplicate emissions; the work below only runs when the
            // active uid actually changes.
            session.userId
                .filterNotNull()
                .distinctUntilChanged()
                .collect { uid ->
                    // local-only -> uid: only finds rows on the very first
                    // run after a fresh install (DatabaseSeeder writes
                    // local-only rows on DB create). After that, this is a
                    // no-op for every subsequent uid change.
                    val beforeLocalOnly = migrationDao.countLocalOnlyStores()
                    if (beforeLocalOnly > 0) {
                        Log.i(TAG, "Claiming $beforeLocalOnly local-only stores (and their cohort) to uid=$uid")
                        migrationDao.claimAllLocalOnlyRowsAs(uid)
                    }
                    val afterLocalOnly = migrationDao.countLocalOnlyStores()
                    check(afterLocalOnly == 0) {
                        "claim migration left $afterLocalOnly local-only stores; expected 0"
                    }

                    // orphan-uid -> uid: every time the active uid changes,
                    // re-stamp any rows still under a previous uid onto the
                    // new one. Single-user v1 assumption: all data on the
                    // device is by definition the active user's, so orphans
                    // from auth-flow edge cases (linkWithCredential fallback,
                    // Google account switch, anonymous-then-signed-in) all
                    // get carried over rather than being silently lost.
                    val beforeOrphans = migrationDao.countOrphanStores(uid)
                    if (beforeOrphans > 0) {
                        Log.i(TAG, "Claiming $beforeOrphans orphan-uid stores (and their cohort) to uid=$uid")
                        migrationDao.claimAllOrphanRowsAs(uid)
                    }
                }
        }
    }

    companion object {
        private const val TAG = "SignInBootstrapper"
    }
}
