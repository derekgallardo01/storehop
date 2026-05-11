package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
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
                    householdId = TEST_USER_ID,
                ),
            )
            listOf("store_a", "store_b", "store_c").forEach {
                db.storeDao().upsert(
                    Store(
                        id = it, name = it, colorArgb = null,
                        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                        householdId = TEST_USER_ID,
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
            householdId = TEST_USER_ID,
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

        dao.setStoresForItem("milk", setOf("store_a", "store_c"), TEST_USER_ID, TEST_USER_ID, now = 200L)

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

        dao.setStoresForItem("milk", setOf("store_a", "store_b"), TEST_USER_ID, TEST_USER_ID, now = 999L)

        val after = dao.findForItem("milk")
        // No new xrefs created (same createdAt), nothing tombstoned.
        assertThat(after).hasSize(2)
        assertThat(after.map { it.createdAt }.toSet()).isEqualTo(before)
    }

    @Test fun `markPurchasedAcrossAllStores flips every live xref for the item`() = runTest {
        dao.upsert(xref("milk", "store_a"))
        dao.upsert(xref("milk", "store_b"))
        dao.upsert(xref("milk", "store_c"))

        dao.markPurchasedAcrossAllStores(TEST_USER_ID, "milk", now = 5_000L)

        val live = dao.findForItem("milk").associateBy { it.storeId }
        assertThat(live).hasSize(3)
        live.values.forEach {
            assertThat(it.isNeeded).isFalse()
            assertThat(it.lastPurchasedAt).isEqualTo(5_000L)
            assertThat(it.updatedAt).isEqualTo(5_000L)
            assertThat(it.pendingSync).isTrue()
        }
    }

    @Test fun `markPurchasedAcrossAllStores skips tombstoned xrefs`() = runTest {
        dao.upsert(xref("milk", "store_a"))
        dao.upsert(xref("milk", "store_b").copy(deletedAt = 1L))

        dao.markPurchasedAcrossAllStores(TEST_USER_ID, "milk", now = 5_000L)

        val all = db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT storeId, isNeeded, deletedAt FROM item_store_xref WHERE itemId='milk'",
            ))
        all.use { c ->
            while (c.moveToNext()) {
                val storeId = c.getString(0)
                val isNeeded = c.getInt(1) == 1
                val deletedAt = if (c.isNull(2)) null else c.getLong(2)
                if (storeId == "store_a") {
                    assertThat(isNeeded).isFalse()
                    assertThat(deletedAt).isNull()
                } else {
                    // The tombstoned row must stay tombstoned and not flipped.
                    assertThat(deletedAt).isEqualTo(1L)
                }
            }
        }
    }

    @Test fun `restorePurchaseAcrossAllStores flips back only xrefs with the matching lastPurchasedAt`() = runTest {
        // Two purchases at different times for the same item. Undo of purchase #1
        // must NOT touch the rows flipped by purchase #2.
        dao.upsert(xref("milk", "store_a"))
        dao.upsert(xref("milk", "store_b"))
        dao.upsert(xref("milk", "store_c"))

        dao.markPurchasedAcrossAllStores(TEST_USER_ID, "milk", now = 1_000L)
        // User restored A only (manual un-check) and then purchased it again later.
        dao.markNeededAtStore(TEST_USER_ID, "milk", "store_a", now = 1_500L)
        dao.markPurchasedAtStore(TEST_USER_ID, "milk", "store_a", now = 2_000L)

        // Undo of the FIRST cascade: should only restore B and C (lastPurchasedAt=1000),
        // leaving A (lastPurchasedAt=2000) alone.
        dao.restorePurchaseAcrossAllStores(TEST_USER_ID, "milk", lastPurchasedAt = 1_000L, now = 9_000L)

        val live = dao.findForItem("milk").associateBy { it.storeId }
        assertThat(live.getValue("store_a").isNeeded).isFalse()
        assertThat(live.getValue("store_a").lastPurchasedAt).isEqualTo(2_000L)
        assertThat(live.getValue("store_b").isNeeded).isTrue()
        assertThat(live.getValue("store_b").lastPurchasedAt).isNull()
        assertThat(live.getValue("store_c").isNeeded).isTrue()
        assertThat(live.getValue("store_c").lastPurchasedAt).isNull()
    }

    // ---- v0.6.1: bulk-needed + observeNeededItemIds for the +/- toggle -----

    @Test fun `markNeededAcrossAllStores updates only alive xrefs for the target item+household`() = runTest {
        // Live xrefs at two stores for the target household, plus a tombstoned
        // one at a third, plus an unrelated household's xref. Only the two live
        // rows for the target should be flipped.
        dao.upsert(xref("milk", "store_a", now = 1L).copy(isNeeded = false, lastPurchasedAt = 100L))
        dao.upsert(xref("milk", "store_b", now = 1L).copy(isNeeded = false, lastPurchasedAt = 100L))
        dao.upsert(
            xref("milk", "store_c", now = 1L)
                .copy(isNeeded = false, lastPurchasedAt = 100L, deletedAt = 50L),
        )
        // Insert the foreign-household Item first (FK), then its xref. Reuses
        // store_c as the FK target -- the FK is on storeId alone, not
        // (storeId, householdId).
        db.itemDao().upsert(
            Item(
                id = "milk_other", name = "Milk", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "OTHER_USER", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = "OTHER_USER",
            ),
        )
        dao.upsert(
            ItemStoreXref(
                itemId = "milk_other", storeId = "store_c", userId = "OTHER_USER",
                isNeeded = false, lastPurchasedAt = 100L,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = "OTHER_USER",
            ),
        )

        dao.markNeededAcrossAllStores(TEST_USER_ID, "milk", now = 9_000L)

        // Two live rows flipped to needed + lastPurchasedAt cleared.
        val targetLive = dao.findForItem("milk")
            .filter { it.householdId == TEST_USER_ID }
            .associateBy { it.storeId }
        assertThat(targetLive["store_a"]!!.isNeeded).isTrue()
        assertThat(targetLive["store_a"]!!.lastPurchasedAt).isNull()
        assertThat(targetLive["store_a"]!!.updatedAt).isEqualTo(9_000L)
        assertThat(targetLive["store_b"]!!.isNeeded).isTrue()
        assertThat(targetLive["store_b"]!!.lastPurchasedAt).isNull()

        // Tombstoned row untouched (verify directly via raw query).
        assertThat(countAll("item_store_xref",
            "itemId='milk' AND storeId='store_c' AND deletedAt IS NOT NULL AND isNeeded=0")).isEqualTo(1)

        // Foreign household's xref untouched (now keyed on the milk_other item).
        assertThat(countAll("item_store_xref",
            "itemId='milk_other' AND householdId='OTHER_USER' AND isNeeded=0")).isEqualTo(1)
    }

    @Test fun `observeNeededItemIds returns DISTINCT itemIds where alive AND isNeeded=1`() = runTest {
        // Seed extra Items (the @Before only seeds "milk").
        listOf("bread", "eggs", "cheese", "foreignItem").forEach { id ->
            db.itemDao().upsert(
                Item(
                    id = id, name = id, categoryId = null, notes = null,
                    quantity = null, isNeeded = true, lastPurchasedAt = null,
                    userId = if (id == "foreignItem") "OTHER_USER" else TEST_USER_ID,
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = if (id == "foreignItem") "OTHER_USER" else TEST_USER_ID,
                ),
            )
        }

        // milk: needed at A, not-needed at B (still surfaces because A is needed).
        dao.upsert(xref("milk", "store_a", now = 1L))
        dao.upsert(xref("milk", "store_b", now = 1L).copy(isNeeded = false))
        // bread: needed at A only.
        dao.upsert(xref("bread", "store_a", now = 1L))
        // eggs: tombstoned needed xref + alive not-needed xref. Should NOT surface.
        dao.upsert(xref("eggs", "store_a", now = 1L).copy(deletedAt = 50L))
        dao.upsert(xref("eggs", "store_b", now = 1L).copy(isNeeded = false))
        // cheese: ALL xrefs not-needed. Should NOT surface.
        dao.upsert(xref("cheese", "store_a", now = 1L).copy(isNeeded = false))
        // foreignItem: needed but for a different household. Should NOT surface.
        dao.upsert(
            ItemStoreXref(
                itemId = "foreignItem", storeId = "store_a", userId = "OTHER_USER",
                isNeeded = true, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = "OTHER_USER",
            ),
        )

        val ids = dao.observeNeededItemIds(TEST_USER_ID).first()
        assertThat(ids.toSet()).containsExactly("milk", "bread")
    }

    private fun xref(itemId: String, storeId: String, now: Long = 1L) = ItemStoreXref(
        itemId = itemId, storeId = storeId, userId = TEST_USER_ID,
        createdAt = now, updatedAt = now, deletedAt = null,
        householdId = TEST_USER_ID,
    )

    private fun countAll(table: String, where: String): Int {
        db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM $table WHERE $where"))
            .use { c -> c.moveToFirst(); return c.getInt(0) }
    }
}
