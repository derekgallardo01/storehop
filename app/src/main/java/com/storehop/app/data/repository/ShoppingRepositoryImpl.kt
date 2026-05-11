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
                        // Picker semantics (v0.6.9, per Mike): a row counts as
                        // "still on the list" only when isNeeded=1. The in-
                        // store view deliberately keeps struck-through staples
                        // visible for the user's convenience, but the picker
                        // badge + banner are strictly the needed-count. When
                        // the user marks a staple purchased it disappears
                        // from the picker even though it's still in the in-
                        // store list.
                        val (needed, pickedUp) = rows.partition { it.isNeeded }
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
