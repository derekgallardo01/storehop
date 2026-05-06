package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.util.UserSessionProvider
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
            session = object : UserSessionProvider {
                override fun currentUserId(): String = TEST_USER_ID
            },
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

    private fun record(id: String, itemId: String, purchasedAt: Long) = PurchaseRecord(
        id = id, itemId = itemId, storeId = null,
        purchasedAt = purchasedAt, userId = TEST_USER_ID,
        createdAt = purchasedAt, updatedAt = purchasedAt, deletedAt = null,
    )
}
