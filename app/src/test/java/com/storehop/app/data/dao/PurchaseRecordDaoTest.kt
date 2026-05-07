package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.PurchaseRecord
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

/**
 * Direct DAO coverage for the cascade tombstone <-> restore round-trip used
 * by item soft-delete + undo. Repository-level tests cover the happy path;
 * these pin the cascade arithmetic so a bug in the SQL doesn't leak history
 * for tombstoned items.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseRecordDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: PurchaseRecordDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.purchaseRecordDao()
        // FK preconditions: insert is restricted by FK from items / stores.
        kotlinx.coroutines.runBlocking {
            db.categoryDao().upsert(
                Category(
                    id = "cat", name = "cat", nameKey = null, icon = null,
                    isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            db.itemDao().upsert(
                Item(
                    id = "milk", name = "Milk", categoryId = "cat", notes = null,
                    quantity = null, isNeeded = true, lastPurchasedAt = null,
                    userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            db.itemDao().upsert(
                Item(
                    id = "eggs", name = "Eggs", categoryId = "cat", notes = null,
                    quantity = null, isNeeded = true, lastPurchasedAt = null,
                    userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            db.storeDao().upsert(
                Store(
                    id = "lidl", name = "Lidl", colorArgb = null,
                    isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
        }
    }

    @After fun tearDown() { db.close() }

    @Test fun `observeForItem returns records ordered by purchasedAt descending`() = runTest {
        dao.insert(record("p1", "milk", purchasedAt = 100L))
        dao.insert(record("p2", "milk", purchasedAt = 300L))
        dao.insert(record("p3", "milk", purchasedAt = 200L))

        val rows = dao.observeForItem(TEST_USER_ID, "milk").first()
        assertThat(rows.map { it.id }).containsExactly("p2", "p3", "p1").inOrder()
    }

    @Test fun `observeForItem hides tombstoned records`() = runTest {
        dao.insert(record("alive", "milk", purchasedAt = 100L))
        dao.insert(record("dead", "milk", purchasedAt = 200L, deletedAt = 99L))

        val rows = dao.observeForItem(TEST_USER_ID, "milk").first()
        assertThat(rows.map { it.id }).containsExactly("alive")
    }

    @Test fun `softDeleteForItem cascades only the target itemId`() = runTest {
        dao.insert(record("milk_1", "milk", purchasedAt = 100L))
        dao.insert(record("milk_2", "milk", purchasedAt = 200L))
        dao.insert(record("eggs_1", "eggs", purchasedAt = 300L))

        dao.softDeleteForItem(TEST_USER_ID, "milk", now = 999L)

        // Both milk records are tombstoned; the eggs record is untouched.
        assertThat(dao.observeForItem(TEST_USER_ID, "milk").first()).isEmpty()
        val eggs = dao.observeForItem(TEST_USER_ID, "eggs").first()
        assertThat(eggs.map { it.id }).containsExactly("eggs_1")
    }

    @Test fun `softDeleteForItem flips pendingSync and stamps deletedAt`() = runTest {
        dao.insert(record("p", "milk", purchasedAt = 100L))
        dao.markPushed(TEST_USER_ID, "p") // simulate the row already pushed

        dao.softDeleteForItem(TEST_USER_ID, "milk", now = 555L)

        val pending = dao.observePendingPush(TEST_USER_ID).first()
        assertThat(pending.map { it.id }).containsExactly("p")
        assertThat(pending.single().deletedAt).isEqualTo(555L)
        assertThat(pending.single().updatedAt).isEqualTo(555L)
    }

    @Test fun `restoreCascadeForItem restores ONLY the matching deletedAt batch`() = runTest {
        // Two delete waves at different timestamps. The undo path passes the
        // exact deletedAt of the wave to restore -- making sure we don't
        // accidentally undo every previous tombstone.
        dao.insert(record("first", "milk", purchasedAt = 100L))
        dao.softDeleteForItem(TEST_USER_ID, "milk", now = 100L)
        dao.insert(record("second", "milk", purchasedAt = 200L))
        dao.softDeleteForItem(TEST_USER_ID, "milk", now = 200L)

        // Restore only the second wave.
        dao.restoreCascadeForItem(TEST_USER_ID, "milk", deletedAt = 200L, now = 999L)

        val live = dao.observeForItem(TEST_USER_ID, "milk").first()
        assertThat(live.map { it.id }).containsExactly("second")
    }

    @Test fun `observePendingPush filters by userId and pendingSync`() = runTest {
        dao.insert(record("p1", "milk", purchasedAt = 100L)) // pendingSync defaults to true
        dao.insert(record("p2", "milk", purchasedAt = 200L))
        dao.markPushed(TEST_USER_ID, "p1")

        val pending = dao.observePendingPush(TEST_USER_ID).first()
        assertThat(pending.map { it.id }).containsExactly("p2")
    }

    private fun record(
        id: String,
        itemId: String,
        purchasedAt: Long,
        deletedAt: Long? = null,
    ) = PurchaseRecord(
        id = id, itemId = itemId, storeId = "lidl",
        purchasedAt = purchasedAt, userId = TEST_USER_ID,
        createdAt = purchasedAt, updatedAt = purchasedAt, deletedAt = deletedAt,
    )
}
