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
    /**
     * Replace local rows with the cloud snapshot, **except** rows where a
     * local edit is pending push (`pendingSync = 1`). Those rows are the
     * user's most-recent intent and must survive the pull — otherwise a
     * pull-before-push race resurrects deletes the user just made.
     *
     * v0.8.0.4: Mike reported a "uncheck Aldi → save → Aldi comes back"
     * bug rooted in this exact race. The previous implementation called
     * `upsertFromCloud` unconditionally, which Room's `@Upsert` resolved
     * by REPLACE-ing the local row (resurrecting `deletedAt`, clearing
     * `pendingSync` via the mapper). Now we snapshot the local
     * pending-PK set per entity and filter the cloud list before
     * upsert.
     *
     * Why the filter is correct under LWW:
     *  - Push is what makes a local edit authoritative cloud-side.
     *    Until then, this device's local DB is the source of truth for
     *    that PK.
     *  - Once push completes, `markPushed()` flips `pendingSync = 0`,
     *    the next pull has no guard for that PK, and cloud is
     *    authoritative again. LWW by `updatedAt` still arbitrates
     *    multi-device conflicts at the cloud level.
     */
    suspend fun replaceAllForUid(
        householdId: String,
        items: List<Item>,
        categories: List<Category>,
        stores: List<Store>,
        xrefs: List<ItemStoreXref>,
        scoOrders: List<StoreCategoryOrder>,
        purchaseRecords: List<PurchaseRecord>,
    ) = db.withTransaction {
        // Snapshot pending-sync primary keys per entity. Any cloud row
        // whose PK is in the corresponding set is dropped from the
        // upsert list.
        val pendingItemIds = db.itemDao().pendingPushIds(householdId).toHashSet()
        val pendingCategoryIds = db.categoryDao().pendingPushIds(householdId).toHashSet()
        val pendingStoreIds = db.storeDao().pendingPushIds(householdId).toHashSet()
        val pendingXrefKeys = db.itemStoreXrefDao().pendingPushKeys(householdId).toHashSet()
        val pendingScoKeys = db.storeCategoryOrderDao().pendingPushKeys(householdId).toHashSet()
        val pendingPurchaseIds = db.purchaseRecordDao().pendingPushIds(householdId).toHashSet()

        // Order doesn't matter for correctness inside a transaction (FKs are
        // checked at commit time), but it keeps the intent visible: parents
        // before children.
        db.categoryDao().upsertFromCloud(
            categories.filterNot { it.id in pendingCategoryIds }
        )
        db.storeDao().upsertFromCloud(
            stores.filterNot { it.id in pendingStoreIds }
        )
        db.itemDao().upsertFromCloud(
            items.filterNot { it.id in pendingItemIds }
        )
        db.itemStoreXrefDao().upsertFromCloud(
            xrefs.filterNot { XrefKey(it.itemId, it.storeId) in pendingXrefKeys }
        )
        db.storeCategoryOrderDao().upsertFromCloud(
            scoOrders.filterNot { ScoKey(it.storeId, it.categoryId) in pendingScoKeys }
        )
        db.purchaseRecordDao().upsertFromCloud(
            purchaseRecords.filterNot { it.id in pendingPurchaseIds }
        )
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
