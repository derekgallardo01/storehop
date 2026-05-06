package com.storehop.app.data.repository

import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.entity.PurchaseRecord
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject

class PurchaseHistoryRepositoryImpl @Inject constructor(
    private val dao: PurchaseRecordDao,
    private val clock: Clock,
) : PurchaseHistoryRepository {

    override fun observeForItem(itemId: String): Flow<List<PurchaseRecord>> =
        dao.observeForItem(itemId)

    override suspend fun softDelete(id: String) {
        dao.softDelete(id, clock.millis())
    }
}
