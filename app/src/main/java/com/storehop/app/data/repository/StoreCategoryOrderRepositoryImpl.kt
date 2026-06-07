package com.storehop.app.data.repository

import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

class StoreCategoryOrderRepositoryImpl @Inject constructor(
    private val dao: StoreCategoryOrderDao,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : StoreCategoryOrderRepository {

    override fun observeForStore(storeId: String): Flow<List<StoreCategoryOrder>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observeForStore(storeId)
        }

    override suspend fun reorderCategoriesForStore(
        storeId: String,
        orderedCategoryIds: List<String>,
    ) {
        val userId = requireSignedIn()
        val now = clock.millis()
        // Build SCO rows with displayOrder = index. The DAO's replaceAllForStore
        // copies updatedAt/deletedAt/pendingSync onto each row inside its
        // transaction, so we don't need to stamp them here -- but we DO need
        // to set createdAt to `now` for any newly-inserted row. Existing rows
        // get their createdAt preserved by @Upsert (only differing columns are
        // updated when the PK matches), so the value we set here is only used
        // for rows that are brand new.
        val rows = orderedCategoryIds.mapIndexed { index, categoryId ->
            StoreCategoryOrder(
                storeId = storeId,
                categoryId = categoryId,
                displayOrder = index,
                isSeeded = false,
                userId = userId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }
        dao.replaceAllForStore(storeId, rows, now)
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")
}
