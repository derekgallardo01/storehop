package com.storehop.app.data.repository

import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject

class PurchaseHistoryRepositoryImpl @Inject constructor(
    private val dao: PurchaseRecordDao,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : PurchaseHistoryRepository {

    override fun observeForItem(itemId: String): Flow<List<PurchaseRecord>> =
        dao.observeForItem(session.currentUserId(), itemId)

    override suspend fun softDelete(id: String) {
        dao.softDelete(session.currentUserId(), id, clock.millis())
    }
}
