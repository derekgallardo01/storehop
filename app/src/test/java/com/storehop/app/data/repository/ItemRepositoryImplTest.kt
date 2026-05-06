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
        val records = db.purchaseRecordDao().observeForItem(itemId).first()
        assertThat(records).hasSize(2)
        assertThat(records.map { it.storeId }.toSet())
            .containsExactly("store_lidl", "store_continente")
        assertThat(records.map { it.userId }.toSet()).containsExactly(TEST_USER_ID)
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
