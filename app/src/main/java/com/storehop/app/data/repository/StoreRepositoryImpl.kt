package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

class StoreRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val dao: StoreDao,
    private val xrefDao: ItemStoreXrefDao,
    private val scoDao: StoreCategoryOrderDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : StoreRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Store>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observeAll(uid, includeArchived)
        }

    override fun observeById(id: String): Flow<Store?> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(null) else dao.observeById(uid, id)
        }

    override suspend fun addStore(name: String, colorArgb: Int?): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Store name cannot be empty" }
        val userId = requireSignedIn()
        val now = clock.millis()

        // Three cases, all serialized inside the transaction:
        //   1. No row at all          -> insert a new row with a fresh UUID.
        //   2. Live row with same name -> reject as duplicate.
        //   3. Tombstoned row with same name -> RESURRECT (clear deletedAt, refresh
        //      colorArgb + updatedAt, return the original id). Re-using the id is
        //      the right model for sync: other devices see the row come back to
        //      life rather than appearing as a brand-new row that conflicts with
        //      their own tombstone.
        val existing = dao.findAnyByName(userId, trimmed)
        when {
            existing == null -> {
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
            existing.deletedAt == null -> {
                throw IllegalArgumentException("A store named \"$trimmed\" already exists")
            }
            else -> {
                dao.upsert(
                    existing.copy(
                        colorArgb = colorArgb,
                        isArchived = false,
                        deletedAt = null,
                        updatedAt = now,
                        pendingSync = true,
                    ),
                )
                existing.id
            }
        }
    }

    override suspend fun rename(id: String, name: String) {
        val userId = requireSignedIn()
        val current = dao.findById(userId, id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis(), pendingSync = true))
    }

    override suspend fun setColor(id: String, colorArgb: Int?) {
        val userId = requireSignedIn()
        val current = dao.findById(userId, id) ?: return
        dao.upsert(current.copy(colorArgb = colorArgb, updatedAt = clock.millis(), pendingSync = true))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(requireSignedIn(), id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        // Cascade so a deleted store doesn't leave orphan xrefs (which would still
        // surface in @Relation joins as a tombstoned store) or orphan SCO rows
        // (which would still appear in observeForStore).
        val userId = requireSignedIn()
        val now = clock.millis()
        dao.softDelete(userId, id, now)
        xrefDao.softDeleteForStore(userId, id, now)
        scoDao.softDeleteForStore(userId, id, now)
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")
}
