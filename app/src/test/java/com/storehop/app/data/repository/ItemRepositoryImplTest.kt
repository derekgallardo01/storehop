package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.OTHER_USER_ID
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Validates the repository-layer enforcement of the cross-table ownership invariant
 * (junction `userId` is copied from the parent row, not from whatever session is active).
 * Also verifies the basic addItem path stamps the right defaults.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: ItemRepositoryImpl
    private val sequentialIds = SequentialIdGenerator()
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC)
    private val session = FakeSessionProvider(TEST_USER_ID)

    @Before fun setup() {
        db = createTestDb(seeded = false)
        repo = ItemRepositoryImpl(
            db = db,
            itemDao = db.itemDao(),
            xrefDao = db.itemStoreXrefDao(),
            purchaseRecordDao = db.purchaseRecordDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = sequentialIds,
            clock = fixedClock,
            session = session,
        )
        kotlinx.coroutines.runBlocking {
            listOf("store_lidl", "store_continente").forEach { id ->
                db.storeDao().upsert(
                    Store(
                        id = id, name = id, colorArgb = null,
                        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    ),
                )
            }
        }
    }

    @After fun tearDown() { db.close() }

    @Test fun `addItem stamps userId from session and isNeeded=true by default`() = runTest {
        val id = repo.addItem(
            name = "Milk",
            categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = "2 L",
            notes = null,
        )
        val item = db.itemDao().observeNeeded(TEST_USER_ID).first().single()
        assertThat(item.id).isEqualTo(id)
        assertThat(item.name).isEqualTo("Milk")
        assertThat(item.quantity).isEqualTo("2 L")
        assertThat(item.userId).isEqualTo(TEST_USER_ID)
        assertThat(item.isNeeded).isTrue()
        assertThat(item.createdAt).isEqualTo(50_000L)
        assertThat(item.updatedAt).isEqualTo(50_000L)
    }

    @Test fun `junction xrefs inherit userId from the active session at insert time`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs).hasSize(2)
        assertThat(xrefs.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
    }

    @Test fun `switching the session userId after insert does not retroactively change xrefs`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Session changes (simulating sign-in as a different user later).
        session.setUserId(OTHER_USER_ID)

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
    }

    @Test fun `markPurchasedAtStore flips isNeeded only at that store and writes one PurchaseRecord`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchasedAtStore(itemId, "store_lidl")

        // Per-store: Lidl's xref is now not-needed. Continente's xref is
        // untouched -- this is the bug-fix the whole migration exists for.
        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
            .associateBy { it.storeId }
        assertThat(xrefs.getValue("store_lidl").isNeeded).isFalse()
        assertThat(xrefs.getValue("store_continente").isNeeded).isTrue()

        // Exactly one PurchaseRecord, scoped to the store we marked.
        val records = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(records).hasSize(1)
        assertThat(records.single().storeId).isEqualTo("store_lidl")
        assertThat(records.single().userId).isEqualTo(TEST_USER_ID)
    }

    @Test fun `markNeededAtStore restores only the targeted store's xref`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchasedAtStore(itemId, "store_lidl")
        // Sanity: Lidl's xref is purchased.
        assertThat(
            db.itemStoreXrefDao().findForItem(itemId)
                .single { it.storeId == "store_lidl" }.isNeeded,
        ).isFalse()

        repo.markNeededAtStore(itemId, "store_lidl")

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
            .associateBy { it.storeId }
        assertThat(xrefs.getValue("store_lidl").isNeeded).isTrue()
        assertThat(xrefs.getValue("store_continente").isNeeded).isTrue()
        // markNeededAtStore is a state correction, NOT a purchase --
        // no new PurchaseRecord, just the one from the original purchase.
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first())
            .hasSize(1)
    }

    @Test fun `updateItem replaces store set and new xrefs inherit the parent item's userId`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        // Simulate a session change between create and update — this is the exact bug
        // the audit caught: previously the new xrefs would be stamped with the LIVE
        // session userId rather than the parent item's userId, breaking the cross-table
        // ownership invariant.
        session.setUserId(OTHER_USER_ID)

        repo.updateItem(
            id = itemId,
            name = "Milk 2L",
            categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = "2L",
            notes = null,
        )

        // updateItem still resolves the row by the live session, so it should be a
        // no-op for the OTHER_USER session: assert the item is untouched and no new
        // xrefs were written.
        val xrefsAfterOtherSession = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefsAfterOtherSession.map { it.storeId }.toSet())
            .containsExactly("store_lidl")

        // Switch back to the original owner and update again.
        session.setUserId(TEST_USER_ID)
        repo.updateItem(
            id = itemId,
            name = "Milk 2L",
            categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = "2L",
            notes = null,
        )

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs.map { it.storeId }.toSet())
            .containsExactly("store_lidl", "store_continente")
        // All xrefs carry the parent's userId — never the live session's.
        assertThat(xrefs.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
    }

    @Test fun `markPurchasedAtStore PurchaseRecords carry the parent's userId, not the session's`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Session changes — the call should no-op for the wrong session and,
        // when called as the right user, source userId from the parent row.
        session.setUserId(OTHER_USER_ID)
        repo.markPurchasedAtStore(itemId, "store_lidl")
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).isEmpty()

        session.setUserId(TEST_USER_ID)
        repo.markPurchasedAtStore(itemId, "store_lidl")
        val records = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(records).hasSize(1)
        assertThat(records.single().userId).isEqualTo(TEST_USER_ID)
    }

    @Test fun `softDelete is rejected for an item the live session does not own`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Switch session — softDelete must NOT tombstone another user's row.
        session.setUserId(OTHER_USER_ID)
        repo.softDelete(itemId)

        // Restore the owner and confirm the item is still live with its xrefs intact.
        session.setUserId(TEST_USER_ID)
        val live = db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id }
        assertThat(live).contains(itemId)
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(1)
    }

    @Test fun `undoSoftDelete restores the item, its xrefs, and its purchase records`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchasedAtStore(itemId, "store_lidl")
        repo.markPurchasedAtStore(itemId, "store_continente")
        // Pre: 2 xrefs, 2 PurchaseRecords.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(2)
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).hasSize(2)

        repo.softDelete(itemId)

        // Cascade tombstoned everything.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).isEmpty()
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).isEmpty()

        repo.undoSoftDelete(itemId)

        // Item live again, both xrefs restored, both purchase records restored.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id }).contains(itemId)
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(2)
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).hasSize(2)
    }

    @Test fun `softDelete cascade-tombstones xrefs and purchase records under one transaction`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        // Build up state at both stores so we can verify the cascade hits both
        // xrefs and both PurchaseRecords.
        repo.markPurchasedAtStore(itemId, "store_lidl")
        repo.markPurchasedAtStore(itemId, "store_continente")
        // Mid-state: two live xrefs, two purchase records.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(2)
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first())
            .hasSize(2)

        repo.softDelete(itemId)

        // Item gone from observeAll.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id })
            .doesNotContain(itemId)

        // Xrefs cascade-tombstoned: findForItem filters deletedAt IS NULL, so empty.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).isEmpty()
        // Purchase records cascade-tombstoned (observeForItem filters deletedAt IS NULL).
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first())
            .isEmpty()
    }

    @Test fun `observeById returns null for an id owned by a different user`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        session.setUserId(OTHER_USER_ID)
        assertThat(repo.observeById(itemId).first()).isNull()
    }

    @Test fun `addItem rolls back the item insert if a downstream xref insert violates an FK`() = runTest {
        // store_does_not_exist is not in the DB, so the xref FK to stores will fail.
        // Without withTransaction wrapping addItem, the item upsert from a few lines
        // above would persist, leaving an orphan item with no xrefs. Verify the
        // entire transaction rolls back: no item, no xrefs, no purchase records.
        val itemCountBefore = db.itemDao().observeAll(TEST_USER_ID).first().size

        runCatching {
            repo.addItem(
                name = "Milk",
                categoryId = null,
                storeIds = setOf("store_lidl", "store_does_not_exist"),
                quantity = null,
                notes = null,
            )
        }.also { result ->
            assertThat(result.isFailure).isTrue()
        }

        val itemCountAfter = db.itemDao().observeAll(TEST_USER_ID).first().size
        assertThat(itemCountAfter).isEqualTo(itemCountBefore)
    }

    @Test fun `OCTA-4 addItem with empty storeIds set works and the item is reachable via observeAll`() = runTest {
        val id = repo.addItem(
            name = "Untagged Milk", categoryId = null,
            storeIds = emptySet(),
            quantity = null, notes = null,
        )
        val all = db.itemDao().observeAll(TEST_USER_ID).first()
        assertThat(all.map { it.item.id }).contains(id)
        assertThat(db.itemStoreXrefDao().findForItem(id)).isEmpty()
    }

    @Test fun `OCTA-5 markPurchasedAtStore twice writes two PurchaseRecords (idempotency contract)`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        repo.markPurchasedAtStore(itemId, "store_lidl")
        val recordsAfterFirst = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(recordsAfterFirst).hasSize(1)

        // Second call on an already-purchased xref: today's behavior is to
        // write another PurchaseRecord. Pin that. If we ever change it to
        // idempotent (skip the record when xref.isNeeded is already 0), flip
        // the assertion. The key thing is it doesn't crash or corrupt state.
        repo.markPurchasedAtStore(itemId, "store_lidl")
        val recordsAfterSecond = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(recordsAfterSecond).hasSize(2)
        // The xref is still not-needed.
        assertThat(
            db.itemStoreXrefDao().findForItem(itemId).single().isNeeded,
        ).isFalse()
    }

    @Test fun `addItem with new category auto-creates an SCO row at each tagged store`() = runTest {
        seedCategory("cat_custom")

        repo.addItem(
            name = "Feta",
            categoryId = "cat_custom",
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )

        val lidlSCOs = db.storeCategoryOrderDao().findForStore("store_lidl")
        val continenteSCOs = db.storeCategoryOrderDao().findForStore("store_continente")
        assertThat(lidlSCOs.map { it.categoryId }).containsExactly("cat_custom")
        assertThat(continenteSCOs.map { it.categoryId }).containsExactly("cat_custom")
        // First SCO row at a fresh store gets displayOrder = 0.
        assertThat(lidlSCOs.single().displayOrder).isEqualTo(0)
        // Auto-created rows are not isSeeded — the seeder is the only writer
        // that should ever set isSeeded=true.
        assertThat(lidlSCOs.single().isSeeded).isFalse()
        // pendingSync=true so the next push tick syncs them to Firestore.
        assertThat(lidlSCOs.single().pendingSync).isTrue()
    }

    @Test fun `addItem appends the new SCO row at displayOrder = max + 1`() = runTest {
        seedCategory("cat_existing")
        seedCategory("cat_new")
        // Pre-existing SCO rows at displayOrder 0 and 1.
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_existing", 0))
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_new", 1))

        // Use a category not yet in any SCO row.
        seedCategory("cat_appended")
        repo.addItem(
            name = "Olives", categoryId = "cat_appended",
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val rows = db.storeCategoryOrderDao().findForStore("store_lidl")
            .sortedBy { it.displayOrder }
        assertThat(rows.map { it.categoryId })
            .containsExactly("cat_existing", "cat_new", "cat_appended").inOrder()
        assertThat(rows.last().displayOrder).isEqualTo(2)
    }

    @Test fun `addItem with categoryId=null does not touch SCO`() = runTest {
        // Pre-existing seeded SCO row to make sure our addItem doesn't disturb it.
        seedCategory("cat_seeded")
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_seeded", 0, isSeeded = true))

        repo.addItem(
            name = "Untagged", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val rows = db.storeCategoryOrderDao().findForStore("store_lidl")
        assertThat(rows).hasSize(1)
        assertThat(rows.single().categoryId).isEqualTo("cat_seeded")
        assertThat(rows.single().isSeeded).isTrue()
    }

    @Test fun `addItem on an already-live SCO row is a no-op (idempotency)`() = runTest {
        seedCategory("cat_existing")
        // Seed an SCO row at a non-zero displayOrder so we can assert it's preserved.
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_existing", 5, isSeeded = true))

        repo.addItem(
            name = "Bread", categoryId = "cat_existing",
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val row = db.storeCategoryOrderDao().findForStore("store_lidl").single()
        assertThat(row.displayOrder).isEqualTo(5)
        // The seeded flag survives — we don't downgrade an existing seeded row.
        assertThat(row.isSeeded).isTrue()
    }

    @Test fun `addItem revives a tombstoned SCO row and repositions it at the end`() = runTest {
        seedCategory("cat_a")
        seedCategory("cat_b")
        // cat_a was at slot 0, then user removed it from this store's aisles.
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_a", 0).copy(deletedAt = 10L))
        db.storeCategoryOrderDao().upsert(sco("store_lidl", "cat_b", 1))

        repo.addItem(
            name = "Bread", categoryId = "cat_a",
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val live = db.storeCategoryOrderDao().findForStore("store_lidl")
            .sortedBy { it.displayOrder }
        // Both rows are alive, with cat_a appended at displayOrder=2 (max+1
        // computed against the only live row, cat_b at 1).
        assertThat(live.map { it.categoryId to it.displayOrder })
            .containsExactly("cat_b" to 1, "cat_a" to 2).inOrder()
    }

    @Test fun `updateItem adding a category creates the SCO row at the tagged stores`() = runTest {
        seedCategory("cat_added")
        val itemId = repo.addItem(
            name = "Bread", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Pre: no SCO rows.
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isEmpty()

        repo.updateItem(
            id = itemId, name = "Bread",
            categoryId = "cat_added",
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val rows = db.storeCategoryOrderDao().findForStore("store_lidl")
        assertThat(rows.map { it.categoryId }).containsExactly("cat_added")
    }

    @Test fun `updateItem leaves the SCO row alone when the category is removed`() = runTest {
        // Other items might still use this (store, category) pair, so removing
        // a category from one item must NOT tombstone the SCO row.
        seedCategory("cat_kept")
        val itemId = repo.addItem(
            name = "Bread", categoryId = "cat_kept",
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).hasSize(1)

        repo.updateItem(
            id = itemId, name = "Bread",
            categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        // Row is still live with the original displayOrder.
        val rows = db.storeCategoryOrderDao().findForStore("store_lidl")
        assertThat(rows.map { it.categoryId }).containsExactly("cat_kept")
    }

    private suspend fun seedCategory(id: String) {
        db.categoryDao().upsert(
            Category(
                id = id, name = id, nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
    }

    private fun sco(
        storeId: String, categoryId: String, displayOrder: Int, isSeeded: Boolean = false,
    ) = StoreCategoryOrder(
        storeId = storeId, categoryId = categoryId, displayOrder = displayOrder,
        isSeeded = isSeeded, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    /** Deterministic IDs for assertion stability. */
    private class SequentialIdGenerator : IdGenerator {
        private var n = 0
        override fun newId(): String = "id-${++n}"
    }
}
