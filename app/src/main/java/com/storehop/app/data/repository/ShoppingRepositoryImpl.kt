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

    override fun shoppingListForStore(
        storeId: String,
        sessionStartMs: Long,
    ): Flow<List<ShoppingRow>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else dao.shoppingListForStore(uid, storeId, sessionStartMs)
        }

    override fun observeStorePickerRows(
        sessionStartMs: Long,
    ): Flow<List<StorePickerRow>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) {
                flowOf(emptyList())
            } else {
                combine(
                    storeDao.observeAll(uid, includeArchived = false),
                    dao.observeStorePickerItems(uid, sessionStartMs),
                ) { stores, items ->
                    val byStore = items.groupBy { it.storeId }
                    stores.map { store ->
                        val rows = byStore[store.id].orEmpty()
                        // A row is "still on the list" for picker purposes if
                        // isNeeded=1, OR if it's a staple that hasn't been
                        // bought this session yet. A priority staple that the
                        // user checked off last week still counts as critical
                        // until they buy it this trip -- otherwise the picker
                        // chip silently drops a real need (the in-store view
                        // still surfaces the row because of the staple OR
                        // clause in shoppingListForStore). pickedUpThisSession
                        // gets the staples-bought-this-session bucket plus
                        // any non-staple just checked off.
                        val (needed, pickedUp) = rows.partition {
                            it.isNeeded || (it.isStaple && !it.purchasedThisSession)
                        }
                        StorePickerRow(
                            store = store,
                            neededCount = needed.size,
                            pickedUpInSessionCount = pickedUp.size,
                            criticalItemNames = needed
                                .filter { it.isPriority }
                                .map { it.itemName },
                        )
                    }
                }
            }
        }
}
