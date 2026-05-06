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
            existing.deletedAt == null -> {
                throw IllegalArgumentException("A category named \"$trimmed\" already exists")
            }
            else -> {
                dao.upsert(
                    existing.copy(
                        icon = icon ?: existing.icon,
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
        val userId = requireSignedIn()
        val current = dao.findById(userId, id) ?: return
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock.millis()))
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

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")
}
