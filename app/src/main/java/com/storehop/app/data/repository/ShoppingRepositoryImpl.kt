package com.storehop.app.data.repository

import com.storehop.app.data.dao.ShoppingDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ShoppingRepositoryImpl @Inject constructor(
    private val dao: ShoppingDao,
    private val storeDao: StoreDao,
    private val session: UserSessionProvider,
) : ShoppingRepository {

    override fun shoppingListForStore(storeId: String): Flow<List<ShoppingRow>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else dao.shoppingListForStore(uid, storeId)
        }

    override fun observeStorePickerRows(): Flow<List<StorePickerRow>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) {
                flowOf(emptyList())
            } else {
                combine(
                    storeDao.observeAll(uid, includeArchived = false),
                    dao.observeNeededByStore(uid),
                ) { stores, needed ->
                    val byStore = needed.groupBy { it.storeId }
                    stores.map { store ->
                        val items = byStore[store.id].orEmpty()
                        StorePickerRow(
                            store = store,
                            neededCount = items.size,
                            criticalItemNames = items
                                .filter { it.isPriority }
                                .map { it.itemName },
                        )
                    }
                }
            }
        }
}
