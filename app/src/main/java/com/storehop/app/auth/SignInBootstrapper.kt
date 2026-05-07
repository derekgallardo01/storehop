package com.storehop.app.auth

import android.util.Log
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the gap between the pre-Firebase data layer (where every row carries
 * `userId = "local-only"`) and the post-Firebase one (every row tagged with a
 * real Firebase uid).
 *
 * Started once from [com.storehop.app.StorehopApplication.onCreate]:
 *  1. Waits for the [UserSessionProvider]'s first non-null uid (anonymous or Google).
 *  2. Re-stamps every `local-only` row in Room to that uid in a single transaction.
 *
 * Idempotent on subsequent app starts -- after the first run, no rows match the
 * `WHERE userId = 'local-only'` filter, so the UPDATEs are no-ops. We still log
 * the post-migration count so operational issues are visible in logcat.
 */
@Singleton
class SignInBootstrapper @Inject constructor(
    private val session: UserSessionProvider,
    private val migrationDao: LocalOnlyMigrationDao,
    private val applicationScope: CoroutineScope,
) {
    fun start() {
        applicationScope.launch {
            // Wait for the first real (non-null) uid to arrive.
            val uid = session.userId.filterNotNull().first()
            val beforeLocalOnly = migrationDao.countLocalOnlyStores()
            if (beforeLocalOnly > 0) {
                Log.i(TAG, "Claiming $beforeLocalOnly local-only stores (and their cohort) to uid=$uid")
                migrationDao.claimAllLocalOnlyRowsAs(uid)
            }
            val afterLocalOnly = migrationDao.countLocalOnlyStores()
            check(afterLocalOnly == 0) {
                "claim migration left $afterLocalOnly local-only stores; expected 0"
            }

            // Orphan-uid recovery: rows under any uid that isn't `local-only`
            // and isn't the current session uid. This happens when
            // GoogleSignInUseCase's linkWithCredential failed and fell back
            // to signInWithCredential -- the user ended up on a different
            // Firebase uid than the one their data was originally stamped
            // with. Single-user v1 assumption: claim those rows to the
            // current session so they don't stay orphaned.
            val beforeOrphans = migrationDao.countOrphanStores(uid)
            if (beforeOrphans > 0) {
                Log.i(TAG, "Claiming $beforeOrphans orphan-uid stores (and their cohort) to uid=$uid")
                migrationDao.claimAllOrphanRowsAs(uid)
            }
        }
    }

    companion object {
        private const val TAG = "SignInBootstrapper"
    }
}
