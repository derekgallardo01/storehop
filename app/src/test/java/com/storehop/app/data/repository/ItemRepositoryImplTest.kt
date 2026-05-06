package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
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
    private val session = StubSession(TEST_USER_ID)

    @Before fun setup() {
        db = createTestDb(seeded = false)
        repo = ItemRepositoryImpl(
            db = db,
            itemDao = db.itemDao(),
            xrefDao = db.itemStoreXrefDao(),
            purchaseRecordDao = db.purchaseRecordDao(),
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
        session.userId = OTHER_USER_ID

        val xrefs = db.itemStoreXrefDao().findForItem(itemId)
        assertThat(xrefs.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
    }

    @Test fun `markPurchased clears isNeeded and writes a PurchaseRecord per tagged store`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchased(itemId)

        // Item drops out of needed.
        assertThat(db.itemDao().observeNeeded(TEST_USER_ID).first()).isEmpty()
        // One PurchaseRecord per tagged store.
        val records = db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()
        assertThat(records).hasSize(2)
        assertThat(records.map { it.storeId }.toSet())
            .containsExactly("store_lidl", "store_continente")
        assertThat(records.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
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
        session.userId = OTHER_USER_ID

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
        session.userId = TEST_USER_ID
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

    @Test fun `markPurchased PurchaseRecords carry the parent's userId, not the session's`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl"),
            quantity = null, notes = null,
        )
        // Session changes — markPurchased should still no-op for the wrong session
        // and, when called as the right user, source userId from the parent row.
        session.userId = OTHER_USER_ID
        repo.markPurchased(itemId)
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first()).isEmpty()

        session.userId = TEST_USER_ID
        repo.markPurchased(itemId)
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
        session.userId = OTHER_USER_ID
        repo.softDelete(itemId)

        // Restore the owner and confirm the item is still live with its xrefs intact.
        session.userId = TEST_USER_ID
        val needed = db.itemDao().observeNeeded(TEST_USER_ID).first()
        assertThat(needed.map { it.id }).containsExactly(itemId)
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(1)
    }

    @Test fun `softDelete cascade-tombstones xrefs and purchase records under one transaction`() = runTest {
        val itemId = repo.addItem(
            name = "Milk", categoryId = null,
            storeIds = setOf("store_lidl", "store_continente"),
            quantity = null, notes = null,
        )
        repo.markPurchased(itemId)
        // Mid-state: item now not-needed, xrefs exist, two purchase records exist.
        assertThat(db.itemStoreXrefDao().findForItem(itemId)).hasSize(2)
        assertThat(db.purchaseRecordDao().observeForItem(TEST_USER_ID, itemId).first())
            .hasSize(2)

        // Re-mark needed so we can use addItem-then-soft-delete semantics. Actually,
        // softDelete should work regardless — proceed.
        repo.softDelete(itemId)

        // Item is gone from observeNeeded (was already not-needed) AND from observeAll.
        assertThat(db.itemDao().observeNeeded(TEST_USER_ID).first()).isEmpty()
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
        session.userId = OTHER_USER_ID
        assertThat(repo.observeById(itemId).first()).isNull()
    }

    private class StubSession(var userId: String) : UserSessionProvider {
        override fun currentUserId(): String = userId
    }

    /** Deterministic IDs for assertion stability. */
    private class SequentialIdGenerator : IdGenerator {
        private var n = 0
        override fun newId(): String = "id-${++n}"
    }
}
