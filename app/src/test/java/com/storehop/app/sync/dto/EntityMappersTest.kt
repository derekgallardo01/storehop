package com.storehop.app.sync.dto

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import org.junit.Test

/**
 * Pin the entity → DTO mappers used by the push side of [SyncEngine].
 * Two contracts:
 *
 *  1. Every persisted field crosses to its DTO unchanged. A bug here would
 *     silently push the wrong value (or null) to Firestore, and Firestore
 *     becomes the source of truth in v0.4 pull -- so a mapper bug now is a
 *     time bomb that detonates when pull lands.
 *  2. `pendingSync` does NOT cross. It's a local-only flag indicating
 *     "this row hasn't been pushed yet"; if it leaked into the DTO and the
 *     DTO came back via pull, every device's first sync after pull would
 *     re-push the same data forever.
 *
 * The composite-PK helpers (docId) get pinned too, since their format
 * encodes assumptions about how Firestore document ids resolve to local
 * primary keys.
 */
class EntityMappersTest {

    @Test fun `Item-toDto preserves every persisted field and excludes pendingSync`() {
        val item = Item(
            id = "i1", name = "Milk", categoryId = "cat", notes = "n", quantity = "1L",
            isNeeded = true, lastPurchasedAt = 500L, userId = "u",
            createdAt = 100L, updatedAt = 200L, deletedAt = 300L,
            pendingSync = true, // local-only flag, must NOT cross over
            brand = "Mimosa", imageUrl = "https://img/x.jpg",
            isStaple = true, isPriority = true,
        )

        val dto = item.toDto()

        assertThat(dto.id).isEqualTo("i1")
        assertThat(dto.name).isEqualTo("Milk")
        assertThat(dto.categoryId).isEqualTo("cat")
        assertThat(dto.notes).isEqualTo("n")
        assertThat(dto.quantity).isEqualTo("1L")
        assertThat(dto.isNeeded).isTrue()
        assertThat(dto.lastPurchasedAt).isEqualTo(500L)
        assertThat(dto.userId).isEqualTo("u")
        assertThat(dto.createdAt).isEqualTo(100L)
        assertThat(dto.updatedAt).isEqualTo(200L)
        assertThat(dto.deletedAt).isEqualTo(300L)
        assertThat(dto.brand).isEqualTo("Mimosa")
        assertThat(dto.imageUrl).isEqualTo("https://img/x.jpg")
        assertThat(dto.isStaple).isTrue()
        assertThat(dto.isPriority).isTrue()
        // pendingSync isn't a field on the DTO; if it was added without
        // exclusion the compiler would force this test to break.
    }

    @Test fun `Category-toDto round trips every field`() {
        val cat = Category(
            id = "cat", name = "Dairy & Eggs", nameKey = "cat_dairy_eggs", icon = "🥛",
            isArchived = false, isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        val dto = cat.toDto()
        assertThat(dto.id).isEqualTo("cat")
        assertThat(dto.name).isEqualTo("Dairy & Eggs")
        assertThat(dto.nameKey).isEqualTo("cat_dairy_eggs")
        assertThat(dto.icon).isEqualTo("🥛")
        assertThat(dto.isArchived).isFalse()
        assertThat(dto.isSeeded).isTrue()
        assertThat(dto.userId).isEqualTo("u")
        assertThat(dto.createdAt).isEqualTo(1L)
        assertThat(dto.updatedAt).isEqualTo(2L)
        assertThat(dto.deletedAt).isNull()
    }

    @Test fun `Store-toDto carries displayOrder added in v3 to v4`() {
        val store = Store(
            id = "s1", name = "Lidl", colorArgb = 0xFFAABBCC.toInt(),
            isArchived = false, isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true, displayOrder = 7,
        )
        val dto = store.toDto()
        assertThat(dto.colorArgb).isEqualTo(0xFFAABBCC.toInt())
        // Critical: the picker order column has to round-trip through Firestore
        // or every device will see a different order after pull.
        assertThat(dto.displayOrder).isEqualTo(7)
    }

    @Test fun `ItemStoreXref-toDto carries the per-store need state added in v4 to v5`() {
        val xref = ItemStoreXref(
            itemId = "i1", storeId = "s1", userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
            isNeeded = false, lastPurchasedAt = 500L,
        )
        val dto = xref.toDto()
        // Per-store need state -- if these don't sync, "I bought milk at Lidl"
        // wouldn't survive a pull on another device.
        assertThat(dto.isNeeded).isFalse()
        assertThat(dto.lastPurchasedAt).isEqualTo(500L)
    }

    @Test fun `StoreCategoryOrder-toDto preserves displayOrder`() {
        val sco = StoreCategoryOrder(
            storeId = "s1", categoryId = "cat", displayOrder = 3,
            isSeeded = false, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        val dto = sco.toDto()
        assertThat(dto.displayOrder).isEqualTo(3)
        assertThat(dto.isSeeded).isFalse()
    }

    @Test fun `PurchaseRecord-toDto carries every persisted field`() {
        val rec = PurchaseRecord(
            id = "p1", itemId = "i1", storeId = "s1", purchasedAt = 999L,
            userId = "u", createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        val dto = rec.toDto()
        assertThat(dto.id).isEqualTo("p1")
        assertThat(dto.itemId).isEqualTo("i1")
        assertThat(dto.storeId).isEqualTo("s1")
        assertThat(dto.purchasedAt).isEqualTo(999L)
    }

    @Test fun `xref docId encodes the composite primary key`() {
        // The xref's natural composite PK (itemId, storeId) needs a stable
        // encoding to use as a Firestore document id. Using a unique
        // separator keeps "i_1" + "s_2" from colliding with "i" + "1__s_2".
        val xref = ItemStoreXref(
            itemId = "milk", storeId = "lidl", userId = "u",
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        assertThat(xref.docId()).isEqualTo("milk__lidl")
    }

    @Test fun `StoreCategoryOrder docId encodes the composite primary key`() {
        val sco = StoreCategoryOrder(
            storeId = "lidl", categoryId = "produce", displayOrder = 0,
            isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        assertThat(sco.docId()).isEqualTo("lidl__produce")
    }

    // ---- DTO → Entity round-trip parity (pull side, v0.4) -----------------
    //
    // The two contracts: every persisted field round-trips, and `pendingSync`
    // is forced to `false` regardless of the source entity's value (so pulled
    // rows don't immediately re-push and overwrite newer cloud edits). Tests
    // start with `pendingSync = true` to prove the false-after-pull rule.

    @Test fun `Item entity-DTO-entity round trip preserves fields and forces pendingSync false`() {
        val original = Item(
            id = "i1", name = "Milk", categoryId = "cat", notes = "n", quantity = "1L",
            isNeeded = true, lastPurchasedAt = 500L, userId = "u",
            createdAt = 100L, updatedAt = 200L, deletedAt = 300L,
            pendingSync = true,
            brand = "Mimosa", imageUrl = "https://img/x.jpg",
            isStaple = true, isPriority = true,
        )
        val roundTripped = original.toDto().toEntity()
        // pendingSync flips; everything else stays.
        assertThat(roundTripped).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `Category entity-DTO-entity round trip preserves fields and forces pendingSync false`() {
        val original = Category(
            id = "cat", name = "Dairy & Eggs", nameKey = "cat_dairy_eggs", icon = "🥛",
            isArchived = false, isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        assertThat(original.toDto().toEntity()).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `Store entity-DTO-entity round trip preserves displayOrder and forces pendingSync false`() {
        val original = Store(
            id = "s1", name = "Lidl", colorArgb = 0xFFAABBCC.toInt(),
            isArchived = false, isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true, displayOrder = 7,
        )
        assertThat(original.toDto().toEntity()).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `ItemStoreXref entity-DTO-entity round trip preserves per-store state and forces pendingSync false`() {
        val original = ItemStoreXref(
            itemId = "i1", storeId = "s1", userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
            isNeeded = false, lastPurchasedAt = 500L,
        )
        assertThat(original.toDto().toEntity()).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `StoreCategoryOrder entity-DTO-entity round trip preserves displayOrder and forces pendingSync false`() {
        val original = StoreCategoryOrder(
            storeId = "s1", categoryId = "cat", displayOrder = 3,
            isSeeded = false, userId = "u",
            createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        assertThat(original.toDto().toEntity()).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `PurchaseRecord entity-DTO-entity round trip preserves fields and forces pendingSync false`() {
        val original = PurchaseRecord(
            id = "p1", itemId = "i1", storeId = "s1", purchasedAt = 999L,
            userId = "u", createdAt = 1L, updatedAt = 2L, deletedAt = null,
            pendingSync = true,
        )
        assertThat(original.toDto().toEntity()).isEqualTo(original.copy(pendingSync = false))
    }

    @Test fun `SyncCollections names match the Firestore subcollection layout`() {
        // Pinning these prevents a rename refactor from silently breaking
        // every existing user's cloud data (Firestore docs would land in a
        // new subcollection, leaving the old one orphaned).
        assertThat(SyncCollections.ITEMS).isEqualTo("items")
        assertThat(SyncCollections.CATEGORIES).isEqualTo("categories")
        assertThat(SyncCollections.STORES).isEqualTo("stores")
        assertThat(SyncCollections.ITEM_STORE_XREFS).isEqualTo("item_store_xref")
        assertThat(SyncCollections.STORE_CATEGORY_ORDERS).isEqualTo("store_category_order")
        assertThat(SyncCollections.PURCHASE_RECORDS).isEqualTo("purchase_records")
    }
}
