package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
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

/**
 * Pin StoreCategoryOrderRepository.reorderCategoriesForStore. Two contracts:
 *  1. Atomic replace: rows not in the new list get tombstoned; rows in the
 *     new list get displayOrder assigned by index.
 *  2. Per-store independence: reordering Lidl's aisles doesn't touch Aldi's.
 */
@RunWith(RobolectricTestRunner::class)
class StoreCategoryOrderRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: StoreCategoryOrderRepositoryImpl
    private val now = 50_000L

    @Before fun setup() {
        db = createTestDb(seeded = false)
        repo = StoreCategoryOrderRepositoryImpl(
            dao = db.storeCategoryOrderDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            session = FakeSessionProvider(TEST_USER_ID),
        )
        // FK preconditions: SCO references stores + categories.
        kotlinx.coroutines.runBlocking {
            listOf("store_lidl", "store_aldi").forEach { id ->
                db.storeDao().upsert(
                    Store(
                        id = id, name = id, colorArgb = null,
                        isArchived = false, isSeeded = true, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    ),
                )
            }
            listOf("cat_produce", "cat_dairy_eggs", "cat_bakery").forEach { id ->
                db.categoryDao().upsert(
                    Category(
                        id = id, name = id, nameKey = id, icon = null,
                        isArchived = false, isSeeded = true, userId = TEST_USER_ID,
                        createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    ),
                )
            }
        }
    }

    @After fun tearDown() { db.close() }

    @Test fun `reorderCategoriesForStore writes the new order with index-based displayOrder`() = runTest {
        repo.reorderCategoriesForStore(
            storeId = "store_lidl",
            orderedCategoryIds = listOf("cat_dairy_eggs", "cat_produce", "cat_bakery"),
        )

        val rows = repo.observeForStore("store_lidl").first()
        assertThat(rows.map { it.categoryId }).containsExactly(
            "cat_dairy_eggs", "cat_produce", "cat_bakery",
        ).inOrder()
        assertThat(rows.map { it.displayOrder }).containsExactly(0, 1, 2).inOrder()
    }

    @Test fun `reorder tombstones rows that are not in the new list`() = runTest {
        // Seed three categories at Lidl.
        repo.reorderCategoriesForStore(
            storeId = "store_lidl",
            orderedCategoryIds = listOf("cat_produce", "cat_dairy_eggs", "cat_bakery"),
        )

        // User decides bakery isn't relevant at Lidl. Reorder without it.
        repo.reorderCategoriesForStore(
            storeId = "store_lidl",
            orderedCategoryIds = listOf("cat_dairy_eggs", "cat_produce"),
        )

        val live = repo.observeForStore("store_lidl").first()
        assertThat(live.map { it.categoryId }).containsExactly(
            "cat_dairy_eggs", "cat_produce",
        ).inOrder()
        // Bakery row still in DB but tombstoned -- raw count includes it.
        val total = db.openHelper.readableDatabase
            .query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT COUNT(*) FROM store_category_order WHERE storeId = 'store_lidl'",
                ),
            )
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertThat(total).isEqualTo(3)
    }

    @Test fun `reorder is per-store - changing Lidl does not touch Aldi`() = runTest {
        // Seed both stores with the same three categories.
        repo.reorderCategoriesForStore("store_lidl", listOf("cat_produce", "cat_dairy_eggs"))
        repo.reorderCategoriesForStore("store_aldi", listOf("cat_dairy_eggs", "cat_produce"))

        // Reorder Lidl alone.
        repo.reorderCategoriesForStore("store_lidl", listOf("cat_bakery", "cat_produce"))

        val lidl = repo.observeForStore("store_lidl").first()
        val aldi = repo.observeForStore("store_aldi").first()
        // Lidl's order matches the new assignment.
        assertThat(lidl.map { it.categoryId }).containsExactly("cat_bakery", "cat_produce").inOrder()
        // Aldi is untouched.
        assertThat(aldi.map { it.categoryId }).containsExactly("cat_dairy_eggs", "cat_produce").inOrder()
    }

    @Test fun `empty list tombstones every existing SCO row for the store`() = runTest {
        repo.reorderCategoriesForStore("store_lidl", listOf("cat_produce", "cat_dairy_eggs"))
        repo.reorderCategoriesForStore("store_lidl", emptyList())

        assertThat(repo.observeForStore("store_lidl").first()).isEmpty()
    }

    @Test fun `repeating an existing order is a no-op for displayOrder`() = runTest {
        repo.reorderCategoriesForStore("store_lidl", listOf("cat_produce", "cat_dairy_eggs"))
        // Same list again — should be idempotent.
        repo.reorderCategoriesForStore("store_lidl", listOf("cat_produce", "cat_dairy_eggs"))

        val rows = repo.observeForStore("store_lidl").first()
        assertThat(rows.map { it.categoryId to it.displayOrder })
            .containsExactly("cat_produce" to 0, "cat_dairy_eggs" to 1).inOrder()
    }
}
