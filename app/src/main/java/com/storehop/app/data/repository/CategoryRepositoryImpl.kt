package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Category
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val dao: CategoryDao,
    private val itemDao: ItemDao,
    private val scoDao: StoreCategoryOrderDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : CategoryRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Category>> =
        session.userId.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else dao.observeAll(uid, includeArchived)
        }

    override suspend fun addCategory(name: String, icon: String?): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val userId = requireSignedIn()
        val now = clock.millis()

        // See StoreRepositoryImpl.addStore for the resurrection rationale.
        val existing = dao.findAnyByName(userId, trimmed)
        when {
            existing == null -> {
                val id = ids.newId()
                // Append at the end of the global Manage Categories list.
                val nextOrder = (dao.maxDisplayOrder(userId) ?: -1) + 1
                dao.upsert(
                    Category(
                        id = id,
                        name = trimmed,
                        nameKey = null,
                        icon = icon,
                        isArchived = false,
                        isSeeded = false,
                        userId = userId,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null,
                        displayOrder = nextOrder,
                    ),
                )
                id
            }
            existing.deletedAt == null -> {
                throw IllegalArgumentException("A category named \"$trimmed\" already exists")
            }
            else -> {
                // Resurrect a tombstone -- append to the end so it doesn't
                // collide with another row's existing displayOrder.
                val nextOrder = (dao.maxDisplayOrder(userId) ?: -1) + 1
                dao.upsert(
                    existing.copy(
                        icon = icon ?: existing.icon,
                        isArchived = false,
                        deletedAt = null,
                        updatedAt = now,
                        pendingSync = true,
                        displayOrder = nextOrder,
                    ),
                )
                existing.id
            }
        }
    }

    override suspend fun rename(id: String, name: String) = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val userId = requireSignedIn()
        val current = dao.findById(userId, id) ?: return@withTransaction
        // Alive-only collision check. Schema v6 dropped the UNIQUE constraint
        // on (userId, name) precisely so deleted rows don't block name reuse:
        // pre-v6 the unique index counted tombstones, and Mike couldn't
        // rename "Pet" -> "Pets" because a previously deleted "Pets" still
        // owned the name. We now reject only when an alive row holds the
        // target. Same-id case changes ("Pets" -> "pets" of the row's own
        // name) pass through because findByName returns the same row.
        val collision = dao.findByName(userId, trimmed)
        if (collision != null && collision.id != current.id) {
            throw IllegalArgumentException("A category named \"$trimmed\" already exists")
        }
        dao.upsert(current.copy(name = trimmed, updatedAt = clock.millis(), pendingSync = true))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(requireSignedIn(), id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        // Cascade so a deleted category doesn't leave orphan SCO rows or items
        // whose categoryId resolves to a tombstoned Category through @Relation.
        val userId = requireSignedIn()
        val now = clock.millis()
        dao.softDelete(userId, id, now)
        itemDao.clearCategoryReferences(userId, id, now)
        scoDao.softDeleteForCategory(userId, id, now)
    }

    override suspend fun undoSoftDelete(id: String) = db.withTransaction {
        val userId = requireSignedIn()
        // Find the category regardless of tombstone state, read back the
        // exact deletedAt it was tombstoned at. Filtering the cascade restore
        // by that exact ms means a later, separate tombstoning at a different
        // time isn't accidentally restored too -- mirrors the StoreRepository
        // pattern for store undo.
        val category = dao.findAnyById(userId, id) ?: return@withTransaction
        val deletedAt = category.deletedAt ?: return@withTransaction
        val now = clock.millis()
        dao.restoreFromTombstone(userId, id, now)
        scoDao.restoreCascadeForCategory(userId, id, deletedAt, now)
        // Re-link items: cascade-clear stamped them with updatedAt = deletedAt
        // and categoryId = NULL. Symmetrical query restores them in the same
        // window. See ItemDao.restoreCategoryReferences for the precision
        // caveat (unrelated item updates at the exact same ms would match).
        itemDao.restoreCategoryReferences(userId, id, deletedAt, now)
    }

    override suspend fun reorder(orderedIds: List<String>) = db.withTransaction {
        val userId = requireSignedIn()
        val now = clock.millis()
        // One UPDATE per id in sequence; SQLite wraps the whole withTransaction
        // block in a single SQL transaction, so a mid-loop crash rolls every
        // partial write back together.
        orderedIds.forEachIndexed { index, id ->
            dao.updateDisplayOrder(userId, id, index, now)
        }
    }

    override suspend fun softDeleteMany(ids: List<String>): Long = db.withTransaction {
        val userId = requireSignedIn()
        val now = clock.millis()
        ids.forEach { id ->
            dao.softDelete(userId, id, now)
            itemDao.clearCategoryReferences(userId, id, now)
            scoDao.softDeleteForCategory(userId, id, now)
        }
        now
    }

    override suspend fun undoSoftDeleteMany(deletedAt: Long) = db.withTransaction {
        val userId = requireSignedIn()
        val now = clock.millis()
        // Restore exactly the rows tombstoned at this batch's instant. Each
        // restored category's cascade (SCO rows + item.categoryId) was
        // stamped with the same `updatedAt = deletedAt`, so the
        // restore-by-time queries pick them up symmetrically.
        val tombstoned = dao.findTombstonedAt(userId, deletedAt)
        tombstoned.forEach { category ->
            dao.restoreFromTombstone(userId, category.id, now)
            scoDao.restoreCascadeForCategory(userId, category.id, deletedAt, now)
            itemDao.restoreCategoryReferences(userId, category.id, deletedAt, now)
        }
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")
}
