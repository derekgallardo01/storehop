package com.storehop.app.testing

import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store

/** The single uid every E2E test runs under (matches `LocalOnlyUserSessionProvider.LOCAL_ONLY`). */
const val E2E_UID: String = "local-only"

/**
 * Canonical fixture data for E2E tests. Hands back ids the test can use
 * to drive the UI:
 *  - 2 stores: "Lidl", "Aldi"
 *  - 1 category: "Dairy"
 *  - 3 items: "Milk" (tagged at both stores, needed), "Eggs" (Lidl only,
 *    needed), "Bread" (no tagged stores -- exercises the "+/- disabled"
 *    branch on the Items list).
 *
 * All rows alive, pendingSync=true (won't matter; the test SyncEngine has
 * a mocked Firestore so it does nothing). Suspends for the inserts.
 */
data class E2EFixtureIds(
    val storeLidlId: String,
    val storeAldiId: String,
    val categoryDairyId: String,
    val itemMilkId: String,
    val itemEggsId: String,
    val itemBreadId: String,
)

suspend fun seedE2EFixtures(
    itemDao: ItemDao,
    storeDao: StoreDao,
    categoryDao: CategoryDao,
    xrefDao: ItemStoreXrefDao,
): E2EFixtureIds {
    val now = 1_000L
    val ids = E2EFixtureIds(
        storeLidlId = "e2e_store_lidl",
        storeAldiId = "e2e_store_aldi",
        categoryDairyId = "e2e_cat_dairy",
        itemMilkId = "e2e_item_milk",
        itemEggsId = "e2e_item_eggs",
        itemBreadId = "e2e_item_bread",
    )

    storeDao.upsert(
        Store(
            id = ids.storeLidlId, name = "Lidl", colorArgb = null,
            isArchived = false, isSeeded = false, userId = E2E_UID,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    storeDao.upsert(
        Store(
            id = ids.storeAldiId, name = "Aldi", colorArgb = null,
            isArchived = false, isSeeded = false, userId = E2E_UID,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    categoryDao.upsert(
        Category(
            id = ids.categoryDairyId, name = "Dairy", nameKey = null,
            icon = null, isArchived = false, isSeeded = false, userId = E2E_UID,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    itemDao.upsert(
        Item(
            id = ids.itemMilkId, name = "Milk", brand = null,
            categoryId = ids.categoryDairyId, notes = null, quantity = null,
            isNeeded = true, isStaple = false, isPriority = false,
            imageUrl = null, lastPurchasedAt = null,
            userId = E2E_UID, createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    itemDao.upsert(
        Item(
            id = ids.itemEggsId, name = "Eggs", brand = null,
            categoryId = null, notes = null, quantity = null,
            isNeeded = true, isStaple = false, isPriority = false,
            imageUrl = null, lastPurchasedAt = null,
            userId = E2E_UID, createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    itemDao.upsert(
        Item(
            id = ids.itemBreadId, name = "Bread", brand = null,
            categoryId = null, notes = null, quantity = null,
            isNeeded = true, isStaple = false, isPriority = false,
            imageUrl = null, lastPurchasedAt = null,
            userId = E2E_UID, createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )

    // Milk -> tagged at both stores, needed.
    xrefDao.upsert(
        ItemStoreXref(
            itemId = ids.itemMilkId, storeId = ids.storeLidlId, userId = E2E_UID,
            isNeeded = true, lastPurchasedAt = null,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    xrefDao.upsert(
        ItemStoreXref(
            itemId = ids.itemMilkId, storeId = ids.storeAldiId, userId = E2E_UID,
            isNeeded = true, lastPurchasedAt = null,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    // Eggs -> tagged at Lidl only, needed.
    xrefDao.upsert(
        ItemStoreXref(
            itemId = ids.itemEggsId, storeId = ids.storeLidlId, userId = E2E_UID,
            isNeeded = true, lastPurchasedAt = null,
            createdAt = now, updatedAt = now, deletedAt = null,
            householdId = E2E_UID,
        ),
    )
    // Bread -> no tagged stores. Exercises the +/- disabled branch.
    return ids
}
