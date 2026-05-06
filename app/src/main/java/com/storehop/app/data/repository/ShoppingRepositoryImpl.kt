package com.storehop.app.data.repository

import com.storehop.app.data.dao.ShoppingDao
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ShoppingRepositoryImpl @Inject constructor(
    private val dao: ShoppingDao,
    private val session: UserSessionProvider,
) : ShoppingRepository {

    override fun shoppingListForStore(storeId: String): Flow<List<ShoppingRow>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else dao.shoppingListForStore(uid, storeId)
        }
}
