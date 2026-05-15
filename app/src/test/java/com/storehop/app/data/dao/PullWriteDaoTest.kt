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
            householdId = TEST_USER_ID,
        )
        val sco = StoreCategoryOrder(
            storeId = "store_lidl", categoryId = "cat_dairy", displayOrder = 0,
            isSeeded = false, userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
            householdId = TEST_USER_ID,
        )
        val pr = PurchaseRecord(
            id = "pr_1", itemId = "item_milk", storeId = "store_lidl",
            purchasedAt = 1L, userId = TEST_USER_ID,
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
            householdId = TEST_USER_ID,
        )

        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
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
            householdId = TEST_USER_ID,
        )

        val result = runCatching {
            dao.replaceAllForUid(
                householdId = TEST_USER_ID,
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
            householdId = TEST_USER_ID,
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
            householdId = TEST_USER_ID,
            items = emptyList(), categories = listOf(cat), stores = listOf(store),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )
        // Second call with the same payload — should not duplicate, should not crash.
        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
            items = emptyList(), categories = listOf(cat), stores = listOf(store),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        assertThat(db.categoryDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id })
            .containsExactly("cat_dairy")
        assertThat(db.storeDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.id })
            .containsExactly("store_lidl")
    }

    // ----------------------------------------------------------------
    // v0.8.0.4: pull-guard for pendingSync = 1 rows.
    //
    // Mike reported a write-revert bug: unchecking a store on the
    // Items edit screen reverted because a pull landed before the push
    // shipped his soft-delete. PullWriteDao now filters cloud rows
    // whose primary key matches a local pendingSync = 1 row, so the
    // user's most-recent intent survives a pull-before-push race.
    // ----------------------------------------------------------------

    @Test fun `pending soft-deleted xref is preserved when cloud sends alive copy`() = runTest {
        // Pre-existing parents so FK constraints pass.
        db.storeDao().upsert(store("store_aldi"))
        db.itemDao().upsertFromCloud(listOf(item("item_tp", categoryId = null)))
        // Mike's local action: insert the xref alive, then "uncheck" it
        // → soft-delete via setStoresForItem → pendingSync = 1.
        db.itemStoreXrefDao().setStoresForItem(
            itemId = "item_tp",
            storeIds = setOf("store_aldi"),
            householdId = TEST_USER_ID,
            userId = TEST_USER_ID,
            now = 100L,
        )
        db.itemStoreXrefDao().setStoresForItem(
            itemId = "item_tp",
            storeIds = emptySet(), // unchecks Aldi → soft-delete
            householdId = TEST_USER_ID,
            userId = TEST_USER_ID,
            now = 200L,
        )
        // findForItem hides tombstones; read raw to see the soft-deleted row.
        val pre = readRawXref("item_tp", "store_aldi")!!
        assertThat(pre.deletedAt).isEqualTo(200L)
        assertThat(pre.pendingSync).isTrue()

        // Cloud pull replays the xref as still-alive (push hadn't fired
        // yet) — this is the race that caused the bug.
        val cloudXref = ItemStoreXref(
            itemId = "item_tp", storeId = "store_aldi", userId = TEST_USER_ID,
            createdAt = 100L, updatedAt = 100L, deletedAt = null,
            householdId = TEST_USER_ID,
        )
        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
            items = emptyList(), categories = emptyList(), stores = emptyList(),
            xrefs = listOf(cloudXref),
            scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        // Local row UNCHANGED — soft-delete preserved, pendingSync still set.
        val post = readRawXref("item_tp", "store_aldi")!!
        assertThat(post.deletedAt).isEqualTo(200L)
        assertThat(post.pendingSync).isTrue()
    }

    @Test fun `pending xref is preserved while unrelated cloud xref upserts`() = runTest {
        // Two stores so we can have both a pending xref and an unrelated cloud xref.
        db.storeDao().upsert(store("store_aldi"))
        db.storeDao().upsert(store("store_lidl"))
        db.itemDao().upsertFromCloud(listOf(item("item_tp", categoryId = null)))
        // Pending soft-delete on Aldi.
        db.itemStoreXrefDao().setStoresForItem(
            "item_tp", setOf("store_aldi"), TEST_USER_ID, TEST_USER_ID, 100L)
        db.itemStoreXrefDao().setStoresForItem(
            "item_tp", emptySet(), TEST_USER_ID, TEST_USER_ID, 200L)

        // Pull brings two cloud xrefs: one matches the pending PK (should
        // skip), one is a fresh Lidl xref (should land).
        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
            items = emptyList(), categories = emptyList(), stores = emptyList(),
            xrefs = listOf(
                ItemStoreXref(
                    itemId = "item_tp", storeId = "store_aldi", userId = TEST_USER_ID,
                    createdAt = 100L, updatedAt = 100L, deletedAt = null,
                    householdId = TEST_USER_ID,
                ),
                ItemStoreXref(
                    itemId = "item_tp", storeId = "store_lidl", userId = TEST_USER_ID,
                    createdAt = 100L, updatedAt = 100L, deletedAt = null,
                    householdId = TEST_USER_ID,
                ),
            ),
            scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        // Aldi: local pending soft-delete preserved (tombstone — read raw).
        val aldi = readRawXref("item_tp", "store_aldi")!!
        assertThat(aldi.deletedAt).isEqualTo(200L)
        // Lidl: cloud row landed (no pending edit on this PK).
        val lidl = readRawXref("item_tp", "store_lidl")!!
        assertThat(lidl.deletedAt).isNull()
    }

    @Test fun `non-pending xref is overwritten by cloud as before`() = runTest {
        db.storeDao().upsert(store("store_aldi"))
        db.itemDao().upsertFromCloud(listOf(item("item_tp", categoryId = null)))
        // Insert the xref then markPushed so pendingSync = 0 (simulating
        // a steady-state row, no local edit waiting to ship).
        db.itemStoreXrefDao().setStoresForItem(
            "item_tp", setOf("store_aldi"), TEST_USER_ID, TEST_USER_ID, 100L)
        db.itemStoreXrefDao().markPushed(TEST_USER_ID, "item_tp", "store_aldi")
        assertThat(readRawXref("item_tp", "store_aldi")!!.pendingSync).isFalse()

        // Cloud row says the xref is soft-deleted (Amanda deleted it on
        // her device). Pull should apply this update — no local pending
        // edit to preserve.
        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
            items = emptyList(), categories = emptyList(), stores = emptyList(),
            xrefs = listOf(
                ItemStoreXref(
                    itemId = "item_tp", storeId = "store_aldi", userId = TEST_USER_ID,
                    createdAt = 100L, updatedAt = 300L, deletedAt = 300L,
                    householdId = TEST_USER_ID,
                ),
            ),
            scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        val post = readRawXref("item_tp", "store_aldi")!!
        assertThat(post.deletedAt).isEqualTo(300L) // cloud's soft-delete landed
    }

    @Test fun `pending item is preserved when cloud sends conflicting copy`() = runTest {
        // Item with a pending local edit (renamed locally).
        val itemLocal = item("item_tp", categoryId = null).copy(
            name = "Toilet Paper (Mike-edited)",
            updatedAt = 200L,
            pendingSync = true,
        )
        db.itemDao().upsert(itemLocal)

        // Cloud sends the older name back. Pull-guard should skip.
        val itemCloud = item("item_tp", categoryId = null).copy(name = "Toilet Paper")
        dao.replaceAllForUid(
            householdId = TEST_USER_ID,
            items = listOf(itemCloud),
            categories = emptyList(), stores = emptyList(),
            xrefs = emptyList(), scoOrders = emptyList(), purchaseRecords = emptyList(),
        )

        val post = db.itemDao().observeAll(TEST_USER_ID).first().single().item
        // Local pending edit preserved.
        assertThat(post.name).isEqualTo("Toilet Paper (Mike-edited)")
        assertThat(post.pendingSync).isTrue()
    }

    private fun store(id: String) = Store(
        id = id, name = id, colorArgb = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
        householdId = TEST_USER_ID,
    )

    private fun cat(id: String) = Category(
        id = id, name = id, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
        householdId = TEST_USER_ID,
    )

    private fun item(id: String, categoryId: String?) = Item(
        id = id, name = id, categoryId = categoryId, notes = null,
        quantity = null, isNeeded = true, lastPurchasedAt = null,
        userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        householdId = TEST_USER_ID,
    )

    /**
     * Raw read of an xref row including soft-deletes — bypasses the
     * deletedAt-filtered queries on `ItemStoreXrefDao.findForItem`. The
     * pull-guard tests need to assert against tombstoned rows, which
     * the standard DAO methods deliberately hide.
     */
    private data class RawXref(val deletedAt: Long?, val pendingSync: Boolean)

    private fun readRawXref(itemId: String, storeId: String): RawXref? {
        db.openHelper.readableDatabase.query(
            "SELECT deletedAt, pendingSync FROM item_store_xref WHERE itemId = ? AND storeId = ?",
            arrayOf(itemId, storeId),
        ).use { c ->
            return if (c.moveToFirst()) {
                RawXref(
                    deletedAt = if (c.isNull(0)) null else c.getLong(0),
                    pendingSync = c.getInt(1) == 1,
                )
            } else {
                null
            }
        }
    }
}
