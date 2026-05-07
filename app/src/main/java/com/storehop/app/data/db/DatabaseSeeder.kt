package com.storehop.app.data.db

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.storehop.app.data.util.LocalOnlyUserSessionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the bundled stores, categories, and per-store category orderings on first DB creation.
 *
 * Runs inside [RoomDatabase.Callback.onCreate] so we can't use Room types here — only raw SQL
 * via [SupportSQLiteDatabase]. Reads the three JSON files under `assets/seed/`.
 *
 * All seeded rows carry `userId = "local-only"`, `isSeeded = 1`, and use deterministic IDs
 * from the JSON files (e.g. `store_lidl`, `cat_produce`). `createdAt`/`updatedAt` use
 * [SEED_TIMESTAMP] — a fixed constant tied to this seed-pack version, NOT
 * `System.currentTimeMillis()` — so a re-seed on a different device produces identical rows.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
) : RoomDatabase.Callback() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        seedStores(db)
        seedCategories(db)
        seedStoreCategoryOrders(db)
    }

    private fun seedStores(db: SupportSQLiteDatabase) {
        val raw = context.assets.open("seed/stores.json").bufferedReader().use { it.readText() }
        val stores = json.decodeFromString<List<SeedStore>>(raw)
        db.beginTransaction()
        try {
            stores.forEachIndexed { index, s ->
                // Use the JSON list index as the initial displayOrder so a fresh
                // install presents the seeded stores in the order the seed pack
                // was authored. Drag-and-drop on the Store Picker will rewrite it.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO stores
                    (id, name, colorArgb, isArchived, isSeeded, userId, createdAt, updatedAt, deletedAt, displayOrder)
                    VALUES (?, ?, NULL, 0, 1, ?, ?, ?, NULL, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(s.id, s.name, USER_ID, SEED_TIMESTAMP, SEED_TIMESTAMP, index),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedCategories(db: SupportSQLiteDatabase) {
        val raw = context.assets.open("seed/categories.json").bufferedReader().use { it.readText() }
        val categories = json.decodeFromString<List<SeedCategory>>(raw)
        db.beginTransaction()
        try {
            categories.forEach { c ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO categories
                    (id, name, nameKey, icon, isArchived, isSeeded, userId, createdAt, updatedAt, deletedAt)
                    VALUES (?, ?, ?, ?, 0, 1, ?, ?, ?, NULL)
                    """.trimIndent(),
                    arrayOf<Any?>(c.id, c.name, c.nameKey, c.icon, USER_ID, SEED_TIMESTAMP, SEED_TIMESTAMP),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedStoreCategoryOrders(db: SupportSQLiteDatabase) {
        val raw = context.assets.open("seed/store_categories.json").bufferedReader().use { it.readText() }
        val orders = json.decodeFromString<List<SeedStoreCategoryOrder>>(raw)
        db.beginTransaction()
        try {
            orders.forEach { o ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO store_category_order
                    (storeId, categoryId, displayOrder, isSeeded, userId, createdAt, updatedAt, deletedAt)
                    VALUES (?, ?, ?, 1, ?, ?, ?, NULL)
                    """.trimIndent(),
                    arrayOf<Any?>(o.storeId, o.categoryId, o.displayOrder, USER_ID, SEED_TIMESTAMP, SEED_TIMESTAMP),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Serializable
    private data class SeedStore(val id: String, val name: String)

    @Serializable
    private data class SeedCategory(
        val id: String,
        val name: String,
        val nameKey: String?,
        val icon: String?,
    )

    @Serializable
    private data class SeedStoreCategoryOrder(
        val storeId: String,
        val categoryId: String,
        val displayOrder: Int,
    )

    companion object {
        // Tied to the seed-pack version, not wall-clock time. Bump when the seed changes.
        const val SEED_TIMESTAMP: Long = 1_730_000_000_000L // 2024-10-27T00:00:00Z
        const val USER_ID: String = LocalOnlyUserSessionProvider.LOCAL_ONLY
    }
}
