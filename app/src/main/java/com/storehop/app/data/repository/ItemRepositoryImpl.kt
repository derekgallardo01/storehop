package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

class ItemRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val itemDao: ItemDao,
    private val xrefDao: ItemStoreXrefDao,
    private val purchaseRecordDao: PurchaseRecordDao,
    private val scoDao: StoreCategoryOrderDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : ItemRepository {

    override fun observeAll(): Flow<List<ItemWithCategoryAndStores>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else itemDao.observeAll(uid)
        }

    override fun observeById(id: String): Flow<ItemWithCategoryAndStores?> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(null) else itemDao.observeById(uid, id)
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
            ),
        )
        // Junction inherits userId from the parent we just wrote — ownership invariant.
        xrefDao.setStoresForItem(id, storeIds, userId, now)
        ensureSCOForCategoryAtStores(categoryId, storeIds, userId, now)
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
        val userId = requireSignedIn()
        val now = clock.millis()
        // Preserve isNeeded / lastPurchasedAt / createdAt from the current row.
        val current = itemDao.observeById(userId, id).first()?.item
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
        // Pass the parent's userId, NOT session.currentUserId() — the parent is the
        // source of truth for ownership; using the live session would let a mid-call
        // sign-in/out swap break the cross-table invariant.
        xrefDao.setStoresForItem(id, storeIds, current.userId, now)
        ensureSCOForCategoryAtStores(categoryId, storeIds, current.userId, now)
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        val userId = requireSignedIn()
        // Load the parent first so we (a) verify the caller owns the item before any
        // write, and (b) get the userId we need to cascade-tombstone the junction
        // rows and purchase records under the same ownership invariant.
        val current = itemDao.observeById(userId, id).first()?.item
            ?: return@withTransaction
        val now = clock.millis()
        itemDao.softDelete(current.userId, id, now)
        xrefDao.softDeleteForItem(current.userId, id, now)
        purchaseRecordDao.softDeleteForItem(current.userId, id, now)
    }

    override suspend fun undoSoftDelete(id: String) = db.withTransaction {
        val userId = requireSignedIn()
        val item = itemDao.findAnyById(userId, id) ?: return@withTransaction
        val deletedAt = item.deletedAt ?: return@withTransaction
        val now = clock.millis()
        itemDao.restoreFromTombstone(item.userId, id, now)
        xrefDao.restoreCascadeForItem(item.userId, id, deletedAt, now)
        purchaseRecordDao.restoreCascadeForItem(item.userId, id, deletedAt, now)
    }

    /**
     * Mark this item purchased. Cascades isNeeded=0 + lastPurchasedAt=now to
     * every store the item is currently tagged to so a single shopping trip
     * satisfies the need everywhere — Mike-reported in v0.5: "purchased it at
     * one of the stores, but it still shows up in the other 2." Writes
     * exactly one PurchaseRecord (the store the user actually bought it at).
     *
     * Verifies the item belongs to the live user before any write so a
     * mid-call session swap can't corrupt cross-table ownership invariants.
     * Returns the snapshot timestamp so [undoPurchase] can do a precision
     * rollback by matching `lastPurchasedAt`; returns null when the lookup
     * fails (no item, or wrong owner).
     */
    override suspend fun markPurchasedAtStore(itemId: String, storeId: String): Long? =
        db.withTransaction {
            val userId = requireSignedIn()
            val now = clock.millis()
            val current = itemDao.observeById(userId, itemId).first()?.item
                ?: return@withTransaction null
            val ownerId = current.userId

            xrefDao.markPurchasedAcrossAllStores(ownerId, itemId, now)
            purchaseRecordDao.insert(record(itemId, storeId = storeId, ownerId, now))
            now
        }

    override suspend fun undoPurchase(itemId: String, snapshotTime: Long) = db.withTransaction {
        val userId = requireSignedIn()
        val current = itemDao.observeById(userId, itemId).first()?.item
            ?: return@withTransaction
        val ownerId = current.userId
        val now = clock.millis()
        xrefDao.restorePurchaseAcrossAllStores(ownerId, itemId, snapshotTime, now)
        purchaseRecordDao.softDeleteForItemAtTime(ownerId, itemId, snapshotTime, now)
    }

    override suspend fun markNeededAtStore(itemId: String, storeId: String) = db.withTransaction {
        val userId = requireSignedIn()
        val current = itemDao.observeById(userId, itemId).first()?.item
            ?: return@withTransaction
        xrefDao.markNeededAtStore(current.userId, itemId, storeId, clock.millis())
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
        userId: String,
        now: Long,
    ) {
        if (categoryId == null) return
        for (storeId in storeIds) {
            scoDao.appendIfMissing(storeId, categoryId, userId, now)
        }
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")

    private fun record(itemId: String, storeId: String?, userId: String, now: Long) =
        PurchaseRecord(
            id = ids.newId(),
            itemId = itemId,
            storeId = storeId,
            purchasedAt = now,
            userId = userId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
}
