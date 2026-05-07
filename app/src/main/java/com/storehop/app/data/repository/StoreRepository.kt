package com.storehop.app.data.repository

import com.storehop.app.data.entity.Store
import kotlinx.coroutines.flow.Flow

interface StoreRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Store>>
    fun observeById(id: String): Flow<Store?>

    suspend fun addStore(name: String, colorArgb: Int? = null): String
    suspend fun rename(id: String, name: String)
    suspend fun setColor(id: String, colorArgb: Int?)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun softDelete(id: String)

    /**
     * Undo a previous [softDelete]. Reads the row's current `deletedAt`
     * timestamp and restores the store row plus every xref + SCO row that
     * was tombstoned at that exact instant (the cascade was atomic, so all
     * affected rows share the timestamp). No-op if the row isn't currently
     * tombstoned.
     */
    suspend fun undoSoftDelete(id: String)

    /**
     * Persist a new picker order. [orderedIds] is the full list of live store
     * ids in the desired top-to-bottom order; each row is rewritten with its
     * new displayOrder (its index in the list) and re-flagged pendingSync.
     *
     * Stores not present in [orderedIds] are left untouched -- the caller is
     * expected to pass the entire visible set. Wrapped in a single
     * transaction so a partial drag never leaves the picker in an
     * inconsistent state.
     */
    suspend fun reorderStores(orderedIds: List<String>)
}
