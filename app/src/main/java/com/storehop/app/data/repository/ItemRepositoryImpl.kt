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
        itemDao.observeAll(session.currentUserId())

    override fun observeNeeded(): Flow<List<Item>> =
        itemDao.observeNeeded(session.currentUserId())

    override fun observeById(id: String): Flow<ItemWithCategoryAndStores?> =
        itemDao.observeById(session.currentUserId(), id)

    override suspend fun addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        isNeeded: Boolean,
    ): String = db.withTransaction {
        val now = clock.millis()
        val userId = session.currentUserId()
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
    ) = db.withTransaction {
        val now = clock.millis()
        // Preserve isNeeded / lastPurchasedAt / createdAt from the current row.
        val current = itemDao.observeById(session.currentUserId(), id).first()?.item
            ?: return@withTransaction
        itemDao.upsert(
            current.copy(
                name = name.trim(),
                categoryId = categoryId,
                quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = now,
            ),
        )
        // Pass the parent's userId, NOT session.currentUserId() — the parent is the
        // source of truth for ownership; using the live session would let a mid-call
        // sign-in/out swap break the cross-table invariant.
        xrefDao.setStoresForItem(id, storeIds, current.userId, now)
    }

    override suspend fun softDelete(id: String) {
        itemDao.softDelete(id, clock.millis())
    }

    override suspend fun markPurchased(id: String) = db.withTransaction {
        val now = clock.millis()
        // Load the parent row first so PurchaseRecord.userId is sourced from the item,
        // not from a possibly-different live session.
        val current = itemDao.observeById(session.currentUserId(), id).first()?.item
            ?: return@withTransaction
        val ownerId = current.userId

        itemDao.markPurchased(id, now)

        val xrefs = xrefDao.findForItem(id)
        if (xrefs.isEmpty()) {
            purchaseRecordDao.insert(record(id, storeId = null, ownerId, now))
        } else {
            xrefs.forEach { x ->
                purchaseRecordDao.insert(record(id, storeId = x.storeId, ownerId, now))
            }
        }
    }

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
