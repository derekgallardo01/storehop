package com.storehop.app.data.repository

import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

class PurchaseHistoryRepositoryImpl @Inject constructor(
    private val dao: PurchaseRecordDao,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : PurchaseHistoryRepository {

    override fun observeForItem(itemId: String): Flow<List<PurchaseRecord>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observeForItem(uid, itemId)
        }

    override suspend fun softDelete(id: String) {
        val userId = session.currentUserId()
            ?: throw IllegalStateException("Not signed in")
        dao.softDelete(userId, id, clock.millis())
    }
}
