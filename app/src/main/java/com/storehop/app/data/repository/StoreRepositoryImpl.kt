package com.storehop.app.data.repository

import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject

class StoreRepositoryImpl @Inject constructor(
    private val dao: StoreDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : StoreRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Store>> =
        dao.observeAll(session.currentUserId(), includeArchived)

    override fun observeById(id: String): Flow<Store?> = dao.observeById(id)

    override suspend fun addStore(name: String, colorArgb: Int?): String {
        val now = clock.millis()
        val id = ids.newId()
        dao.upsert(
            Store(
                id = id,
                name = name.trim(),
                colorArgb = colorArgb,
                isArchived = false,
                isSeeded = false,
                userId = session.currentUserId(),
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        return id
    }

    override suspend fun rename(id: String, name: String) {
        val current = dao.findById(id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis()))
    }

    override suspend fun setColor(id: String, colorArgb: Int?) {
        val current = dao.findById(id) ?: return
        dao.upsert(current.copy(colorArgb = colorArgb, updatedAt = clock.millis()))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) {
        dao.softDelete(id, clock.millis())
    }
}
