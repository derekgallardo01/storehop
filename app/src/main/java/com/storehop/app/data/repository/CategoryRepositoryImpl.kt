package com.storehop.app.data.repository

import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.entity.Category
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val dao: CategoryDao,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val session: UserSessionProvider,
) : CategoryRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Category>> =
        dao.observeAll(session.currentUserId(), includeArchived)

    override suspend fun addCategory(name: String, icon: String?): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val userId = session.currentUserId()
        // Same rationale as StoreRepositoryImpl.addStore — Room's @Upsert silently
        // no-ops on the non-PK (userId, name) unique-index conflict, so detect it here.
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
        return id
    }

    override suspend fun rename(id: String, name: String) {
        val current = dao.findById(session.currentUserId(), id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis()))
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(session.currentUserId(), id, archived, clock.millis())
    }

    override suspend fun softDelete(id: String) {
        dao.softDelete(session.currentUserId(), id, clock.millis())
    }
}
