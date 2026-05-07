package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.storehop.app.data.util.LocalOnlyUserSessionProvider

/**
 * One-shot migration that re-stamps every row whose `userId` is the pre-Firebase
 * `"local-only"` sentinel onto the first real Firebase uid the device acquires.
 *
 * Runs from [com.storehop.app.auth.SignInBootstrapper] after the StateFlow's
 * first non-null emission. Idempotent -- subsequent runs find no `local-only`
 * rows and no-op.
 */
@Dao
interface LocalOnlyMigrationDao {

    @Transaction
    suspend fun claimAllLocalOnlyRowsAs(uid: String) {
        require(uid != LocalOnlyUserSessionProvider.LOCAL_ONLY) {
            "Cannot claim local-only rows back to the local-only sentinel"
        }
        claimItems(uid)
        claimCategories(uid)
        claimStores(uid)
        claimItemStoreXrefs(uid)
        claimStoreCategoryOrders(uid)
        claimPurchaseRecords(uid)
    }

    @Query("UPDATE items SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimItems(uid: String)

    @Query("UPDATE categories SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimCategories(uid: String)

    @Query("UPDATE stores SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimStores(uid: String)

    @Query("UPDATE item_store_xref SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimItemStoreXrefs(uid: String)

    @Query("UPDATE store_category_order SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimStoreCategoryOrders(uid: String)

    @Query("UPDATE purchase_records SET userId = :uid WHERE userId = 'local-only'")
    suspend fun claimPurchaseRecords(uid: String)

    @Query("SELECT COUNT(*) FROM stores WHERE userId = 'local-only'")
    suspend fun countLocalOnlyStores(): Int

    /**
     * Re-stamp every row whose `userId` is neither `'local-only'` nor the
     * current session uid onto the current session uid. Single-user v1
     * assumption: all data on the device is by definition the active user's,
     * so any orphan-uid data (left behind when `linkWithCredential` failed
     * and the sign-in flow fell back to a plain `signInWithCredential` under
     * a different uid -- see GoogleSignInUseCase for the failure mode) is
     * safely claimable to the current session.
     *
     * Idempotent: when no orphan rows exist, the UPDATEs are no-ops.
     */
    @Transaction
    suspend fun claimAllOrphanRowsAs(uid: String) {
        require(uid != LocalOnlyUserSessionProvider.LOCAL_ONLY) {
            "Cannot claim orphan rows back to the local-only sentinel"
        }
        claimOrphanItems(uid)
        claimOrphanCategories(uid)
        claimOrphanStores(uid)
        claimOrphanItemStoreXrefs(uid)
        claimOrphanStoreCategoryOrders(uid)
        claimOrphanPurchaseRecords(uid)
    }

    @Query("UPDATE items SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanItems(uid: String)

    @Query("UPDATE categories SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanCategories(uid: String)

    @Query("UPDATE stores SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanStores(uid: String)

    @Query("UPDATE item_store_xref SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanItemStoreXrefs(uid: String)

    @Query("UPDATE store_category_order SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanStoreCategoryOrders(uid: String)

    @Query("UPDATE purchase_records SET userId = :uid WHERE userId != :uid AND userId != 'local-only'")
    suspend fun claimOrphanPurchaseRecords(uid: String)

    /** Count of stores under any uid other than local-only AND the current session uid. */
    @Query("SELECT COUNT(*) FROM stores WHERE userId != :uid AND userId != 'local-only'")
    suspend fun countOrphanStores(uid: String): Int
}
