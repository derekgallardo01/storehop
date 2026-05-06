package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject

class StoreRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val dao: StoreDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : StoreRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Store>> =
        dao.observeAll(session.currentUserId(), includeArchived)

    override fun observeById(id: String): Flow<Store?> =
        dao.observeById(session.currentUserId(), id)

    override suspend fun addStore(name: String, colorArgb: Int?): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Store name cannot be empty" }
        val userId = session.currentUserId()
        // The (userId, name) unique index is enforced by the schema, but Room's
        // @Upsert silently no-ops on a non-PK conflict (insert IGNOREs, then update
        // by PK is a no-op for a fresh UUID). Detect the collision here so the
        // caller gets a clear error instead of a UUID for a row that was never written.
        // Wrapping in withTransaction closes the TOCTOU race between findByName and
        // upsert -- two concurrent addStore calls with the same name now serialize.
        require(dao.findByName(userId, trimmed) == null) {
            "A store named \"$trimmed\" already exists"
        }
        val now = clock.millis()
        val id = ids.newId()
        dao.upsert(
            Store(
                id = id,
                name = trimmed,
                colorArgb = colorArgb,
                isArchived = false,
                isSeeded = false,
                userId = userId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        id
    }

    override suspend fun rename(id: String, name: String) {
        val current = dao.findById(session.currentUserId(), id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis()))
    }

    override suspend fun setColor(id: String, colorArgb: Int?) {
        val current = dao.findById(session.currentUserId(), id) ?: return
        dao.upsert(current.copy(colorArgb = colorArgb, updatedAt = clock.millis()))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(session.currentUserId(), id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) {
        dao.softDelete(session.currentUserId(), id, clock.millis())
    }
}
