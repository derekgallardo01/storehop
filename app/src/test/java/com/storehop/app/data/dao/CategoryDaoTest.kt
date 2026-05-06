package com.storehop.app.data.dao

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: CategoryDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.categoryDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `archive hides from observeAll(includeArchived=false) and unarchive restores it`() = runTest {
        dao.upsert(cat("cat_bbq", "BBQ"))
        dao.observeAll(TEST_USER_ID, includeArchived = false).test {
            assertThat(awaitItem().map { it.name }).containsExactly("BBQ")
            dao.setArchived(TEST_USER_ID, "cat_bbq", archived = true, now = 100L)
            assertThat(awaitItem()).isEmpty()
            dao.setArchived(TEST_USER_ID, "cat_bbq", archived = false, now = 200L)
            assertThat(awaitItem().map { it.name }).containsExactly("BBQ")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeAll(includeArchived=true) shows archived categories`() = runTest {
        dao.upsert(cat("cat_bbq", "BBQ"))
        dao.setArchived(TEST_USER_ID, "cat_bbq", archived = true, now = 100L)
        dao.observeAll(TEST_USER_ID, includeArchived = true).test {
            assertThat(awaitItem().map { it.name }).containsExactly("BBQ")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `softDelete removes from both observeAll variants`() = runTest {
        dao.upsert(cat("cat_bbq", "BBQ"))
        dao.softDelete(TEST_USER_ID, "cat_bbq", now = 100L)
        dao.observeAll(TEST_USER_ID, includeArchived = true).test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `NONA-8 archive preserves item references and store_category_order rows`() = runTest {
        // Plan called for archive being non-destructive: items in the category retain
        // their categoryId, and StoreCategoryOrder rows for the archived category
        // remain in place. Pin those properties so a future setArchived refactor
        // can't accidentally cascade like softDelete does.
        dao.upsert(cat("cat_bbq", "BBQ"))
        // Add an item that references the category and an SCO row that references it.
        db.itemDao().upsert(
            com.storehop.app.data.entity.Item(
                id = "ribs", name = "Ribs", categoryId = "cat_bbq",
                notes = null, quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        db.storeDao().upsert(
            com.storehop.app.data.entity.Store(
                id = "store_x", name = "X", colorArgb = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        db.storeCategoryOrderDao().upsert(
            com.storehop.app.data.entity.StoreCategoryOrder(
                storeId = "store_x", categoryId = "cat_bbq", displayOrder = 0,
                isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )

        dao.setArchived(TEST_USER_ID, "cat_bbq", archived = true, now = 100L)

        // Item still references cat_bbq.
        val items = db.itemDao().observeNeeded(TEST_USER_ID).first()
        assertThat(items.single { it.id == "ribs" }.categoryId).isEqualTo("cat_bbq")
        // SCO row still live.
        val sco = db.storeCategoryOrderDao().findForStore("store_x")
        assertThat(sco.map { it.categoryId }).containsExactly("cat_bbq")
    }

    @Test fun `NONA-8 two different users can each have a category named the same`() = runTest {
        // The (userId, name) unique index is per-user, not global. Document.
        dao.upsert(cat("cat_user_a", "Wine").copy(userId = "user-A"))
        dao.upsert(cat("cat_user_b", "Wine").copy(userId = "user-B"))
        assertThat(dao.observeAll("user-A", includeArchived = false).first().map { it.name })
            .containsExactly("Wine")
        assertThat(dao.observeAll("user-B", includeArchived = false).first().map { it.name })
            .containsExactly("Wine")
    }

    private fun cat(id: String, name: String) = Category(
        id = id, name = name, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
