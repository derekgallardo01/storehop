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

/**
 * v3 -> v4: add a `displayOrder` column to `stores` so the Shop tab's
 * Store Picker can render the user's preferred order. Drag-and-drop on
 * that screen rewrites it.
 *
 * Backfill: assign sequential displayOrders to live (non-tombstoned) rows
 * grouped by userId, sorted by name (the order the picker was previously
 * showing) so existing users see no visible jump on upgrade. Tombstoned
 * rows keep the column default of 0 -- they're not displayed, and the
 * resurrect-on-re-add path handles them when they come back.
 *
 * Bumps `pendingSync = 1` on every touched row so the new column gets
 * pushed to Firestore on the next sync.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stores` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
        // Per-user dense ranking by name. The correlated subquery counts how
        // many live rows in this user's store list sort strictly before this
        // one (case-insensitive name, then id as tiebreaker for duplicates
        // that managed to slip through before the unique index existed).
        db.execSQL(
            """
            UPDATE `stores`
            SET displayOrder = (
                SELECT COUNT(*) FROM `stores` AS s2
                WHERE s2.userId = stores.userId
                  AND s2.deletedAt IS NULL
                  AND (
                        (s2.name COLLATE NOCASE) < (stores.name COLLATE NOCASE)
                     OR ((s2.name COLLATE NOCASE) = (stores.name COLLATE NOCASE) AND s2.id < stores.id)
                  )
            ),
            pendingSync = 1
            WHERE deletedAt IS NULL
            """.trimIndent(),
        )
    }
}
