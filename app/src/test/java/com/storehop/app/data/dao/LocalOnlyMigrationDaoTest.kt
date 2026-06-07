package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.util.LocalOnlyUserSessionProvider.Companion.LOCAL_ONLY
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the data-recovery DAO that re-stamps rows onto the active uid when
 * the auth state changes. This codepath protects user data through
 * anonymous-to-Google sign-in and through the linkWithCredential->
 * signInWithCredential fallback. A bug here would silently make stores +
 * items + purchase history disappear on sign-in.
 */
@RunWith(RobolectricTestRunner::class)
class LocalOnlyMigrationDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: LocalOnlyMigrationDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.localOnlyMigrationDao()
    }

    @After fun tearDown() { db.close() }

    // -------- local-only -> uid --------

    @Test fun `claimAllLocalOnlyRowsAs re-stamps every entity from local-only to the target uid`() = runTest {
        seedFullCohort(LOCAL_ONLY)

        dao.claimAllLocalOnlyRowsAs(NEW_UID)

        // Every table should now be under NEW_UID.
        assertAllRowsUnder(NEW_UID)
        // No local-only rows survive.
        assertThat(dao.countLocalOnlyStores()).isEqualTo(0)
    }

    @Test fun `claimAllLocalOnlyRowsAs leaves rows under other uids alone`() = runTest {
        seedStore(id = "s_local", userId = LOCAL_ONLY)
        seedStore(id = "s_other", userId = OTHER_UID)

        dao.claimAllLocalOnlyRowsAs(NEW_UID)

        assertThat(storeUserId("s_local")).isEqualTo(NEW_UID)
        assertThat(storeUserId("s_other")).isEqualTo(OTHER_UID) // untouched
    }

    @Test fun `claimAllLocalOnlyRowsAs is a no-op when nothing is local-only`() = runTest {
        seedStore(id = "s_other", userId = OTHER_UID)

        dao.claimAllLocalOnlyRowsAs(NEW_UID)

        assertThat(storeUserId("s_other")).isEqualTo(OTHER_UID)
        assertThat(dao.countOrphanStores(NEW_UID)).isEqualTo(1)
    }

    @Test fun `claimAllLocalOnlyRowsAs refuses to claim back to the local-only sentinel`() = runTest {
        seedStore(id = "s_local", userId = LOCAL_ONLY)
        var caught: Exception? = null
        try {
            dao.claimAllLocalOnlyRowsAs(LOCAL_ONLY)
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertThat(caught).isNotNull()
        // Row should still be local-only.
        assertThat(storeUserId("s_local")).isEqualTo(LOCAL_ONLY)
    }

    // -------- orphan-uid -> uid --------

    @Test fun `claimAllOrphanRowsAs re-stamps rows from a previous uid onto the new uid`() = runTest {
        // Simulates the linkWithCredential fallback path: old anonymous uid's
        // rows are still in the DB tagged with the previous uid when we sign
        // in to a different Firebase user.
        seedFullCohort(OTHER_UID)

        dao.claimAllOrphanRowsAs(NEW_UID)

        assertAllRowsUnder(NEW_UID)
        assertThat(dao.countOrphanStores(NEW_UID)).isEqualTo(0)
    }

    @Test fun `claimAllOrphanRowsAs preserves local-only rows`() = runTest {
        // local-only is the pre-Firebase sentinel. The orphan claim should NOT
        // touch it -- the local-only claim is a separate code path that runs
        // first; the orphan claim then has to leave any straggling local-only
        // row alone (defensive: there shouldn't be any, but if there is,
        // don't repurpose it).
        seedStore(id = "s_local", userId = LOCAL_ONLY)
        seedStore(id = "s_orphan", userId = OTHER_UID)

        dao.claimAllOrphanRowsAs(NEW_UID)

        assertThat(storeUserId("s_local")).isEqualTo(LOCAL_ONLY)
        assertThat(storeUserId("s_orphan")).isEqualTo(NEW_UID)
    }

    @Test fun `claimAllOrphanRowsAs leaves rows already under target uid alone`() = runTest {
        seedStore(id = "s_target", userId = NEW_UID)
        seedStore(id = "s_orphan", userId = OTHER_UID)

        dao.claimAllOrphanRowsAs(NEW_UID)

        assertThat(storeUserId("s_target")).isEqualTo(NEW_UID)
        assertThat(storeUserId("s_orphan")).isEqualTo(NEW_UID)
        assertThat(dao.countOrphanStores(NEW_UID)).isEqualTo(0)
    }

    @Test fun `claimAllOrphanRowsAs is idempotent`() = runTest {
        seedStore(id = "s_orphan", userId = OTHER_UID)

        dao.claimAllOrphanRowsAs(NEW_UID)
        dao.claimAllOrphanRowsAs(NEW_UID) // second run should be a no-op

        assertThat(storeUserId("s_orphan")).isEqualTo(NEW_UID)
        assertThat(dao.countOrphanStores(NEW_UID)).isEqualTo(0)
    }

    @Test fun `claimAllOrphanRowsAs refuses to claim back to the local-only sentinel`() = runTest {
        seedStore(id = "s_orphan", userId = OTHER_UID)
        var caught: Exception? = null
        try {
            dao.claimAllOrphanRowsAs(LOCAL_ONLY)
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(storeUserId("s_orphan")).isEqualTo(OTHER_UID)
    }

    @Test fun `countOrphanStores excludes local-only and target uid`() = runTest {
        seedStore(id = "s_local", userId = LOCAL_ONLY)
        seedStore(id = "s_target", userId = NEW_UID)
        seedStore(id = "s_orphan_a", userId = OTHER_UID)
        seedStore(id = "s_orphan_b", userId = "yet-another-uid")

        assertThat(dao.countOrphanStores(NEW_UID)).isEqualTo(2)
    }

    // -------- helpers --------

    /** Inserts one row in every synced table, all tagged with [userId]. */
    private suspend fun seedFullCohort(userId: String) {
        seedStore(id = "s1", userId = userId)
        seedCategory(id = "c1", userId = userId)
        seedItem(id = "i1", userId = userId)
        seedXref(itemId = "i1", storeId = "s1", userId = userId)
        seedSco(storeId = "s1", categoryId = "c1", userId = userId)
        seedPurchase(id = "p1", itemId = "i1", storeId = "s1", userId = userId)
    }

    private suspend fun seedStore(id: String, userId: String) {
        db.storeDao().upsert(
            Store(
                id = id, name = id, colorArgb = null,
                isArchived = false, isSeeded = false, userId = userId,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private suspend fun seedCategory(id: String, userId: String) {
        db.categoryDao().upsert(
            Category(
                id = id, name = id, nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = userId,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private suspend fun seedItem(id: String, userId: String) {
        db.itemDao().upsert(
            Item(
                id = id, name = id, categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = userId, createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private suspend fun seedXref(itemId: String, storeId: String, userId: String) {
        db.itemStoreXrefDao().upsert(
            ItemStoreXref(
                itemId = itemId, storeId = storeId, userId = userId,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private suspend fun seedSco(storeId: String, categoryId: String, userId: String) {
        db.storeCategoryOrderDao().upsert(
            StoreCategoryOrder(
                storeId = storeId, categoryId = categoryId, displayOrder = 0,
                isSeeded = false, userId = userId,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private suspend fun seedPurchase(id: String, itemId: String, storeId: String, userId: String) {
        db.purchaseRecordDao().insert(
            PurchaseRecord(
                id = id, itemId = itemId, storeId = storeId,
                purchasedAt = 1L, userId = userId,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    /** Verify every synced table has exactly one row, all under [uid]. */
    private fun assertAllRowsUnder(uid: String) {
        listOf(
            "items", "categories", "stores",
            "item_store_xref", "store_category_order", "purchase_records",
        ).forEach { table ->
            val rows = userIdsIn(table)
            assertThat(rows).hasSize(1)
            assertThat(rows.toSet()).containsExactly(uid)
        }
    }

    private fun storeUserId(id: String): String {
        db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT userId FROM stores WHERE id = ?",
                arrayOf<Any>(id),
            ))
            .use { c ->
                check(c.moveToFirst()) { "no store row for id=$id" }
                return c.getString(0)
            }
    }

    private fun userIdsIn(table: String): List<String> {
        db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT userId FROM $table"))
            .use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) out += c.getString(0)
                return out
            }
    }

    companion object {
        private const val NEW_UID = "uid-google"
        private const val OTHER_UID = "uid-anon-prev"
    }
}
