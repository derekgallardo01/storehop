package com.storehop.app.data.dao

import androidx.room.withTransaction
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-transaction batch write for the pull side. Either every entity from
 * the cloud lands or none of them do -- which is the contract that lets us
 * skip partial-pull handling everywhere upstream. The atomicity also matters
 * for foreign-key consistency: items reference categoryId, xrefs reference
 * itemId/storeId, etc. -- if items landed but stores didn't, the DB would
 * briefly contain dangling references.
 *
 * Caller is responsible for converting DTOs to entities (with
 * `pendingSync = false`) before invoking this method -- see
 * [com.storehop.app.sync.dto.toEntity] mappers.
 *
 * Not annotated `@Dao` because multi-DAO transactions can't live on a single
 * DAO class. Uses room-ktx's `withTransaction` to span the database.
 */
@Singleton
class PullWriteDao @Inject constructor(
    private val db: StorehopDatabase,
) {
    suspend fun replaceAllForUid(
        items: List<Item>,
        categories: List<Category>,
        stores: List<Store>,
        xrefs: List<ItemStoreXref>,
        scoOrders: List<StoreCategoryOrder>,
        purchaseRecords: List<PurchaseRecord>,
    ) = db.withTransaction {
        // Order doesn't matter for correctness inside a transaction (FKs are
        // checked at commit time), but it keeps the intent visible: parents
        // before children.
        db.categoryDao().upsertFromCloud(categories)
        db.storeDao().upsertFromCloud(stores)
        db.itemDao().upsertFromCloud(items)
        db.itemStoreXrefDao().upsertFromCloud(xrefs)
        db.storeCategoryOrderDao().upsertFromCloud(scoOrders)
        db.purchaseRecordDao().upsertFromCloud(purchaseRecords)
    }

    /**
     * v0.7.0 Phase 3: hard-delete every household-scoped row for
     * [householdId]. Used when the user accepts another household's invite
     * (their personal household's data is dropped and the new shared
     * household is then pulled in) or when they leave a shared household
     * (the shared rows are dropped and a fresh personal household is
     * created). Single transaction so the wipe is all-or-nothing.
     *
     * Children-before-parents because of FK constraints: xrefs and SCOs
     * reference stores/categories/items, so they must clear first.
     * `household_members` rows are intentionally NOT touched here — the
     * caller manages those.
     */
    suspend fun wipeAllForHousehold(householdId: String) = db.withTransaction {
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("DELETE FROM item_store_xref WHERE householdId = ?", arrayOf(householdId))
        sqlDb.execSQL("DELETE FROM store_category_order WHERE householdId = ?", arrayOf(householdId))
        sqlDb.execSQL("DELETE FROM purchase_records WHERE householdId = ?", arrayOf(householdId))
        sqlDb.execSQL("DELETE FROM items WHERE householdId = ?", arrayOf(householdId))
        sqlDb.execSQL("DELETE FROM categories WHERE householdId = ?", arrayOf(householdId))
        sqlDb.execSQL("DELETE FROM stores WHERE householdId = ?", arrayOf(householdId))
    }
}
