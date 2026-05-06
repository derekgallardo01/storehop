package com.storehop.app.data.repository

import com.storehop.app.data.db.relations.ShoppingRow
import kotlinx.coroutines.flow.Flow

interface ShoppingRepository {
    fun shoppingListForStore(storeId: String): Flow<List<ShoppingRow>>
}
