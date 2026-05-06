package com.storehop.app.data.dao

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
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
            dao.setArchived("cat_bbq", archived = true, now = 100L)
            assertThat(awaitItem()).isEmpty()
            dao.setArchived("cat_bbq", archived = false, now = 200L)
            assertThat(awaitItem().map { it.name }).containsExactly("BBQ")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeAll(includeArchived=true) shows archived categories`() = runTest {
        dao.upsert(cat("cat_bbq", "BBQ"))
        dao.setArchived("cat_bbq", archived = true, now = 100L)
        dao.observeAll(TEST_USER_ID, includeArchived = true).test {
            assertThat(awaitItem().map { it.name }).containsExactly("BBQ")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `softDelete removes from both observeAll variants`() = runTest {
        dao.upsert(cat("cat_bbq", "BBQ"))
        dao.softDelete("cat_bbq", now = 100L)
        dao.observeAll(TEST_USER_ID, includeArchived = true).test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun cat(id: String, name: String) = Category(
        id = id, name = name, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
