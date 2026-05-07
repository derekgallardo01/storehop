package com.storehop.app.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.OTHER_USER_ID
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository-level coverage for the Shop tab's two main data sources.
 * The DAO query already has its own test (ShoppingDaoTest); this fills
 * the gap around the userId-flow integration:
 *  - empty when no session uid
 *  - re-keys queries when the session uid changes (sign-in / sign-out)
 *  - StorePickerRow combine() correctly augments stores with their needed +
 *    session-picked-up + critical-item counts.
 */
@RunWith(RobolectricTestRunner::class)
class ShoppingRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var session: FakeSessionProvider
    private lateinit var repo: ShoppingRepositoryImpl

    @Before fun setup() {
        db = createTestDb(seeded = false)
        session = FakeSessionProvider(initial = TEST_USER_ID)
        repo = ShoppingRepositoryImpl(
            dao = db.shoppingDao(),
            storeDao = db.storeDao(),
            session = session,
        )
        seedFixture(TEST_USER_ID)
    }

    @After fun tearDown() { db.close() }

    @Test fun `shoppingListForStore returns empty list when session uid is null`() = runTest {
        session.setUserId(null)
        repo.shoppingListForStore("store_lidl", NO_WINDOW).test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `shoppingListForStore returns the active uid's items`() = runTest {
        repo.shoppingListForStore("store_lidl", NO_WINDOW).test {
            val rows = awaitItem()
            assertThat(rows.map { it.itemName }).containsExactly("Milk", "Eggs")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `shoppingListForStore re-keys when session uid changes`() = runTest {
        // Seed a different uid's data alongside TEST_USER_ID's.
        seedFixture(OTHER_USER_ID, itemNamePrefix = "Other-")

        repo.shoppingListForStore("store_lidl", NO_WINDOW).test {
            // First emission: TEST_USER_ID's items.
            assertThat(awaitItem().map { it.itemName }).containsExactly("Milk", "Eggs")

            // Sign-in to a different uid -> flow re-emits with that uid's items.
            session.setUserId(OTHER_USER_ID)
            assertThat(awaitItem().map { it.itemName })
                .containsExactly("Other-Milk", "Other-Eggs")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeStorePickerRows returns empty when session uid is null`() = runTest {
        session.setUserId(null)
        repo.observeStorePickerRows(NO_WINDOW).test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeStorePickerRows reports needed and critical counts per store`() = runTest {
        // Re-upsert the existing milk row with isPriority=true. The seeded id
        // is uid-prefixed so two seeded uids' rows can't collide on PK.
        val milkId = "$TEST_USER_ID-milk"
        db.itemDao().upsert(item(milkId, "Milk", "cat_dairy_eggs", isPriority = true))

        repo.observeStorePickerRows(NO_WINDOW).test {
            val rows = awaitItem().associateBy { it.store.id }
            // Lidl: both Milk + Eggs needed (2), Milk is critical.
            assertThat(rows["store_lidl"]!!.neededCount).isEqualTo(2)
            assertThat(rows["store_lidl"]!!.criticalItemNames).containsExactly("Milk")
            assertThat(rows["store_lidl"]!!.pickedUpInSessionCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeStorePickerRows excludes archived stores`() = runTest {
        // Archive Lidl; Continente should still be present, Lidl should not.
        db.storeDao().upsert(store("store_lidl", "Lidl", isArchived = true))

        repo.observeStorePickerRows(NO_WINDOW).test {
            val rows = awaitItem().map { it.store.id }
            assertThat(rows).doesNotContain("store_lidl")
            assertThat(rows).contains("store_continente")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------- fixture helpers --------

    private fun seedFixture(userId: String, itemNamePrefix: String = "") {
        kotlinx.coroutines.runBlocking {
            db.storeDao().upsert(store("store_lidl", "Lidl", userId = userId))
            db.storeDao().upsert(store("store_continente", "Continente", userId = userId))
            db.categoryDao().upsert(cat("cat_dairy_eggs", "Dairy & Eggs", userId = userId))
            db.storeCategoryOrderDao().upsert(
                sco("store_lidl", "cat_dairy_eggs", 0, userId = userId),
            )
            db.storeCategoryOrderDao().upsert(
                sco("store_continente", "cat_dairy_eggs", 0, userId = userId),
            )
            // Use uid-prefixed item ids so two seeded uids don't collide on PK.
            val milkId = "$userId-milk".take(64)
            val eggsId = "$userId-eggs".take(64)
            db.itemDao().upsert(
                item(milkId, "${itemNamePrefix}Milk", "cat_dairy_eggs", userId = userId),
            )
            db.itemDao().upsert(
                item(eggsId, "${itemNamePrefix}Eggs", "cat_dairy_eggs", userId = userId, isStaple = true),
            )
            db.itemStoreXrefDao().upsert(xref(milkId, "store_lidl", userId = userId))
            db.itemStoreXrefDao().upsert(xref(eggsId, "store_lidl", userId = userId))
        }
    }

    private fun store(id: String, name: String, isArchived: Boolean = false, userId: String = TEST_USER_ID) = Store(
        id = id, name = name, colorArgb = null,
        isArchived = isArchived, isSeeded = false, userId = userId,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun cat(id: String, name: String, userId: String = TEST_USER_ID) = Category(
        id = id, name = name, nameKey = id, icon = null,
        isArchived = false, isSeeded = false, userId = userId,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun sco(storeId: String, categoryId: String, order: Int, userId: String = TEST_USER_ID) = StoreCategoryOrder(
        storeId = storeId, categoryId = categoryId, displayOrder = order,
        isSeeded = false, userId = userId,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun item(
        id: String, name: String, categoryId: String?,
        userId: String = TEST_USER_ID,
        isPriority: Boolean = false,
        isStaple: Boolean = false,
    ) = Item(
        id = id, name = name, categoryId = categoryId, notes = null, quantity = null,
        isNeeded = true, lastPurchasedAt = null, userId = userId,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
        isPriority = isPriority, isStaple = isStaple,
    )

    private fun xref(itemId: String, storeId: String, userId: String = TEST_USER_ID) = ItemStoreXref(
        itemId = itemId, storeId = storeId, userId = userId,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    companion object {
        private const val NO_WINDOW = Long.MAX_VALUE
    }
}
