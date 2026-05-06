package com.storehop.app.data.repository

import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
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
        itemDao.observeById(id)

    override suspend fun addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        isNeeded: Boolean,
    ): String {
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
        // Junction inherits userId from parent — ownership invariant.
        xrefDao.setStoresForItem(id, storeIds, userId, now)
        return id
    }

    override suspend fun updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
    ) {
        val now = clock.millis()
        val userId = session.currentUserId()
        // Preserve isNeeded / lastPurchasedAt / createdAt from the current row.
        val current = itemDao.observeById(id).first()?.item ?: return
        itemDao.upsert(
            current.copy(
                name = name.trim(),
                categoryId = categoryId,
                quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = now,
            ),
        )
        xrefDao.setStoresForItem(id, storeIds, userId, now)
    }

    override suspend fun softDelete(id: String) {
        itemDao.softDelete(id, clock.millis())
    }

    override suspend fun markPurchased(id: String) {
        val now = clock.millis()
        val userId = session.currentUserId()
        itemDao.markPurchased(id, now)
        // Append a purchase record per store the item was tagged to.
        // storeId left null when called outside a store context — caller can extend later.
        val xrefs = xrefDao.findForItem(id)
        if (xrefs.isEmpty()) {
            purchaseRecordDao.insert(
                PurchaseRecord(
                    id = ids.newId(),
                    itemId = id,
                    storeId = null,
                    purchasedAt = now,
                    userId = userId,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        } else {
            xrefs.forEach { x ->
                purchaseRecordDao.insert(
                    PurchaseRecord(
                        id = ids.newId(),
                        itemId = id,
                        storeId = x.storeId,
                        purchasedAt = now,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null,
                    ),
                )
            }
        }
    }
}
