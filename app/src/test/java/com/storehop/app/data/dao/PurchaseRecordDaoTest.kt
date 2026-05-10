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

    @Test fun `softDeleteForItemAtTime tombstones only records with the matching purchasedAt`() = runTest {
        // Two purchases of the same item at different times. The cascade-undo
        // passes the exact purchasedAt of the action being undone — verify
        // it doesn't accidentally tombstone older or unrelated records.
        dao.insert(record("first", "milk", purchasedAt = 100L))
        dao.insert(record("second", "milk", purchasedAt = 200L))
        dao.insert(record("eggs", "eggs", purchasedAt = 200L))

        dao.softDeleteForItemAtTime(TEST_USER_ID, "milk", purchasedAt = 200L, now = 999L)

        val liveMilk = dao.observeForItem(TEST_USER_ID, "milk").first()
        assertThat(liveMilk.map { it.id }).containsExactly("first")
        // Same purchasedAt on a different item must not be touched.
        val liveEggs = dao.observeForItem(TEST_USER_ID, "eggs").first()
        assertThat(liveEggs.map { it.id }).containsExactly("eggs")
    }

    @Test fun `observePendingPush filters by userId and pendingSync`() = runTest {
        dao.insert(record("p1", "milk", purchasedAt = 100L)) // pendingSync defaults to true
        dao.insert(record("p2", "milk", purchasedAt = 200L))
        dao.markPushed(TEST_USER_ID, "p1")

        val pending = dao.observePendingPush(TEST_USER_ID).first()
        assertThat(pending.map { it.id }).containsExactly("p2")
    }

    // ---- Statistics aggregates -----------------------------------------------
    // Pin the SQL behind the Settings -> Statistics screen. These queries were
    // added with the Statistics card; they're easy to break via GROUP BY,
    // NULL-coalesce, ORDER BY, or LIMIT mistakes.
    //
    // Day-of-week / day-string assertions deliberately avoid pinning specific
    // weekday integers so the tests don't flake under different host TZs;
    // we only assert structure (cardinality, ordering, exclusion rules).

    @Test fun `observeTotalCount counts only live records for the requesting user`() = runTest {
        dao.insert(record("alive_1", "milk", purchasedAt = 100L))
        dao.insert(record("alive_2", "eggs", purchasedAt = 200L))
        // Tombstoned record must not be counted.
        dao.insert(record("dead", "milk", purchasedAt = 150L, deletedAt = 999L))
        // Other-user record must not be counted.
        dao.insert(record("other_user", "milk", purchasedAt = 300L, userId = "u2"))

        assertThat(dao.observeTotalCount(TEST_USER_ID).first()).isEqualTo(2)
        assertThat(dao.observeTotalCount("u2").first()).isEqualTo(1)
    }

    @Test fun `observeCountSince filters with a half-open lower bound and excludes tombstones`() = runTest {
        dao.insert(record("before", "milk", purchasedAt = 100L))
        dao.insert(record("on_boundary", "milk", purchasedAt = 200L))
        dao.insert(record("after", "milk", purchasedAt = 300L))
        dao.insert(record("after_dead", "milk", purchasedAt = 400L, deletedAt = 999L))

        // [200, ∞) → on_boundary + after, excluding the tombstoned row.
        assertThat(dao.observeCountSince(TEST_USER_ID, sinceMillis = 200L).first()).isEqualTo(2)
        // No rows in [9_999_999, ∞).
        assertThat(dao.observeCountSince(TEST_USER_ID, sinceMillis = 9_999_999L).first()).isEqualTo(0)
    }

    @Test fun `observePurchasesPerDay groups by local day, orders ASC, excludes tombstones and other users`() = runTest {
        // Three purchases spread across two UTC days, plus a same-day duplicate.
        // Use noon-UTC timestamps so the local-day classification stays stable
        // across reasonable host TZs (-12..+11).
        val day1Noon = 1_704_628_800_000L + 12 * 60 * 60 * 1000L // 2024-01-07T12:00:00Z
        val day2Noon = day1Noon + 24 * 60 * 60 * 1000L           // 2024-01-08T12:00:00Z

        dao.insert(record("a", "milk", purchasedAt = day1Noon))
        dao.insert(record("b", "milk", purchasedAt = day1Noon + 60_000L)) // same day, +1 min
        dao.insert(record("c", "eggs", purchasedAt = day2Noon))
        dao.insert(record("dead", "milk", purchasedAt = day2Noon, deletedAt = 999L))
        dao.insert(record("other_user", "milk", purchasedAt = day1Noon, userId = "u2"))

        val rows = dao.observePurchasesPerDay(TEST_USER_ID, sinceMillis = day1Noon).first()

        // Two distinct days; cardinality first.
        assertThat(rows).hasSize(2)
        // ASC by day.
        assertThat(rows.map { it.day }).isInOrder()
        // Total across days = live records for TEST_USER_ID (3, not 4 with tombstone, not 5 with cross-user).
        assertThat(rows.sumOf { it.count }).isEqualTo(3)
        // The earlier day has 2 records, the later has 1.
        assertThat(rows.first().count).isEqualTo(2)
        assertThat(rows.last().count).isEqualTo(1)
    }

    @Test fun `observePurchasesByDayOfWeek groups by DOW ordered count DESC, excluding tombstones`() = runTest {
        // Two purchases sharing a DOW, plus one on a different DOW.
        val baseSundayNoonUtc = 1_704_628_800_000L + 12 * 60 * 60 * 1000L // 2024-01-07T12:00:00Z
        val sevenDaysLater = baseSundayNoonUtc + 7 * 24 * 60 * 60 * 1000L  // same DOW
        val oneDayLater = baseSundayNoonUtc + 24 * 60 * 60 * 1000L         // different DOW

        dao.insert(record("a", "milk", purchasedAt = baseSundayNoonUtc))
        dao.insert(record("b", "milk", purchasedAt = sevenDaysLater))
        dao.insert(record("c", "eggs", purchasedAt = oneDayLater))
        dao.insert(record("dead", "milk", purchasedAt = baseSundayNoonUtc, deletedAt = 999L))

        val rows = dao.observePurchasesByDayOfWeek(TEST_USER_ID).first()

        // Two distinct DOW buckets; the one with 2 records first (count DESC).
        assertThat(rows).hasSize(2)
        assertThat(rows.first().count).isEqualTo(2)
        assertThat(rows.last().count).isEqualTo(1)
        assertThat(rows.map { it.count }).isInOrder(Comparator.reverseOrder<Int>())
        // DOW values in the documented 0..6 range.
        assertThat(rows.map { it.dayOfWeek }.all { it in 0..6 }).isTrue()
    }

    @Test fun `observeTopItems orders by count DESC and respects LIMIT`() = runTest {
        // milk: 3 records, eggs: 2 records.
        dao.insert(record("m1", "milk", purchasedAt = 100L))
        dao.insert(record("m2", "milk", purchasedAt = 200L))
        dao.insert(record("m3", "milk", purchasedAt = 300L))
        dao.insert(record("e1", "eggs", purchasedAt = 100L))
        dao.insert(record("e2", "eggs", purchasedAt = 200L))
        // Tombstone must drop out of the count.
        dao.insert(record("e_dead", "eggs", purchasedAt = 250L, deletedAt = 999L))

        val full = dao.observeTopItems(TEST_USER_ID, limit = 10).first()
        assertThat(full.map { it.itemId to it.count }).containsExactly(
            "milk" to 3,
            "eggs" to 2,
        ).inOrder()

        // LIMIT respected: only the top entry surfaces.
        val top1 = dao.observeTopItems(TEST_USER_ID, limit = 1).first()
        assertThat(top1.map { it.itemId }).containsExactly("milk")
    }

    @Test fun `observePurchasesByStore excludes null-store rows and orders by count DESC`() = runTest {
        // Add a second store so we have two with non-zero counts.
        db.storeDao().upsert(
            Store(
                id = "aldi", name = "Aldi", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )

        dao.insert(record("l1", "milk", storeId = "lidl", purchasedAt = 100L))
        dao.insert(record("l2", "milk", storeId = "lidl", purchasedAt = 200L))
        dao.insert(record("a1", "eggs", storeId = "aldi", purchasedAt = 300L))
        // null-store record must be excluded by the WHERE clause.
        dao.insert(record("nostore", "milk", storeId = null, purchasedAt = 400L))
        // Tombstoned record must not be counted.
        dao.insert(record("dead", "milk", storeId = "lidl", purchasedAt = 500L, deletedAt = 999L))

        val rows = dao.observePurchasesByStore(TEST_USER_ID).first()
        // Lidl with 2 ahead of Aldi with 1; null-store row absent.
        assertThat(rows.map { it.storeId to it.count }).containsExactly(
            "lidl" to 2,
            "aldi" to 1,
        ).inOrder()
    }

    @Test fun `observePurchasesByCategory coalesces null categoryId and joins live items only`() = runTest {
        // Add a second category and an uncategorised item.
        db.categoryDao().upsert(
            Category(
                id = "cat2", name = "cat2", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        db.itemDao().upsert(
            Item(
                id = "bread", name = "Bread", categoryId = "cat2", notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        db.itemDao().upsert(
            Item(
                id = "loose", name = "Loose", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        // Tombstoned item -- its purchase records must NOT contribute.
        db.itemDao().upsert(
            Item(
                id = "ghost", name = "Ghost", categoryId = "cat", notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = 50L,
            ),
        )

        // milk + eggs share categoryId "cat" (3 records); bread is "cat2" (1);
        // loose is uncategorised (1); ghost is tombstoned and must not surface.
        dao.insert(record("m1", "milk", purchasedAt = 100L))
        dao.insert(record("m2", "milk", purchasedAt = 200L))
        dao.insert(record("e1", "eggs", purchasedAt = 300L))
        dao.insert(record("b1", "bread", purchasedAt = 400L))
        dao.insert(record("u1", "loose", purchasedAt = 500L))
        dao.insert(record("ghost1", "ghost", purchasedAt = 600L)) // joined-out by item tombstone

        val rows = dao.observePurchasesByCategory(TEST_USER_ID).first()
        // cat=3 ahead of cat2=1 and ""=1; the empty-string bucket is the
        // COALESCE for null categoryId (uncategorised).
        assertThat(rows.map { it.categoryId to it.count }).containsExactly(
            "cat" to 3,
            "cat2" to 1,
            "" to 1,
        ).inOrder()
    }

    private fun record(
        id: String,
        itemId: String,
        purchasedAt: Long,
        deletedAt: Long? = null,
        userId: String = TEST_USER_ID,
        storeId: String? = "lidl",
    ) = PurchaseRecord(
        id = id, itemId = itemId, storeId = storeId,
        purchasedAt = purchasedAt, userId = userId,
        createdAt = purchasedAt, updatedAt = purchasedAt, deletedAt = deletedAt,
    )
}
