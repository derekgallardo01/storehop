package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

/**
 * v0.7.0: store queries filter by `householdId`. `userId` is still
 * required (it's the creator/audit field on each row) so we hold both
 * providers. For single-member households they hold the same value;
 * post-Phase 3 they diverge when Amanda creates a row in Mike's
 * household (`userId = amanda`, `householdId = mike`).
 *
 * The cross-cascade xref + SCO DAOs still take `userId: String` named
 * parameters because they haven't been migrated to the household model
 * yet (Phase 1.2 ships per-DAO). We pass `householdId` to them anyway
 * since the value is identical for single-member households -- once
 * those DAOs flip the SQL filter to `householdId`, no caller change is
 * needed here.
 */
class StoreRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val dao: StoreDao,
    private val xrefDao: ItemStoreXrefDao,
    private val scoDao: StoreCategoryOrderDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
) : StoreRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Store>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(emptyList()) else dao.observeAll(hid, includeArchived)
        }

    override fun observeById(id: String): Flow<Store?> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(null) else dao.observeById(hid, id)
        }

    override suspend fun addStore(name: String, colorArgb: Int?, isOneOff: Boolean): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Store name cannot be empty" }
        val userId = requireSignedIn()
        val householdId = requireHousehold()
        val now = clock.millis()

        // Three cases, all serialized inside the transaction:
        //   1. No row at all          -> insert a new row with a fresh UUID.
        //   2. Live row with same name -> reject as duplicate.
        //   3. Tombstoned row with same name -> RESURRECT (clear deletedAt, refresh
        //      colorArgb + updatedAt, return the original id). Re-using the id is
        //      the right model for sync: other devices see the row come back to
        //      life rather than appearing as a brand-new row that conflicts with
        //      their own tombstone.
        val existing = dao.findAnyByName(householdId, trimmed)
        when {
            existing == null -> {
                val id = ids.newId()
                // New stores append to the end of the picker -- the user drags
                // them where they want from there. Reads MAX+1 inside the
                // transaction so concurrent adds get distinct positions.
                val displayOrder = dao.nextDisplayOrder(householdId)
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
                        displayOrder = displayOrder,
                        householdId = householdId,
                        isOneOff = isOneOff,
                    ),
                )
                id
            }
            existing.deletedAt == null -> {
                throw IllegalArgumentException("A store named \"$trimmed\" already exists")
            }
            else -> {
                // Resurrect: keep the original displayOrder (sync semantic --
                // the row coming back to life sits where it was on other
                // devices). If the user wants it elsewhere they can drag.
                // v0.9: the addStore caller's `isOneOff` choice overrides the
                // tombstoned row's stored value -- the user is re-creating the
                // store fresh from their POV, so honor their intent.
                dao.upsert(
                    existing.copy(
                        colorArgb = colorArgb,
                        isArchived = false,
                        deletedAt = null,
                        updatedAt = now,
                        pendingSync = true,
                        isOneOff = isOneOff,
                    ),
                )
                existing.id
            }
        }
    }

    override suspend fun rename(id: String, name: String) = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Store name cannot be empty" }
        val householdId = requireHousehold()
        val current = dao.findById(householdId, id) ?: return@withTransaction
        // Alive-only collision check. Schema v6 dropped the UNIQUE constraint
        // on (userId, name) so tombstoned rows don't block name reuse.
        // Same-id case changes ("Aldi" -> "ALDI") are allowed because the
        // findByName lookup returns the same row. Mirrors
        // CategoryRepositoryImpl.rename.
        val collision = dao.findByName(householdId, trimmed)
        if (collision != null && collision.id != current.id) {
            throw IllegalArgumentException("A store named \"$trimmed\" already exists")
        }
        dao.upsert(current.copy(name = trimmed, updatedAt = clock.millis(), pendingSync = true))
    }

    override suspend fun setColor(id: String, colorArgb: Int?) {
        val householdId = requireHousehold()
        val current = dao.findById(householdId, id) ?: return
        dao.upsert(current.copy(colorArgb = colorArgb, updatedAt = clock.millis(), pendingSync = true))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(requireHousehold(), id, archived, clock.millis())
    }

    override suspend fun setOneOff(id: String, isOneOff: Boolean) {
        val householdId = requireHousehold()
        val current = dao.findById(householdId, id) ?: return
        if (current.isOneOff == isOneOff) return  // idempotent: no write, no sync hit
        dao.upsert(
            current.copy(
                isOneOff = isOneOff,
                updatedAt = clock.millis(),
                pendingSync = true,
            ),
        )
    }

    override suspend fun reorderStores(orderedIds: List<String>) = db.withTransaction {
        val householdId = requireHousehold()
        val now = clock.millis()
        // Wrapping in a transaction means a partial reorder (caller cancels,
        // device dies mid-write) never leaves the picker with mixed old/new
        // displayOrders. Each setDisplayOrder also flips pendingSync so the
        // SyncEngine pushes the whole reorder on its next tick.
        orderedIds.forEachIndexed { index, id ->
            dao.setDisplayOrder(householdId, id, index, now)
        }
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        // Cascade so a deleted store doesn't leave orphan xrefs (which would still
        // surface in @Relation joins as a tombstoned store) or orphan SCO rows
        // (which would still appear in observeForStore).
        val householdId = requireHousehold()
        val now = clock.millis()
        dao.softDelete(householdId, id, now)
        // xrefDao + scoDao still take a `userId: String` named parameter; the
        // VALUE is `householdId` (identical to userId in single-member
        // households). When those DAOs flip to `householdId` filtering in a
        // later Phase 1.2 commit, no change needed here.
        xrefDao.softDeleteForStore(householdId, id, now)
        scoDao.softDeleteForStore(householdId, id, now)
    }

    override suspend fun undoSoftDelete(id: String) = db.withTransaction {
        val householdId = requireHousehold()
        // Find the store regardless of tombstone state, read back the timestamp
        // it was deleted at, and restore the cascade by that exact ms. Filtering
        // by the exact deletedAt means a later, separate tombstoning of a
        // different row at a different time isn't accidentally restored too.
        val store = dao.findAnyById(householdId, id) ?: return@withTransaction
        val deletedAt = store.deletedAt ?: return@withTransaction
        val now = clock.millis()
        dao.restoreFromTombstone(householdId, id, now)
        xrefDao.restoreCascadeForStore(householdId, id, deletedAt, now)
        scoDao.restoreCascadeForStore(householdId, id, deletedAt, now)
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")

    private fun requireHousehold(): String =
        householdSession.currentHouseholdId() ?: throw IllegalStateException("No active household")
}
