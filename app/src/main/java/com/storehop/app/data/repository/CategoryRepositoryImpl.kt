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
        dao.observeAll(session.currentUserId(), includeArchived)

    override suspend fun addCategory(name: String, icon: String?): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val userId = session.currentUserId()
        // See StoreRepositoryImpl.addStore for the rationale on detecting duplicates
        // here AND wrapping in withTransaction (closes a TOCTOU race that would let
        // two concurrent addCategory calls with the same name both pass the check).
        require(dao.findByName(userId, trimmed) == null) {
            "A category named \"$trimmed\" already exists"
        }
        val now = clock.millis()
        val id = ids.newId()
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
            ),
        )
        id
    }

    override suspend fun rename(id: String, name: String) {
        val current = dao.findById(session.currentUserId(), id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis()))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(session.currentUserId(), id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) = db.withTransaction {
        // Cascade so a deleted category doesn't leave orphan SCO rows or items
        // whose categoryId resolves to a tombstoned Category through @Relation.
        // Items keep existing — only their categoryId is cleared (UI shows them
        // as "uncategorized" rather than disappearing them).
        val userId = session.currentUserId()
        val now = clock.millis()
        dao.softDelete(userId, id, now)
        itemDao.clearCategoryReferences(userId, id, now)
        scoDao.softDeleteForCategory(userId, id, now)
    }
}
