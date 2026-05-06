package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId AND deletedAt IS NULL
          AND (:includeArchived OR isArchived = 0)
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeAll(userId: String, includeArchived: Boolean): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): Category?

    @Upsert
    suspend fun upsert(category: Category)

    @Query("UPDATE categories SET isArchived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
