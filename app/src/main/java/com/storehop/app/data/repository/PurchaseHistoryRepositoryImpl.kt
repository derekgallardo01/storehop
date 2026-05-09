package com.storehop.app.data.repository

import com.storehop.app.data.dao.CategoryPurchaseCount
import com.storehop.app.data.dao.DayCount
import com.storehop.app.data.dao.DayOfWeekCount
import com.storehop.app.data.dao.ItemPurchaseCount
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StorePurchaseCount
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

    override fun observeTotalCount(): Flow<Int> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(0) else dao.observeTotalCount(uid)
        }

    override fun observeCountSince(sinceMillis: Long): Flow<Int> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(0) else dao.observeCountSince(uid, sinceMillis)
        }

    override fun observePurchasesPerDay(sinceMillis: Long): Flow<List<DayCount>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observePurchasesPerDay(uid, sinceMillis)
        }

    override fun observePurchasesByDayOfWeek(): Flow<List<DayOfWeekCount>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observePurchasesByDayOfWeek(uid)
        }

    override fun observeTopItems(limit: Int): Flow<List<ItemPurchaseCount>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observeTopItems(uid, limit)
        }

    override fun observePurchasesByStore(): Flow<List<StorePurchaseCount>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observePurchasesByStore(uid)
        }

    override fun observePurchasesByCategory(): Flow<List<CategoryPurchaseCount>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observePurchasesByCategory(uid)
        }
}
