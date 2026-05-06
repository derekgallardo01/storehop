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

/**
 * v2 -> v3: extend `items` with brand, imageUrl, isStaple, isPriority.
 *
 * brand and imageUrl are user-facing optional metadata. isStaple flags
 * "always-on-the-list" items that stay visible (struck-through) after
 * being marked purchased. isPriority flags "don't let me forget this" items
 * that get a colored side-stripe in lists and a critical-needs banner on
 * the Shop-at-Store + Store Picker screens.
 *
 * All four columns default to safe values (NULL / 0) for existing rows.
 * The booleans use the same DEFAULT-1-on-new-rows convention as
 * pendingSync so a future re-pushed seed picks up the new schema cleanly.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `brand` TEXT")
        db.execSQL("ALTER TABLE `items` ADD COLUMN `imageUrl` TEXT")
        db.execSQL("ALTER TABLE `items` ADD COLUMN `isStaple` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `items` ADD COLUMN `isPriority` INTEGER NOT NULL DEFAULT 0")
    }
}
