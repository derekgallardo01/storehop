package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the all-or-nothing contract of [PullWriteDao.replaceAllForUid]. The
 * pull side relies on this — every entity from the cloud lands in the same
 * transaction, or none of them do — so upstream code never has to reason
 * about "we got the items but not the stores yet" intermediate states.
 */
@RunWith(RobolectricTestRunner::class)
class PullWriteDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: PullWriteDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = PullWriteDao(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun `replaceAllForUid lands all six entity types in a single batch`() = runTest {
        val store = store("store_lidl")
        val cat = cat("cat_dairy")
        val item = item("item_milk", categoryId = "cat_dairy")
        val xref = ItemStoreXref(
            itemId = "item_milk", storeId = "store_lidl", userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        val sco = StoreCategoryOrder(
            storeId = "store_lidl", categoryId = "cat_dairy", displayOrder = 0,
            isSeeded = false, userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        val pr = PurchaseRecord(
            id = "pr_1", itemId = "item_milk", storeId = "store_lidl",
            purchasedAt = 1L, userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )

        dao.replaceAllForUid(
            items = listOf(item),
            categories = listOf(cat),
            stores = listOf(store),
            xrefs = listOf(xref),
            scoOrders = listOf(sco),
            purchaseRecords = listOf(pr),
        )

        // Assert every one of the six entity types actually landed — this is
        // the contract the upstream code relies on. Sampling a subset would
        // miss e.g. categories or purchase records silently dropping.
        assertThat(db.categoryDao().observeAll(TEST_USER_ID, includeArchived = false).first()
            .map { it.id }).containsExactly("cat_dairy")
        assertThat(db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first()
            .map { it.id }).containsExactly("store_lidl")
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id })
            .containsExactly("item_milk")
        assertThat(db.itemStoreXrefDao().findForItem("item_milk").map { it.storeId })
            .containsExactly("store_lidl")
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl").map { it.categoryId })
            .containsExactly("cat_dairy")
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, "item_milk").first()
            .map { it.id }).containsExactly("pr_1")
    }

    @Test fun `replaceAllForUid rolls back the entire batch when an FK-violating xref is included`() = runTest {
        // A pre-existing row that should still be there if the rollback is correct.
        db.storeDao().upsert(store("store_existing"))

        val violatingXref = ItemStoreXref(
            // itemId doesn't exist in the items list, FK to items will fail.
            itemId = "ghost_item",
            storeId = "store_lidl",
            userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )

        val result = runCatching {
            dao.replaceAllForUid(
                items = emptyList(),
                categories = emptyList(),
                stores = listOf(store("store_lidl")), // would land if not rolled back
                xrefs = listOf(violatingXref),
                scoOrders = emptyList(),
                purchaseRecords = emptyList(),
            )
        }
        assertThat(result.isFailure).isTrue()

        // The pre-existing store is still there (untouched by the failed batch).
        val stores = db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id }.toSet()
        assertThat(stores).contains("store_existing")
        // The store from the failing batch did NOT land — rollback held.
        assertThat(stores).doesNotContain("store_lidl")
    }

    @Test fun `replaceAllForUid with all empty lists is a clean no-op`() = runTest {
        // Pre-existing data should remain intact when the cloud sends nothing.
        db.storeDao().upsert(store("store_pre"))
        val before = db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id }

        dao.replaceAllForUid(
            items = emptyList(), categories = emptyList(), stores = emptyList(),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        val after = db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id }
        assertThat(after).isEqualTo(before)
    }

    @Test fun `replaceAllForUid is idempotent on the upsert path`() = runTest {
        val cat = cat("cat_dairy")
        val store = store("store_lidl")

        dao.replaceAllForUid(
            items = emptyList(), categories = listOf(cat), stores = listOf(store),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )
        // Second call with the same payload — should not duplicate, should not crash.
        dao.replaceAllForUid(
            items = emptyList(), categories = listOf(cat), stores = listOf(store),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        assertThat(db.categoryDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id })
            .containsExactly("cat_dairy")
        assertThat(db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id })
            .containsExactly("store_lidl")
    }

    private fun store(id: String) = Store(
        id = id, name = id, colorArgb = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun cat(id: String) = Category(
        id = id, name = id, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun item(id: String, categoryId: String?) = Item(
        id = id, name = id, categoryId = categoryId, notes = null,
        quantity = null, isNeeded = true, lastPurchasedAt = null,
        userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
