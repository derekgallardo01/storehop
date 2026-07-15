package com.storehop.app.data.repository

import com.storehop.app.data.dao.ShoppingDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.util.HouseholdSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ShoppingRepositoryImpl @Inject constructor(
    private val dao: ShoppingDao,
    private val storeDao: StoreDao,
    private val householdSession: HouseholdSessionProvider,
) : ShoppingRepository {

    override fun shoppingListForStore(
        storeId: String,
        sessionStartMs: Long,
    ): Flow<List<ShoppingRow>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(emptyList())
            else dao.shoppingListForStore(hid, storeId, sessionStartMs)
        }

    override fun observeStorePickerRows(
        sessionStartMs: Long,
    ): Flow<List<StorePickerRow>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) {
                flowOf(emptyList())
            } else {
                combine(
                    storeDao.observeAll(hid, includeArchived = false),
                    dao.observeStorePickerItems(hid, sessionStartMs),
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
                            buyTodayItemNames = needed
                                .filter { it.isBuyToday }
                                .map { it.itemName },
                        )
                    }
                }
            }
        }
}
