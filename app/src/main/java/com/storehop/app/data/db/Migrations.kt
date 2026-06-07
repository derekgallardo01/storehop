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

/**
 * v5 -> v6: drop the UNIQUE constraint on `(userId, name)` for `categories`
 * and `stores`. Recreate as plain non-unique indices (still useful for
 * findByName lookups during CSV import + add/rename).
 *
 * Why: the old UNIQUE index counted tombstoned (soft-deleted) rows. A user
 * who deleted a category named "Pets" and later tried to rename a different
 * category to "Pets" hit a `SQLiteConstraintException` because the dead row
 * still owned the name. Mike reported exactly this on v0.5.4 after importing
 * hundreds of categories from his old shopping-list app -- many rename
 * attempts collided with phantom tombstones.
 *
 * SQLite's @Index annotation can't express partial indices (`WHERE deletedAt
 * IS NULL`), and Room's schema validator rejects extra indices that aren't
 * declared on the entity, so the cleanest answer is to drop DB-level
 * uniqueness entirely. Application-layer guards already enforce uniqueness
 * for alive rows: `addCategory` / `addStore` reject same-name alive rows and
 * resurrect tombstones; `rename` (post-v0.5.5) rejects alive collisions but
 * lets tombstones pass. All three paths are wrapped in `withTransaction` so
 * concurrent mutations serialize correctly.
 *
 * No row data changes -- only the index definition. No `pendingSync` bump:
 * Firestore docs aren't affected by local index topology.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // categories: drop the UNIQUE variant, recreate non-unique with the
        // same name so Room's schema validator finds the index it expects.
        db.execSQL("DROP INDEX IF EXISTS `index_categories_userId_name`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_categories_userId_name` " +
                "ON `categories` (`userId`, `name`)",
        )
        // stores: same shape.
        db.execSQL("DROP INDEX IF EXISTS `index_stores_userId_name`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stores_userId_name` " +
                "ON `stores` (`userId`, `name`)",
        )
    }
}

/**
 * v6 -> v7: add a `displayOrder` column to `categories` for the new
 * Manage Categories drag-to-reorder. Mirrors what v3 -> v4 did for
 * `stores`. This is the GLOBAL order on the Manage Categories screen;
 * per-store aisle order remains owned by `store_category_order` and
 * isn't affected.
 *
 * Backfill: dense rank by name within each user's live category list
 * so the ordering on first open matches the alphabetical view the user
 * had before. Tombstoned rows keep the column default of 0 -- they're
 * not displayed; resurrect-on-re-add applies a fresh order then.
 *
 * Bumps `pendingSync = 1` on every touched row so the new column lands
 * in Firestore on the next push.
 */
/**
 * v7 -> v8: introduce the `householdId` column on every per-user entity +
 * a new `household_members` table for the v0.7.0 multi-user account-sharing
 * feature.
 *
 * Scaffolding migration only — every backfilled row keeps `householdId =
 * userId` so the single-user world keeps working byte-for-byte. Query
 * changes that actually filter by `householdId` ship in follow-up phases.
 *
 * Per-row backfill rationale: today every user's data is keyed by `userId`;
 * after the migration each user is implicitly a "household of one" whose
 * id equals their uid. When someone later accepts an invite, their
 * `HouseholdSessionProvider.householdId` flips to the inviter's
 * `householdId` (= the inviter's uid), at which point all writes go to
 * the shared household path.
 *
 * Bumps `pendingSync = 1` on every touched alive row so the new column
 * lands in Firestore on the next push. Tombstones are left untouched —
 * they're not read by any live query path and don't need re-pushing.
 *
 * The new `household_members` table is initially empty; the first-launch
 * bootstrap in [com.storehop.app.auth.FirebaseAuthSessionProvider]
 * (Phase 2) seeds the active user's personal-household row.
 */
/**
 * v8 -> v9: register the `alive_item_store_xref` view.
 *
 * v0.8.1: a `WHERE deletedAt IS NULL` projection over `item_store_xref`
 * used as the `@Junction` target in
 * [com.storehop.app.data.db.relations.ItemWithCategoryAndStores]. Room's
 * `@Junction` doesn't filter the bridging table itself, so tombstoned
 * xrefs were leaking ghost stores into every consumer of `row.stores`
 * (form chips, CSV export, Items list `hasStores` toggle).
 *
 * Pointing the junction at this view fixes all three consumers at the
 * data layer instead of requiring each call site to filter. Replaces
 * the v0.8.0.5 tactical hack (`ItemRepository.aliveStoreIdsForItem`).
 *
 * No data is read or written; only DDL. Safe to re-run via
 * `CREATE VIEW IF NOT EXISTS`.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIEW IF NOT EXISTS `alive_item_store_xref` AS " +
                "SELECT itemId, storeId FROM item_store_xref WHERE deletedAt IS NULL",
        )
    }
}

/**
 * v9 -> v10: mark stores as "one-off" via a new boolean column.
 *
 * v0.9: a store flagged `isOneOff = 1` holds non-recurring purchases
 * (couch, drying rack, etc.). The downstream behavior change lives in
 * [com.storehop.app.data.dao.ItemDao.observeAll]'s EXISTS filter,
 * which hides items whose alive xrefs all point at one-off stores
 * from the master Items list. Mixed-tag items (one-off + regular)
 * stay on the master list because at least one alive xref points at
 * a regular store.
 *
 * Default = 0 keeps every existing store regular. The composite index
 * on (householdId, isOneOff) backs the EXISTS subquery so the master
 * list stays fast as the library grows.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stores` ADD COLUMN `isOneOff` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stores_householdId_isOneOff` " +
                "ON `stores` (`householdId`, `isOneOff`)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add householdId to every per-user entity. Empty-string default
        // so existing test fixtures + entity constructors keep compiling;
        // the UPDATE below populates real values for live rows.
        val tables = listOf(
            "items",
            "stores",
            "categories",
            "item_store_xref",
            "store_category_order",
            "purchase_records",
        )
        for (table in tables) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `householdId` TEXT NOT NULL DEFAULT ''")
            // Backfill alive rows to `householdId = userId`. Tombstones
            // get the default empty string -- they're invisible to live
            // queries and don't need re-pushing.
            db.execSQL(
                "UPDATE `$table` SET householdId = userId, pendingSync = 1 WHERE deletedAt IS NULL",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_householdId` ON `$table` (`householdId`)")
        }

        // New household_members table. Composite PK (uid, householdId)
        // matches the entity. Indices match what HouseholdMember.kt
        // declares -- Room verifies on first open.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `household_members` (
                `uid` TEXT NOT NULL,
                `householdId` TEXT NOT NULL,
                `displayName` TEXT,
                `joinedAt` INTEGER NOT NULL,
                `isOwner` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                `pendingSync` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`uid`, `householdId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_household_members_uid` ON `household_members` (`uid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_household_members_householdId` ON `household_members` (`householdId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_household_members_deletedAt` ON `household_members` (`deletedAt`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE `categories`
            SET displayOrder = (
                SELECT COUNT(*) FROM `categories` AS c2
                WHERE c2.userId = categories.userId
                  AND c2.deletedAt IS NULL
                  AND (
                        (c2.name COLLATE NOCASE) < (categories.name COLLATE NOCASE)
                     OR ((c2.name COLLATE NOCASE) = (categories.name COLLATE NOCASE) AND c2.id < categories.id)
                  )
            ),
            pendingSync = 1
            WHERE deletedAt IS NULL
            """.trimIndent(),
        )
    }
}

/**
 * v4 -> v5: move per-store need state from `items` to `item_store_xref`.
 *
 * The old model had `items.isNeeded` and `items.lastPurchasedAt` -- a single
 * "is this item on the list" flag that applied uniformly across every store
 * the item was tagged to. Marking milk purchased at Lidl flipped Aldi's
 * milk to "purchased" at the same time, which is wrong: each store's row
 * is its own piece of state -- "I bought milk at Lidl" and "I still need
 * milk at Aldi" must coexist.
 *
 * After this migration:
 *  - `item_store_xref.isNeeded` (default 1) stores per-(item, store) need
 *  - `item_store_xref.lastPurchasedAt` stores when this specific row was
 *    last purchased -- powers the within-session strike-through window
 *
 * Backfill: every live xref copies the parent item's current isNeeded /
 * lastPurchasedAt values, which is the most faithful snapshot we can
 * reconstruct -- both stores' rows briefly agree, then diverge as the
 * user shops.
 *
 * `items.isNeeded` and `items.lastPurchasedAt` stay in the schema (we'd
 * have to do a table-recreation migration to drop them) but become
 * vestigial -- new code paths read xref values; the item-level fields are
 * left frozen at their pre-migration values for tombstone safety on older
 * Firestore-pull code that may still parse them.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `item_store_xref` ADD COLUMN `isNeeded` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `item_store_xref` ADD COLUMN `lastPurchasedAt` INTEGER")
        // Backfill each live xref's per-store state from the parent item's
        // (now-vestigial) global values. Also re-flag pendingSync so the
        // next Firestore push carries the new columns.
        db.execSQL(
            """
            UPDATE `item_store_xref`
            SET isNeeded = COALESCE(
                    (SELECT i.isNeeded FROM `items` i WHERE i.id = item_store_xref.itemId),
                    1
                ),
                lastPurchasedAt = (
                    SELECT i.lastPurchasedAt FROM `items` i WHERE i.id = item_store_xref.itemId
                ),
                pendingSync = 1
            WHERE deletedAt IS NULL
            """.trimIndent(),
        )
    }
}
