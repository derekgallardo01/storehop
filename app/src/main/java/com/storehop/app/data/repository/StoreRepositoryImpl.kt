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
        dao.observeAll(session.currentUserId(), includeArchived)

    override fun observeById(id: String): Flow<Store?> =
        dao.observeById(session.currentUserId(), id)

    override suspend fun addStore(name: String, colorArgb: Int?): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Store name cannot be empty" }
        val userId = session.currentUserId()
        val now = clock.millis()

        // Three cases, all serialized inside the transaction:
        //   1. No row at all          -> insert a new row with a fresh UUID.
        //   2. Live row with same name -> reject as duplicate.
        //   3. Tombstoned row with same name -> RESURRECT (clear deletedAt, refresh
        //      colorArgb + updatedAt, return the original id). Re-using the id is
        //      the right model for sync: other devices see the row come back to
        //      life rather than appearing as a brand-new row that conflicts with
        //      their own tombstone.
        //
        // Without this, the schema UNIQUE(userId, name) index blocks the insert
        // forever once a name is tombstoned -- @Upsert silently no-ops and
        // addStore would return a UUID for a row that was never written.
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
                // Resurrect.
                dao.upsert(
                    existing.copy(
                        colorArgb = colorArgb,
                        isArchived = false,
                        deletedAt = null,
                        updatedAt = now,
                    ),
                )
                existing.id
            }
        }
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

    override suspend fun softDelete(id: String) = db.withTransaction {
        // Cascade so a deleted store doesn't leave orphan xrefs (which would still
        // surface in @Relation joins as a tombstoned store) or orphan SCO rows
        // (which would still appear in observeForStore).
        val userId = session.currentUserId()
        val now = clock.millis()
        dao.softDelete(userId, id, now)
        xrefDao.softDeleteForStore(userId, id, now)
        scoDao.softDeleteForStore(userId, id, now)
    }
}
