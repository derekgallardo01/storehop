package com.storehop.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: add a `pendingSync` boolean column to every synced entity.
 *
 * Defaults to `1` (true) so every existing row gets pushed to Firestore
 * once on first sync after the upgrade. New writes set it to `1` via the
 * entity data class default; the SyncEngine flips it to `0` after a
 * successful Firestore push.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "items",
            "categories",
            "stores",
            "item_store_xref",
            "store_category_order",
            "purchase_records",
        ).forEach { table ->
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `pendingSync` INTEGER NOT NULL DEFAULT 1")
        }
    }
}
