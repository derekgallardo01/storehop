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
            val before = migrationDao.countLocalOnlyStores()
            if (before > 0) {
                Log.i(TAG, "Claiming $before local-only stores (and their cohort) to uid=$uid")
                migrationDao.claimAllLocalOnlyRowsAs(uid)
            }
            val after = migrationDao.countLocalOnlyStores()
            check(after == 0) {
                "claim migration left $after local-only stores; expected 0"
            }
        }
    }

    companion object {
        private const val TAG = "SignInBootstrapper"
    }
}
