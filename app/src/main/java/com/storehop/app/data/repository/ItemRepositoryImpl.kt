package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

/**
 * v0.7.0: queries scope by `householdId`. `userId` is still required (it's
 * the creator/audit field on each row, stamped on insert). Cross-cascade
 * DAOs that have already migrated (xrefDao) take `householdId`; the
 * remaining ones (purchaseRecordDao, scoDao) still take `userId: String`
 * named parameters and receive the item's `userId` (which equals
 * householdId in single-member households; revisited per-DAO when those
 * flip to household-scoped filters).
 */
class ItemRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val itemDao: ItemDao,
    private val xrefDao: ItemStoreXrefDao,
    private val purchaseRecordDao: PurchaseRecordDao,
    private val scoDao: StoreCategoryOrderDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
) : ItemRepository {

    override fun observeAll(): Flow<List<ItemWithCategoryAndStores>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(emptyList()) else itemDao.observeAll(hid)
        }

    override fun observeById(id: String): Flow<ItemWithCategoryAndStores?> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(null) else itemDao.observeById(hid, id)
        }

    override suspend fun addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        isNeeded: Boolean,
        brand: String?,
        imageUrl: String?,
        isStaple: Boolean,
        isPriority: Boolean,
    ): String = db.withTransaction {
        val userId = requireSignedIn()
        val householdId = requireHousehold()
        val now = clock.millis()
        val id = ids.newId()
        itemDao.upsert(
            Item(
                id = id,
                name = name.trim(),
                categoryId = categoryId,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
                isNeeded = isNeeded,
                lastPurchasedAt = null,
                userId = userId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                brand = brand?.trim()?.takeIf { it.isNotEmpty() },
                imageUrl = imageUrl,
                isStaple = isStaple,
                isPriority = isPriority,
                householdId = householdId,
            ),
        )
        // Junction inherits both ids from the parent we just wrote — ownership
        // invariant. `userId` is creator/audit; `householdId` is access scope.
        xrefDao.setStoresForItem(id, storeIds, householdId, userId, now)
        ensureSCOForCategoryAtStores(categoryId, storeIds, householdId, userId, now)
        id
    }

    override suspend fun updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        brand: String?,
        imageUrl: String?,
        isStaple: Boolean,
        isPriority: Boolean,
    ) = db.withTransaction {
        val householdId = requireHousehold()
        val now = clock.millis()
        // Preserve isNeeded / lastPurchasedAt / createdAt from the current row.
        val current = itemDao.observeById(householdId, id).first()?.item
            ?: return@withTransaction
        itemDao.upsert(
            current.copy(
                name = name.trim(),
                categoryId = categoryId,
                quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                brand = brand?.trim()?.takeIf { it.isNotEmpty() },
                imageUrl = imageUrl,
                isStaple = isStaple,
                isPriority = isPriority,
                updatedAt = now,
                pendingSync = true,
            ),
        )
        // Pass the parent's householdId + userId, NOT session.currentUserId() —
        // the parent is the source of truth for ownership; using the live
        // session would let a mid-call sign-in/out swap break the cross-table
        // invariant.
        xrefDao.setStoresForItem(id, storeIds, current.householdId, current.userId, now)
        ensureSCOForCategoryAtStores(categoryId, storeIds, current.householdId, current.userId, now)
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        val householdId = requireHousehold()
        // Load the parent first so we (a) verify the caller's household owns
        // the item before any write, and (b) get the userId we need to
        // cascade-tombstone the junction rows and purchase records under
        // the same ownership invariant.
        val current = itemDao.observeById(householdId, id).first()?.item
            ?: return@withTransaction
        val now = clock.millis()
        itemDao.softDelete(householdId, id, now)
        xrefDao.softDeleteForItem(current.householdId, id, now)
        purchaseRecordDao.softDeleteForItem(current.userId, id, now)
    }

    override suspend fun undoSoftDelete(id: String) = db.withTransaction {
        val householdId = requireHousehold()
        val item = itemDao.findAnyById(householdId, id) ?: return@withTransaction
        val deletedAt = item.deletedAt ?: return@withTransaction
        val now = clock.millis()
        itemDao.restoreFromTombstone(householdId, id, now)
        xrefDao.restoreCascadeForItem(item.householdId, id, deletedAt, now)
        purchaseRecordDao.restoreCascadeForItem(item.userId, id, deletedAt, now)
    }

    /**
     * Mark this item purchased. Cascades isNeeded=0 + lastPurchasedAt=now to
     * every store the item is currently tagged to so a single shopping trip
     * satisfies the need everywhere — Mike-reported in v0.5: "purchased it at
     * one of the stores, but it still shows up in the other 2." Writes
     * exactly one PurchaseRecord (the store the user actually bought it at).
     *
     * Verifies the item belongs to the live household before any write so a
     * mid-call session swap can't corrupt cross-table ownership invariants.
     * Returns the snapshot timestamp so [undoPurchase] can do a precision
     * rollback by matching `lastPurchasedAt`; returns null when the lookup
     * fails (no item, or wrong household).
     */
    override suspend fun markPurchasedAtStore(itemId: String, storeId: String): Long? =
        db.withTransaction {
            val userId = requireSignedIn()
            val householdId = requireHousehold()
            val now = clock.millis()
            val current = itemDao.observeById(householdId, itemId).first()?.item
                ?: return@withTransaction null

            xrefDao.markPurchasedAcrossAllStores(current.householdId, itemId, now)
            // PurchaseRecord's userId is the *purchaser* (the user who clicked
            // the checkbox), not the item's creator. Under multi-user this
            // matters: stats filter by purchaser per the v0.7.0 design ("what
            // I bought" not "what we bought").
            purchaseRecordDao.insert(record(itemId, storeId = storeId, userId, householdId, now))
            now
        }

    override suspend fun undoPurchase(itemId: String, snapshotTime: Long) = db.withTransaction {
        val userId = requireSignedIn()
        val householdId = requireHousehold()
        val current = itemDao.observeById(householdId, itemId).first()?.item
            ?: return@withTransaction
        val now = clock.millis()
        xrefDao.restorePurchaseAcrossAllStores(current.householdId, itemId, snapshotTime, now)
        // Scope undo to the purchaser's own records (their userId), matching
        // the insert side. Different users undoing their own purchases stays
        // isolated even though they share the household.
        purchaseRecordDao.softDeleteForItemAtTime(userId, itemId, snapshotTime, now)
    }

    override suspend fun markNeededAtStore(itemId: String, storeId: String) = db.withTransaction {
        val householdId = requireHousehold()
        val current = itemDao.observeById(householdId, itemId).first()?.item
            ?: return@withTransaction
        xrefDao.markNeededAtStore(current.householdId, itemId, storeId, clock.millis())
    }

    override suspend fun tagItemToStore(itemId: String, storeId: String) = db.withTransaction {
        val householdId = requireHousehold()
        val current = itemDao.observeById(householdId, itemId).first()?.item
            ?: return@withTransaction
        val ownerUserId = current.userId
        val now = clock.millis()
        val aliveXref = xrefDao.findForItem(itemId).firstOrNull { it.storeId == storeId }
        if (aliveXref != null) {
            // Live xref already exists -- just ensure isNeeded=true. Idempotent;
            // no-op when the row was already needed.
            xrefDao.markNeededAtStore(current.householdId, itemId, storeId, now)
        } else {
            // Either no xref exists or only a tombstoned one. Upsert by primary
            // key (itemId, storeId) replaces a tombstone or inserts fresh; either
            // way the result is a live row with isNeeded=true.
            xrefDao.upsert(
                ItemStoreXref(
                    itemId = itemId,
                    storeId = storeId,
                    userId = ownerUserId,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    isNeeded = true,
                    householdId = current.householdId,
                ),
            )
        }
        // Mirror addItem's behavior: ensure the SCO row exists so the category
        // can be aisle-ordered at this store.
        ensureSCOForCategoryAtStores(current.categoryId, setOf(storeId), current.householdId, ownerUserId, now)
    }

    override suspend fun addItemFromQuickAdd(name: String, storeId: String): String {
        val householdId = requireHousehold()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "name must be non-empty" }
        val existing = itemDao.findByName(householdId, trimmed)
        return if (existing != null) {
            tagItemToStore(existing.id, storeId)
            existing.id
        } else {
            addItem(name = trimmed, categoryId = null, storeIds = setOf(storeId))
        }
    }

    /**
     * After a save, make sure each store the item is tagged at has an SCO row
     * for the item's category. Without this, custom user-added categories
     * never become aisle-orderable: Edit-aisle filters categories by SCO row
     * presence, so the user can shop the items but can't reposition the
     * category in the aisle order. Idempotent — calling on an existing live
     * row is a no-op. Items without a category and items tagged at zero
     * stores skip out trivially.
     */
    private suspend fun ensureSCOForCategoryAtStores(
        categoryId: String?,
        storeIds: Set<String>,
        householdId: String,
        userId: String,
        now: Long,
    ) {
        if (categoryId == null) return
        for (storeId in storeIds) {
            scoDao.appendIfMissing(storeId, categoryId, householdId, userId, now)
        }
    }

    override fun observeNeededItemIds(): Flow<Set<String>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(emptySet())
            else xrefDao.observeNeededItemIds(hid).map { it.toSet() }
        }

    override suspend fun markNeededAcrossAllStores(itemId: String) = db.withTransaction {
        val householdId = requireHousehold()
        val current = itemDao.observeById(householdId, itemId).first()?.item
            ?: return@withTransaction
        xrefDao.markNeededAcrossAllStores(current.householdId, itemId, clock.millis())
    }

    override suspend fun markPurchasedAcrossAllStores(itemId: String) = db.withTransaction {
        val householdId = requireHousehold()
        val current = itemDao.observeById(householdId, itemId).first()?.item
            ?: return@withTransaction
        // Pure list-state action -- no PurchaseRecord. The cascade clears
        // the item from every tagged store; the user is on the master Items
        // list, not at a store, so attributing the purchase to any one
        // store would be wrong.
        xrefDao.markPurchasedAcrossAllStores(current.householdId, itemId, clock.millis())
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")

    private fun requireHousehold(): String =
        householdSession.currentHouseholdId() ?: throw IllegalStateException("No active household")

    private fun record(itemId: String, storeId: String?, userId: String, householdId: String, now: Long) =
        PurchaseRecord(
            id = ids.newId(),
            itemId = itemId,
            storeId = storeId,
            purchasedAt = now,
            userId = userId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            householdId = householdId,
        )
}
