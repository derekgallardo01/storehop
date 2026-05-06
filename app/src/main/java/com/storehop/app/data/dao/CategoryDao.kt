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

    @Query(
        """
        SELECT * FROM categories
        WHERE id = :id AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun findById(userId: String, id: String): Category?

    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId AND deletedAt IS NULL
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findByName(userId: String, name: String): Category?

    /** Mirror of StoreDao.findAnyByName -- includes tombstones for the resurrect-on-re-add path. */
    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAnyByName(userId: String, name: String): Category?

    @Upsert
    suspend fun upsert(category: Category)

    @Query(
        """
        UPDATE categories
        SET isArchived = :archived, updatedAt = :now
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun setArchived(userId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE categories
        SET deletedAt = :now, updatedAt = :now
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)
}
