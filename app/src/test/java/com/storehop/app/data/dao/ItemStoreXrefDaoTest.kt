package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ItemStoreXrefDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: ItemStoreXrefDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.itemStoreXrefDao()
        kotlinx.coroutines.runBlocking {
            db.itemDao().upsert(
                Item(
                    id = "milk", name = "Milk", categoryId = null, notes = null,
                    quantity = null, isNeeded = true, lastPurchasedAt = null,
                    userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            listOf("store_a", "store_b", "store_c").forEach {
                db.storeDao().upsert(
                    Store(
                        id = it, name = it, colorArgb = null,
                        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    ),
                )
            }
        }
    }

    @After fun tearDown() { db.close() }

    @Test fun `setStoresForItem creates xrefs for all listed stores`() = runTest {
        dao.setStoresForItem(
            itemId = "milk",
            storeIds = setOf("store_a", "store_b"),
            userId = TEST_USER_ID,
            now = 100L,
        )
        val xrefs = dao.findForItem("milk")
        assertThat(xrefs.map { it.storeId }).containsExactly("store_a", "store_b")
        // Ownership invariant: copied from parent (the userId we passed in).
        assertThat(xrefs.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
    }

    @Test fun `setStoresForItem tombstones xrefs that are no longer in the set`() = runTest {
        dao.upsert(xref("milk", "store_a"))
        dao.upsert(xref("milk", "store_b"))
        dao.upsert(xref("milk", "store_c"))

        dao.setStoresForItem("milk", setOf("store_a", "store_c"), TEST_USER_ID, now = 200L)

        val live = dao.findForItem("milk")
        assertThat(live.map { it.storeId }).containsExactly("store_a", "store_c")
        // store_b is soft-deleted, not hard-deleted — confirm via raw count.
        val totalCount = countAll("item_store_xref", where = "itemId = 'milk'")
        assertThat(totalCount).isEqualTo(3)
    }

    @Test fun `setStoresForItem is a no-op when the set is unchanged`() = runTest {
        dao.upsert(xref("milk", "store_a"))
        dao.upsert(xref("milk", "store_b"))
        val before = dao.findForItem("milk").map { it.createdAt }.toSet()

        dao.setStoresForItem("milk", setOf("store_a", "store_b"), TEST_USER_ID, now = 999L)

        val after = dao.findForItem("milk")
        // No new xrefs created (same createdAt), nothing tombstoned.
        assertThat(after).hasSize(2)
        assertThat(after.map { it.createdAt }.toSet()).isEqualTo(before)
    }

    private fun xref(itemId: String, storeId: String, now: Long = 1L) = ItemStoreXref(
        itemId = itemId, storeId = storeId, userId = TEST_USER_ID,
        createdAt = now, updatedAt = now, deletedAt = null,
    )

    private fun countAll(table: String, where: String): Int {
        db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM $table WHERE $where"))
            .use { c -> c.moveToFirst(); return c.getInt(0) }
    }
}
