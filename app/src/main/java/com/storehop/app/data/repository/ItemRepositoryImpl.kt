package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
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

    /**
     * Mark this (item, store) row as purchased. Per-store: only `isx(item,
     * store).isNeeded` flips -- other stores the item is tagged to are
     * untouched. Writes exactly one [PurchaseRecord] for the pair (vs. the
     * old API which wrote one per tagged store and inflated history counts).
     *
     * Verifies the item belongs to the live user before any write so a
     * mid-call session swap can't corrupt cross-table ownership invariants.
     */
    override suspend fun markPurchasedAtStore(itemId: String, storeId: String) = db.withTransaction {
        val userId = requireSignedIn()
        val now = clock.millis()
        val current = itemDao.observeById(userId, itemId).first()?.item
            ?: return@withTransaction
        val ownerId = current.userId

        xrefDao.markPurchasedAtStore(ownerId, itemId, storeId, now)
        purchaseRecordDao.insert(record(itemId, storeId = storeId, ownerId, now))
    }

    override suspend fun markNeededAtStore(itemId: String, storeId: String) = db.withTransaction {
        val userId = requireSignedIn()
        val current = itemDao.observeById(userId, itemId).first()?.item
            ?: return@withTransaction
        xrefDao.markNeededAtStore(current.userId, itemId, storeId, clock.millis())
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
