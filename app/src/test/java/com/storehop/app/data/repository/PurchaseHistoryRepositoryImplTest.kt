package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.testing.FakeSessionProvider
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

@RunWith(RobolectricTestRunner::class)
class PurchaseHistoryRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: PurchaseHistoryRepositoryImpl

    @Before fun setup() {
        db = createTestDb(seeded = false)
        repo = PurchaseHistoryRepositoryImpl(
            dao = db.purchaseRecordDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider(TEST_USER_ID),
        )
    }

    @After fun tearDown() { db.close() }

    @Test fun `observeForItem returns inserted records ordered newest-first`() = runTest {
        listOf(100L, 300L, 200L).forEachIndexed { i, ts ->
            db.purchaseRecordDao().insert(record(id = "p$i", itemId = "milk", purchasedAt = ts))
        }
        val ts = repo.observeForItem("milk").first().map { it.purchasedAt }
        assertThat(ts).containsExactly(300L, 200L, 100L).inOrder()
    }

    @Test fun `softDelete sets deletedAt on the record so observeForItem stops returning it`() = runTest {
        db.purchaseRecordDao().insert(record(id = "p1", itemId = "milk", purchasedAt = 100L))
        assertThat(repo.observeForItem("milk").first()).hasSize(1)

        repo.softDelete("p1")

        assertThat(repo.observeForItem("milk").first()).isEmpty()
    }

    // ---- Statistics-screen aggregate paths --------------------------------

    @Test fun `observeTotalCount counts only the active session's live records`() = runTest {
        db.purchaseRecordDao().insert(record(id = "p1", itemId = "milk", purchasedAt = 100L))
        db.purchaseRecordDao().insert(record(id = "p2", itemId = "eggs", purchasedAt = 200L))
        db.purchaseRecordDao().insert(record(id = "p3", itemId = "x", purchasedAt = 300L, userId = "OTHER"))
        assertThat(repo.observeTotalCount().first()).isEqualTo(2)
    }

    @Test fun `observeCountSince filters to the half-open window`() = runTest {
        db.purchaseRecordDao().insert(record(id = "old", itemId = "milk", purchasedAt = 100L))
        db.purchaseRecordDao().insert(record(id = "edge", itemId = "milk", purchasedAt = 200L))
        db.purchaseRecordDao().insert(record(id = "after", itemId = "milk", purchasedAt = 300L))
        // sinceMillis = 200 -> includes 200 and 300, excludes 100.
        assertThat(repo.observeCountSince(200L).first()).isEqualTo(2)
    }

    @Test fun `observePurchasesPerDay groups records by day key in ascending order`() = runTest {
        // Three records on two distinct days.
        db.purchaseRecordDao().insert(record(id = "a", itemId = "milk", purchasedAt = 100L))
        db.purchaseRecordDao().insert(record(id = "b", itemId = "milk", purchasedAt = 200L))
        db.purchaseRecordDao().insert(record(id = "c", itemId = "milk",
            purchasedAt = 100L + 24L * 3_600_000L))
        val days = repo.observePurchasesPerDay(0L).first()
        // Two day buckets, order ascending.
        assertThat(days.size).isEqualTo(2)
        assertThat(days[0].day).isLessThan(days[1].day)
    }

    @Test fun `observePurchasesByDayOfWeek + observeTopItems + observePurchasesByStore + observePurchasesByCategory all proxy to the DAO`() = runTest {
        db.purchaseRecordDao().insert(record(id = "p1", itemId = "milk", purchasedAt = 100L))
        // Sanity: every aggregate flow returns a non-null list (proves the
        // session.userId.flatMapLatest path on the repo side; the SQL
        // contract is owned by PurchaseRecordDaoTest).
        assertThat(repo.observePurchasesByDayOfWeek().first()).isNotNull()
        assertThat(repo.observeTopItems(5).first()).isNotNull()
        assertThat(repo.observePurchasesByStore().first()).isNotNull()
        assertThat(repo.observePurchasesByCategory().first()).isNotNull()
    }

    @Test fun `softDelete throws when not signed in`() = runTest {
        val signedOut = PurchaseHistoryRepositoryImpl(
            dao = db.purchaseRecordDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider(null),
        )
        try {
            signedOut.softDelete("p1")
            org.junit.Assert.fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // Expected -- the repo guards against a signed-out caller.
        }
    }

    @Test fun `every observe stream emits empty list (or 0 count) when signed out`() = runTest {
        val signedOut = PurchaseHistoryRepositoryImpl(
            dao = db.purchaseRecordDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider(null),
        )
        assertThat(signedOut.observeForItem("milk").first()).isEmpty()
        assertThat(signedOut.observeTotalCount().first()).isEqualTo(0)
        assertThat(signedOut.observeCountSince(0L).first()).isEqualTo(0)
        assertThat(signedOut.observePurchasesPerDay(0L).first()).isEmpty()
        assertThat(signedOut.observePurchasesByDayOfWeek().first()).isEmpty()
        assertThat(signedOut.observeTopItems(5).first()).isEmpty()
        assertThat(signedOut.observePurchasesByStore().first()).isEmpty()
        assertThat(signedOut.observePurchasesByCategory().first()).isEmpty()
    }

    private fun record(id: String, itemId: String, purchasedAt: Long, userId: String = TEST_USER_ID) = PurchaseRecord(
        id = id, itemId = itemId, storeId = null,
        purchasedAt = purchasedAt, userId = userId,
        createdAt = purchasedAt, updatedAt = purchasedAt, deletedAt = null,
    )
}
