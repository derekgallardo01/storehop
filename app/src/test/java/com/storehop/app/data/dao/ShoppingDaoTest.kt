package com.storehop.app.data.dao

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
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

/**
 * The cross-cutting query that powers "Shop at Store". The behaviour we care about:
 *  1. Per-store displayOrder differs across stores even when the items are the same.
 *  2. isNeeded = 0 items are filtered out (cross-store completion sync).
 *  3. Items in categories without a StoreCategoryOrder for that store fall to the bottom.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingDaoTest {

    private lateinit var db: StorehopDatabase

    @Before fun setup() {
        db = createTestDb(seeded = false)
        seedFixture()
    }
    @After fun tearDown() { db.close() }

    @Test fun `Lidl orders Produce before Dairy (items alphabetical within each)`() = runTest {
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_lidl").test {
            val rows = awaitItem()
            val names = rows.map { it.itemName }
            // Produce displayOrder=0: Bananas, Tomatoes (alphabetical)
            // Dairy   displayOrder=1: Eggs, Milk     (alphabetical)
            assertThat(names).containsExactly("Bananas", "Tomatoes", "Eggs", "Milk").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `Continente orders Dairy before Produce (items alphabetical within each)`() = runTest {
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_continente").test {
            val rows = awaitItem()
            val names = rows.map { it.itemName }
            assertThat(names).containsExactly("Eggs", "Milk", "Bananas", "Tomatoes").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `marking an item not-needed removes it from both store views`() = runTest {
        db.itemDao().markPurchased("milk", now = 100L)
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_lidl").test {
            assertThat(awaitItem().map { it.itemName }).doesNotContain("Milk")
            cancelAndIgnoreRemainingEvents()
        }
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_continente").test {
            assertThat(awaitItem().map { it.itemName }).doesNotContain("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `items in a category with no StoreCategoryOrder for the store fall to the end`() = runTest {
        // "Wine" is in cat_alcohol — Lidl has it ordered; Continente doesn't (we leave it out
        // of the fixture's Continente aisle plan), so it should land at the bottom there.
        db.itemDao().upsert(item("wine", "Wine", categoryId = "cat_alcohol"))
        db.itemStoreXrefDao().upsert(xref("wine", "store_continente"))
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_continente").test {
            val names = awaitItem().map { it.itemName }
            assertThat(names.last()).isEqualTo("Wine")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `the query is scoped by userId on both items and item_store_xref`() = runTest {
        // Insert another user's item tagged to the same store and confirm it's invisible.
        runBlockingDb {
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = "their_milk", name = "OtherMilk", categoryId = "cat_dairy_eggs",
                    notes = null, quantity = null, isNeeded = true, lastPurchasedAt = null,
                    userId = "other-user", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = "their_milk", storeId = "store_lidl", userId = "other-user",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
        }
        db.shoppingDao().shoppingListForStore(TEST_USER_ID, "store_lidl").test {
            val names = awaitItem().map { it.itemName }
            assertThat(names).doesNotContain("OtherMilk")
            cancelAndIgnoreRemainingEvents()
        }
        // And the other user only sees their own item.
        db.shoppingDao().shoppingListForStore("other-user", "store_lidl").test {
            assertThat(awaitItem().map { it.itemName }).containsExactly("OtherMilk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun seedFixture() {
        // Two stores, two categories, two items per category, both items tagged to both stores.
        // Lidl puts Produce in aisle 0, Dairy in aisle 1. Continente flips them.
        listOf(
            store("store_lidl", "Lidl"),
            store("store_continente", "Continente"),
        ).forEach { runBlockingDb { db.storeDao().upsert(it) } }

        listOf(
            cat("cat_produce", "Produce"),
            cat("cat_dairy_eggs", "Dairy & Eggs"),
            cat("cat_alcohol", "Alcohol"),
        ).forEach { runBlockingDb { db.categoryDao().upsert(it) } }

        // Lidl: Produce=0, Dairy=1, Alcohol=2
        listOf(
            sco("store_lidl", "cat_produce", 0),
            sco("store_lidl", "cat_dairy_eggs", 1),
            sco("store_lidl", "cat_alcohol", 2),
        ).forEach { runBlockingDb { db.storeCategoryOrderDao().upsert(it) } }

        // Continente: Dairy=0, Produce=1 (no row for Alcohol -> falls to end)
        listOf(
            sco("store_continente", "cat_dairy_eggs", 0),
            sco("store_continente", "cat_produce", 1),
        ).forEach { runBlockingDb { db.storeCategoryOrderDao().upsert(it) } }

        listOf(
            item("bananas", "Bananas", categoryId = "cat_produce"),
            item("tomatoes", "Tomatoes", categoryId = "cat_produce"),
            item("milk", "Milk", categoryId = "cat_dairy_eggs"),
            item("eggs", "Eggs", categoryId = "cat_dairy_eggs"),
        ).forEach { runBlockingDb { db.itemDao().upsert(it) } }

        listOf(
            xref("bananas", "store_lidl"),  xref("bananas", "store_continente"),
            xref("tomatoes", "store_lidl"), xref("tomatoes", "store_continente"),
            xref("milk", "store_lidl"),     xref("milk", "store_continente"),
            xref("eggs", "store_lidl"),     xref("eggs", "store_continente"),
        ).forEach { runBlockingDb { db.itemStoreXrefDao().upsert(it) } }
    }

    private fun store(id: String, name: String) = Store(
        id = id, name = name, colorArgb = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun cat(id: String, name: String) = Category(
        id = id, name = name, nameKey = id, icon = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun sco(storeId: String, categoryId: String, order: Int) = StoreCategoryOrder(
        storeId = storeId, categoryId = categoryId, displayOrder = order,
        isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun item(id: String, name: String, categoryId: String?) = Item(
        id = id, name = name, categoryId = categoryId, notes = null, quantity = null,
        isNeeded = true, lastPurchasedAt = null, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun xref(itemId: String, storeId: String) = ItemStoreXref(
        itemId = itemId, storeId = storeId, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun runBlockingDb(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
