package com.storehop.app.data.dao

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreCategoryOrderDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: StoreCategoryOrderDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.storeCategoryOrderDao()
        kotlinx.coroutines.runBlocking {
            db.storeDao().upsert(store("store_lidl"))
            listOf("cat_a", "cat_b", "cat_c", "cat_d").forEach {
                db.categoryDao().upsert(cat(it))
            }
        }
    }
    @After fun tearDown() { db.close() }

    @Test fun `replaceAllForStore upserts the new ordered set and tombstones removed entries`() = runTest {
        // Initial: a, b, c.
        dao.replaceAllForStore("store_lidl", listOf(
            sco("cat_a", 0), sco("cat_b", 1), sco("cat_c", 2),
        ), now = 100L)
        assertThat(dao.findForStore("store_lidl").map { it.categoryId })
            .containsExactly("cat_a", "cat_b", "cat_c")

        // New ordering: d, a (b and c are removed; d is added; a moves to position 1).
        dao.replaceAllForStore("store_lidl", listOf(
            sco("cat_d", 0), sco("cat_a", 1),
        ), now = 200L)

        val live = dao.findForStore("store_lidl").sortedBy { it.displayOrder }
        assertThat(live.map { it.categoryId }).containsExactly("cat_d", "cat_a").inOrder()
    }

    @Test fun `observeForStore re-emits a single coherent state after replaceAllForStore`() = runTest {
        dao.replaceAllForStore("store_lidl", listOf(
            sco("cat_a", 0), sco("cat_b", 1),
        ), now = 100L)

        dao.observeForStore("store_lidl").test {
            assertThat(awaitItem().map { it.categoryId }).containsExactly("cat_a", "cat_b").inOrder()

            dao.replaceAllForStore("store_lidl", listOf(
                sco("cat_b", 0), sco("cat_a", 1), sco("cat_c", 2),
            ), now = 200L)

            // The replaceAllForStore wrapper is @Transaction — observers should see the
            // final consistent state, not an intermediate "b removed" snapshot.
            val finalState = awaitItem()
            assertThat(finalState.map { it.categoryId })
                .containsExactly("cat_b", "cat_a", "cat_c").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun store(id: String) = Store(
        id = id, name = id, colorArgb = null, isArchived = false, isSeeded = false,
        userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun cat(id: String) = Category(
        id = id, name = id, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun sco(categoryId: String, order: Int) = StoreCategoryOrder(
        storeId = "store_lidl", categoryId = categoryId, displayOrder = order,
        isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
