package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.util.FakeHouseholdSessionProvider
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
    private val householdSession = FakeHouseholdSessionProvider(TEST_USER_ID)

    @Before fun setup() {
        db = createTestDb(seeded = false)
        repo = ItemRepositoryImpl(
            db = db,
            itemDao = db.itemDao(),
            xrefDao = db.itemStoreXrefDao(),
            purchaseRecordDao = db.purchaseRecordDao(),
            scoDao = db.storeCategoryOrderDao(),
            storeDao = db.storeDao(),
            ids = sequentialIds,
            clock = fixedClock,
            session = session,
            householdSession = householdSession,
        )
        kotlinx.coroutines.runBlocking {
            listOf("store_lidl", "store_continente").forEach { id ->
                db.storeDao().upsert(
                    Store(
                        id = id, name = id, colorArgb = null,
                        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                        householdId = TEST_USER_ID,
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

    @Test fun `markPurchasedAtStore cascades isNeeded=0 to every tagged store and writes one PurchaseRecord`() = runTest {
        // Mike-reported in v0.5: "I purchased it at one of the stores, but it
        // still shows up in the other 2." This test pins the cascade fix.
        val itemId = repo.addItem(
            name = "Mozzarella", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )

        val snapshot = repo.markPurchasedAtStore(itemId, "store_lidl")

        // Both xrefs flipped to not-needed; both stamped with the same
        // lastPurchasedAt = snapshot, which is the precision marker undo uses.
        val xrefs = db.itemStoreXrefDao().findForItem(itemId).associateBy { it.storeId }
        assertThat(xrefs.getValue("store_lidl").isNeeded).isFalse()
        assertThat(xrefs.getValue("store_continente").isNeeded).isFalse()
        assertThat(xrefs.getValue("store_lidl").lastPurchasedAt).isEqualTo(snapshot)
        assertThat(xrefs.getValue("store_continente").lastPurchasedAt).isEqualTo(snapshot)

        // Still exactly one PurchaseRecord, scoped to the store the user
        // actually bought it at — history reflects the real transaction, not
        // the cascade fan-out.
        val records = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(records).hasSize(1)
        assertThat(records.single().storeId).isEqualTo("store_lidl")
        assertThat(records.single().userId).isEqualTo(TEST_USER_ID)
    }

    @Test fun `markPurchasedAtStore returns the snapshot timestamp callers pass to undoPurchase`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        val snapshot = repo.markPurchasedAtStore(itemId, "store_lidl")

        assertThat(snapshot).isNotNull()
        assertThat(snapshot).isEqualTo(50_000L) // fixedClock value
    }

    @Test fun `markPurchasedAtStore returns null when the item is not owned by the live session`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        session.setUserId(OTHER_USER_ID)
        householdSession.setHouseholdId(OTHER_USER_ID)

        val snapshot = repo.markPurchasedAtStore(itemId, "store_lidl")

        assertThat(snapshot).isNull()
        // No xref state changed.
        assertThat(
            db.itemStoreXrefDao().findForItem(itemId).single().isNeeded,
        ).isTrue()
    }

    @Test fun `undoPurchase restores every cascaded xref and soft-deletes the PurchaseRecord`() = runTest {
        val itemId = repo.addItem(
            name = "Mozzarella", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        val snapshot = checkNotNull(repo.markPurchasedAtStore(itemId, "store_lidl"))
        // Sanity: cascade fired.
        assertThat(db.itemStoreXrefDao().findForItem(itemId).all { !it.isNeeded }).isTrue()
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).hasSize(1)

        repo.undoPurchase(itemId, snapshot)

        val xrefs = db.itemStoreXrefDao().findForItem(itemId).associateBy { it.storeId }
        // All xrefs back to needed; lastPurchasedAt cleared so the rows look as
        // if the purchase never happened.
        assertThat(xrefs.getValue("store_lidl").isNeeded).isTrue()
        assertThat(xrefs.getValue("store_continente").isNeeded).isTrue()
        assertThat(xrefs.getValue("store_lidl").lastPurchasedAt).isNull()
        assertThat(xrefs.getValue("store_continente").lastPurchasedAt).isNull()
        // PurchaseRecord soft-deleted (observeForItem filters deletedAt IS NULL).
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).isEmpty()
    }

    @Test fun `undoPurchase with a stale snapshot is a no-op`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        val snapshot = checkNotNull(repo.markPurchasedAtStore(itemId, "store_lidl"))

        repo.undoPurchase(itemId, snapshot - 1) // not the timestamp we flipped at

        // Xref still purchased; PurchaseRecord still live.
        assertThat(db.itemStoreXrefDao().findForItem(itemId).single().isNeeded).isFalse()
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).hasSize(1)
    }

    @Test fun `markNeededAtStore restores ONLY the targeted store after a cascade purchase`() = runTest {
        // Manual un-check path: user purchased at Lidl (cascade fired), then a
        // beat later realized "actually I still need this at Lidl." Tapping
        // un-check should restore Lidl only — the user said nothing about
        // Continente, which they presumably don't need anymore.
        val itemId = repo.addItem(
            name = "Mozzarella", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchasedAtStore(itemId, "store_lidl")

        repo.markNeededAtStore(itemId, "store_lidl")

        val xrefs = db.itemStoreXrefDao().findForItem(itemId).associateBy { it.storeId }
        assertThat(xrefs.getValue("store_lidl").isNeeded).isTrue()
        assertThat(xrefs.getValue("store_continente").isNeeded).isFalse()
        // markNeededAtStore is a state correction — no PurchaseRecord touched.
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
        householdSession.setHouseholdId(OTHER_USER_ID)

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
        householdSession.setHouseholdId(TEST_USER_ID)
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
        householdSession.setHouseholdId(OTHER_USER_ID)
        repo.softDelete(itemId)

        // Restore the owner and confirm the item is still live with its xrefs intact.
        session.setUserId(TEST_USER_ID)
        householdSession.setHouseholdId(TEST_USER_ID)
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
        householdSession.setHouseholdId(OTHER_USER_ID)
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
        // v0.9: zero-xref items still surface on the master list (the
        // filter only hides items whose alive xrefs ALL point at one-
        // off stores; "no xrefs at all" is treated as "untagged, needs
        // a home" — typically from CSV imports — and stays visible).
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
                householdId = TEST_USER_ID,
            ),
        )
    }

    // ---- v0.5.7 additions: tagItemToStore + addItemFromQuickAdd -------------

    @Test fun `tagItemToStore creates a fresh xref when none exists`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Add a second store *after* item creation. tagItemToStore should
        // produce a brand-new live xref for store_continente without
        // disturbing the existing store_lidl xref.
        repo.tagItemToStore(itemId = itemId, storeId = "store_continente")

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs.map { it.storeId }.toSet())
            .containsExactly("store_lidl", "store_continente")
        val continenteXref = xrefs.single { it.storeId == "store_continente" }
        assertThat(continenteXref.isNeeded).isTrue()
        assertThat(continenteXref.userId).isEqualTo(TEST_USER_ID)
        assertThat(continenteXref.deletedAt).isNull()
    }

    @Test fun `tagItemToStore upserts a tombstoned xref back to live + needed`() = runTest {
        // Start with the item tagged at store_lidl, then tombstone the xref
        // (simulates the user removing the store via the form, leaving the
        // junction soft-deleted with the original createdAt). Then
        // tagItemToStore re-tags the same store. Upsert by primary key
        // (itemId, storeId) replaces the tombstoned row with a live one.
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Replace the store set with empty -- tombstones the lidl xref.
        repo.updateItem(
            id = itemId, name = "Milk", categoryId = null,
            storeIds = emptySet(), quantity = null, notes = null,
        )
        // findForItem returns only LIVE xrefs.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).isEmpty()

        repo.tagItemToStore(itemId = itemId, storeId = "store_lidl")

        val live = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(live).hasSize(1)
        assertThat(live.single().storeId).isEqualTo("store_lidl")
        assertThat(live.single().isNeeded).isTrue()
        assertThat(live.single().deletedAt).isNull()
    }

    @Test fun `tagItemToStore on an already-needed live xref is idempotent`() = runTest {
        // Initial xref is alive AND isNeeded=true. Calling tagItemToStore
        // is a no-op-ish: the row stays alive, isNeeded stays true; the
        // row gets touched (updatedAt + pendingSync) for sync hygiene
        // but the user-visible state is unchanged.
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        val before = db.itemStoreXrefDao().findForItem(itemId).single()
        assertThat(before.isNeeded).isTrue()

        repo.tagItemToStore(itemId = itemId, storeId = "store_lidl")

        val after = db.itemStoreXrefDao().findForItem(itemId).single()
        assertThat(after.storeId).isEqualTo("store_lidl")
        assertThat(after.isNeeded).isTrue()
        assertThat(after.deletedAt).isNull()
        // Same primary key -- single() guarantees there's no duplicate row.
    }

    @Test fun `addItemFromQuickAdd dedupes by case-insensitive name and routes to tagItemToStore`() = runTest {
        // Pre-seed the master library with a "Milk" item not yet tagged to
        // any store. addItemFromQuickAdd("milk", store_lidl) should find
        // the existing entry by case-insensitive name match and tag it
        // rather than creating a duplicate row -- this is the v0.5.7 fix
        // for the duplicate-creation bug.
        val originalId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = emptySet(),
            quantity = null, notes = null,
        )
        val initialItemCount = db.itemDao().observeAll(TEST_USER_ID).first().size
        assertThat(initialItemCount).isEqualTo(1)

        // Lowercase form + surrounding whitespace -- both should be
        // ignored by findByName (COLLATE NOCASE) + the trim().
        val resolvedId = repo.addItemFromQuickAdd(name = "  milk  ", storeId = "store_lidl")

        // Same item id surfaced -- no duplicate created.
        assertThat(resolvedId).isEqualTo(originalId)
        // Items table count unchanged.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().size).isEqualTo(initialItemCount)
        // The resolved item now has a live xref at store_lidl.
        val xrefs = db.itemStoreXrefDao().findForItem(originalId)
        assertThat(xrefs.map { it.storeId }).containsExactly("store_lidl")
        assertThat(xrefs.single().isNeeded).isTrue()

        // No-match path: a new name creates a new item.
        val newId = repo.addItemFromQuickAdd(name = "Bread", storeId = "store_lidl")
        assertThat(newId).isNotEqualTo(originalId)
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().size).isEqualTo(2)
    }

    // ---- v0.6.1 additions: bulk needed-state for the Items-list +/- toggle --

    @Test fun `markNeededAcrossAllStores flips every alive xref to isNeeded=true`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        // Mark purchased so all xrefs go isNeeded=false; baseline for the +.
        repo.markPurchasedAtStore(itemId, "store_lidl")
        val before = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(before.all { !it.isNeeded }).isTrue()

        repo.markNeededAcrossAllStores(itemId)

        val after = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(after.map { it.storeId }.toSet())
            .containsExactly("store_lidl", "store_continente")
        assertThat(after.all { it.isNeeded }).isTrue()
        assertThat(after.all { it.lastPurchasedAt == null }).isTrue()
    }

    @Test fun `markNeededAcrossAllStores is a no-op when the item belongs to a different uid`() = runTest {
        // Create the item under a foreign owner. The session-scoped repo
        // shouldn't be able to flip its xrefs.
        val foreignSession = FakeSessionProvider(OTHER_USER_ID)
        val foreignRepo = ItemRepositoryImpl(
            db = db,
            itemDao = db.itemDao(),
            xrefDao = db.itemStoreXrefDao(),
            purchaseRecordDao = db.purchaseRecordDao(),
            scoDao = db.storeCategoryOrderDao(),
            storeDao = db.storeDao(),
            ids = sequentialIds,
            clock = fixedClock,
            session = foreignSession,
            householdSession = FakeHouseholdSessionProvider(OTHER_USER_ID),
        )
        // Seed a store under the foreign uid so the foreign repo can write
        // its own xref.
        db.storeDao().upsert(
            Store(
                id = "store_other", name = "Other", colorArgb = null,
                isArchived = false, isSeeded = false, userId = OTHER_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = OTHER_USER_ID,
            ),
        )
        val foreignItemId = foreignRepo.addItem(
            name = "Foreign", categoryId = null,
            storeIds = setOf("store_other"),
            quantity = null, notes = null,
        )

        // The TEST_USER_ID-scoped `repo` tries to mark the foreign item
        // needed-everywhere. observeById filtered by TEST_USER_ID returns
        // null, so the cascade is a no-op.
        repo.markNeededAcrossAllStores(foreignItemId)

        // Foreign xref untouched.
        val foreignXref = db.itemStoreXrefDao().findForItem(foreignItemId).single()
        assertThat(foreignXref.userId).isEqualTo(OTHER_USER_ID)
        assertThat(foreignXref.isNeeded).isTrue()
    }

    @Test fun `markPurchasedAcrossAllStores flips every alive xref to isNeeded=false WITHOUT writing a PurchaseRecord`() = runTest {
        val itemId = repo.addItem(
            name = "Bread", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        // Purchase records start at 0.
        val before = db.purchaseRecordDao().observeTotalCount(TEST_USER_ID).first()
        assertThat(before).isEqualTo(0)

        repo.markPurchasedAcrossAllStores(itemId)

        // Every xref flipped.
        val after = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(after.map { it.isNeeded }.toSet()).containsExactly(false)
        assertThat(after.all { it.lastPurchasedAt == 50_000L }).isTrue()
        // ...and CRUCIALLY no PurchaseRecord was inserted -- this is the
        // distinction from `markPurchasedAtStore`. The user is on the
        // master Items list, not at a store; attributing a purchase to
        // any one store would be wrong.
        val purchaseCount = db.purchaseRecordDao().observeTotalCount(TEST_USER_ID).first()
        assertThat(purchaseCount).isEqualTo(0)
    }

    @Test fun `observeNeededItemIds emits distinct itemIds with at least one alive needed xref`() = runTest {
        val milk = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        val bread = repo.addItem(
            name = "Bread", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        // Both items currently needed at one or more stores.
        val initial = repo.observeNeededItemIds().first()
        assertThat(initial).containsExactly(milk, bread)

        // Buying milk anywhere clears its xrefs (cascade), so it drops out.
        repo.markPurchasedAtStore(milk, "store_lidl")
        val afterPurchase = repo.observeNeededItemIds().first()
        assertThat(afterPurchase).containsExactly(bread)

        // Soft-deleting bread's xrefs (via softDelete on the item) drops
        // bread too -- the DISTINCT query filters tombstoned xrefs.
        repo.softDelete(bread)
        val afterDelete = repo.observeNeededItemIds().first()
        assertThat(afterDelete).isEmpty()
    }

    // ---- v0.9: Buy Today (#5b) + quick-add staple default (#5a) ------------

    @Test fun `markPurchasedAtStore clears the isBuyToday flag`() = runTest {
        val id = repo.addItem(
            name = "Advil", categoryId = null, storeIds = setOf("store_lidl"),
            isBuyToday = true,
        )
        assertThat(repo.observeById(id).first()!!.item.isBuyToday).isTrue()

        repo.markPurchasedAtStore(id, "store_lidl")

        assertThat(repo.observeById(id).first()!!.item.isBuyToday).isFalse()
    }

    @Test fun `setBuyToday toggles the transient urgency flag`() = runTest {
        val id = repo.addItem(name = "Advil", categoryId = null, storeIds = setOf("store_lidl"))
        assertThat(repo.observeById(id).first()!!.item.isBuyToday).isFalse()

        repo.setBuyToday(id, true)
        assertThat(repo.observeById(id).first()!!.item.isBuyToday).isTrue()

        repo.setBuyToday(id, false)
        assertThat(repo.observeById(id).first()!!.item.isBuyToday).isFalse()
    }

    @Test fun `addItemFromQuickAdd defaults staple true at a regular store`() = runTest {
        val id = repo.addItemFromQuickAdd("Bananas", "store_lidl")
        assertThat(repo.observeById(id).first()!!.item.isStaple).isTrue()
    }

    @Test fun `addItemFromQuickAdd defaults staple false at a one-off store`() = runTest {
        db.storeDao().upsert(
            Store(
                id = "store_ikea", name = "IKEA", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                isOneOff = true, householdId = TEST_USER_ID,
            ),
        )
        val id = repo.addItemFromQuickAdd("Lamp", "store_ikea")
        assertThat(repo.observeById(id).first()!!.item.isStaple).isFalse()
    }

    // ---- Helpers ------------------------------------------------------------

    private fun sco(
        storeId: String, categoryId: String, displayOrder: Int, isSeeded: Boolean = false,
    ) = StoreCategoryOrder(
        storeId = storeId, categoryId = categoryId, displayOrder = displayOrder,
        isSeeded = isSeeded, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
        householdId = TEST_USER_ID,
    )

    // ---- v0.8.1 bulkTagStoresForItems ------------------------------------

    @Test fun `bulkTagStoresForItems unions storeIds into every selected item`() = runTest {
        val item1 = repo.addItem(
            name = "Toilet Paper", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        val item2 = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = emptySet(),
            quantity = null, notes = null,
        )

        repo.bulkTagStoresForItems(
            itemIds = setOf(item1, item2),
            storeIdsToAdd = setOf("store_lidl", "store_continente"),
        )

        val item1Stores = db.itemStoreXrefDao().findForItem(item1).map { it.storeId }.toSet()
        val item2Stores = db.itemStoreXrefDao().findForItem(item2).map { it.storeId }.toSet()
        // Item1 already had lidl; union adds continente without dropping it.
        assertThat(item1Stores).containsExactly("store_lidl", "store_continente")
        // Item2 had none; gets both.
        assertThat(item2Stores).containsExactly("store_lidl", "store_continente")
    }

    @Test fun `bulkTagStoresForItems is idempotent and does not duplicate alive xrefs`() = runTest {
        val itemId = repo.addItem(
            name = "TP", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        // Apply twice with the same args.
        repo.bulkTagStoresForItems(setOf(itemId), setOf("store_lidl"))
        repo.bulkTagStoresForItems(setOf(itemId), setOf("store_lidl"))

        // Still exactly one alive xref to lidl; upsert keyed on the (item,
        // store) PK can't duplicate.
        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs).hasSize(1)
        assertThat(xrefs.single().storeId).isEqualTo("store_lidl")
    }

    @Test fun `bulkTagStoresForItems resurrects a tombstoned xref instead of leaving it dead`() = runTest {
        val itemId = repo.addItem(
            name = "TP", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        db.itemStoreXrefDao().softDelete(
            householdId = TEST_USER_ID,
            itemId = itemId,
            storeId = "store_lidl",
            now = 99_000L,
        )

        repo.bulkTagStoresForItems(setOf(itemId), setOf("store_lidl"))

        val alive = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(alive).hasSize(1)
        assertThat(alive.single().storeId).isEqualTo("store_lidl")
        assertThat(alive.single().deletedAt).isNull()
    }

    @Test fun `bulkTagStoresForItems with empty inputs is a no-op`() = runTest {
        val itemId = repo.addItem(
            name = "TP", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )

        repo.bulkTagStoresForItems(emptySet(), setOf("store_lidl"))
        repo.bulkTagStoresForItems(setOf(itemId), emptySet())

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs.map { it.storeId }).containsExactly("store_lidl")
    }

    // ---- v0.9 one-off store master-list filter -------------------------

    @Test fun `observeAll hides items whose only alive xref is at a one-off store`() = runTest {
        // Seed a one-off store directly via the DAO (the v0.9 repo
        // addStore(isOneOff) path is exercised separately).
        db.storeDao().upsert(
            Store(
                id = "store_online_oneoff", name = "Online (One Off)", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID, isOneOff = true,
            ),
        )
        val itemId = repo.addItem(
            name = "Drying rack", categoryId = null,
            storeIds = setOf("store_online_oneoff"),
            quantity = null, notes = null,
        )

        val visible = db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id }
        assertThat(visible).doesNotContain(itemId)
    }

    @Test fun `observeAll shows items tagged to both a one-off store AND a regular store`() = runTest {
        db.storeDao().upsert(
            Store(
                id = "store_online_oneoff", name = "Online (One Off)", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID, isOneOff = true,
            ),
        )
        val itemId = repo.addItem(
            name = "Fancy olive oil", categoryId = null,
            storeIds = setOf("store_online_oneoff", "store_lidl"),
            quantity = null, notes = null,
        )

        val visible = db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id }
        assertThat(visible).contains(itemId)
    }

    @Test fun `observeAll keeps showing one-off-only item after flipping the store back to regular`() = runTest {
        db.storeDao().upsert(
            Store(
                id = "store_flippy", name = "Flippy", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID, isOneOff = true,
            ),
        )
        val itemId = repo.addItem(
            name = "Curtain rod", categoryId = null,
            storeIds = setOf("store_flippy"),
            quantity = null, notes = null,
        )
        // Initially hidden.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id })
            .doesNotContain(itemId)

        // Flip the store back to regular. The same item should now surface.
        db.storeDao().upsert(
            db.storeDao().findById(TEST_USER_ID, "store_flippy")!!.copy(isOneOff = false),
        )
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().map { it.item.id })
            .contains(itemId)
    }

    /**
     * v0.8.1 regression pin: Mike's "Aldi keeps showing checked even after I
     * uncheck + save" bug. The [ItemWithCategoryAndStores.@Junction] now
     * reads via the `alive_item_store_xref` view, so a soft-deleted xref no
     * longer surfaces its target store through `row.stores`. Pre-v0.8.1
     * (Junction against raw `item_store_xref`), this assertion would have
     * returned {store_lidl, store_continente}.
     */
    @Test fun `observeById stores excludes tombstoned xrefs via alive_item_store_xref view`() = runTest {
        val itemId = repo.addItem(
            name = "Toilet Paper", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )

        // Tombstone the continente xref directly via the DAO (simulating
        // the path the form's "uncheck + save" takes). The item itself
        // remains alive.
        db.itemStoreXrefDao().softDelete(
            householdId = TEST_USER_ID,
            itemId = itemId,
            storeId = "store_continente",
            now = 99_000L,
        )

        val row = checkNotNull(repo.observeById(itemId).first())
        val storeIds = row.stores.map { it.id }.toSet()
        assertThat(storeIds).containsExactly("store_lidl")
    }

    /** Deterministic IDs for assertion stability. */
    private class SequentialIdGenerator : IdGenerator {
        private var n = 0
        override fun newId(): String = "id-${++n}"
    }
}
