package com.storehop.app.data.dao

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Item
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ItemDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: ItemDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.itemDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert then observe emits the inserted item`() = runTest {
        dao.upsert(item(id = "i1", name = "Milk"))
        dao.observeNeeded(TEST_USER_ID).test {
            val first = awaitItem()
            assertThat(first).hasSize(1)
            assertThat(first.first().name).isEqualTo("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `softDelete sets deletedAt and removes the row from observeNeeded`() = runTest {
        dao.upsert(item(id = "i1", name = "Milk"))
        dao.observeNeeded(TEST_USER_ID).test {
            assertThat(awaitItem()).hasSize(1)
            dao.softDelete(TEST_USER_ID, "i1", now = 5_000L)
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `markPurchased flips isNeeded=0 so the item drops out of observeNeeded`() = runTest {
        dao.upsert(item(id = "i1", name = "Milk", isNeeded = true))
        dao.observeNeeded(TEST_USER_ID).test {
            assertThat(awaitItem().single().isNeeded).isTrue()
            dao.markPurchased(TEST_USER_ID, "i1", now = 9_000L)
            // Must vanish from observeNeeded since the query filters isNeeded = 1.
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeNeeded scopes by userId so other-user rows are filtered out`() = runTest {
        dao.upsert(item(id = "mine",   name = "Milk",  userId = TEST_USER_ID))
        dao.upsert(item(id = "theirs", name = "Bread", userId = "other-user"))
        dao.observeNeeded(TEST_USER_ID).test {
            assertThat(awaitItem().map { it.id }).containsExactly("mine")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun item(
        id: String,
        name: String,
        userId: String = TEST_USER_ID,
        isNeeded: Boolean = true,
    ) = Item(
        id = id, name = name, categoryId = null, notes = null, quantity = null,
        isNeeded = isNeeded, lastPurchasedAt = null, userId = userId,
        createdAt = 1_000L, updatedAt = 1_000L, deletedAt = null,
    )
}
