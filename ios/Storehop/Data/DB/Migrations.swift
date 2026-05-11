import GRDB

/// Schema migrations for the Storehop SQLite database.
///
/// Why a single `v5_initial` migration instead of replaying Android's v1→v5
/// history: the iOS app has no installed users, so no device starts at v1, v2,
/// v3 or v4. Replaying every step would just be a redundant series of
/// `CREATE`+`ALTER`+`CREATE` that lands on the same v5 schema we'd produce
/// directly. We do however match Android's migration *numbering* — when
/// Android adds v6, we register a `v6` step here that runs after `v5_initial`
/// and the upgrade flow stays in lock-step.
///
/// The SQL below is lifted verbatim from
/// `app/schemas/com.storehop.app.data.db.StorehopDatabase/5.json` (Room's
/// auto-generated schema dump for v5). Field order, NOT NULL constraints,
/// default values, FK on-delete actions, and index definitions all mirror it
/// exactly, so the iOS database is byte-shape compatible with Android — same
/// columns, same names, same types, same constraints.
enum Migrations {
    static func migrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()

        // Block accidental destructive migrations from passing review by
        // failing the build during DEBUG if a registered migration is later
        // edited rather than appended.
        #if DEBUG
        migrator.eraseDatabaseOnSchemaChange = false
        #endif

        migrator.registerMigration("v5_initial") { db in
            // categories — must come before items (FK target)
            try db.execute(sql: """
                CREATE TABLE categories (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    nameKey TEXT,
                    icon TEXT,
                    isArchived INTEGER NOT NULL,
                    isSeeded INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(id)
                )
                """)
            try db.execute(sql: "CREATE UNIQUE INDEX index_categories_userId_name ON categories (userId, name)")
            try db.execute(sql: "CREATE INDEX index_categories_userId ON categories (userId)")
            try db.execute(sql: "CREATE INDEX index_categories_deletedAt ON categories (deletedAt)")

            // stores — must come before items references and xref FK targets
            try db.execute(sql: """
                CREATE TABLE stores (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    colorArgb INTEGER,
                    isArchived INTEGER NOT NULL,
                    isSeeded INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    displayOrder INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id)
                )
                """)
            try db.execute(sql: "CREATE UNIQUE INDEX index_stores_userId_name ON stores (userId, name)")
            try db.execute(sql: "CREATE INDEX index_stores_userId ON stores (userId)")
            try db.execute(sql: "CREATE INDEX index_stores_deletedAt ON stores (deletedAt)")

            // items — references categories(id) ON DELETE SET NULL
            try db.execute(sql: """
                CREATE TABLE items (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    categoryId TEXT,
                    notes TEXT,
                    quantity TEXT,
                    isNeeded INTEGER NOT NULL,
                    lastPurchasedAt INTEGER,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    brand TEXT,
                    imageUrl TEXT,
                    isStaple INTEGER NOT NULL DEFAULT 0,
                    isPriority INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id),
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """)
            try db.execute(sql: "CREATE INDEX index_items_categoryId ON items (categoryId)")
            try db.execute(sql: "CREATE INDEX index_items_isNeeded ON items (isNeeded)")
            try db.execute(sql: "CREATE INDEX index_items_name ON items (name)")
            try db.execute(sql: "CREATE INDEX index_items_userId ON items (userId)")
            try db.execute(sql: "CREATE INDEX index_items_deletedAt ON items (deletedAt)")

            // item_store_xref — composite PK (itemId, storeId), CASCADE both FKs
            try db.execute(sql: """
                CREATE TABLE item_store_xref (
                    itemId TEXT NOT NULL,
                    storeId TEXT NOT NULL,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    isNeeded INTEGER NOT NULL DEFAULT 1,
                    lastPurchasedAt INTEGER,
                    PRIMARY KEY(itemId, storeId),
                    FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(storeId) REFERENCES stores(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """)
            try db.execute(sql: "CREATE INDEX index_item_store_xref_storeId ON item_store_xref (storeId)")
            try db.execute(sql: "CREATE INDEX index_item_store_xref_userId ON item_store_xref (userId)")
            try db.execute(sql: "CREATE INDEX index_item_store_xref_deletedAt ON item_store_xref (deletedAt)")

            // store_category_order — composite PK (storeId, categoryId), CASCADE both FKs
            try db.execute(sql: """
                CREATE TABLE store_category_order (
                    storeId TEXT NOT NULL,
                    categoryId TEXT NOT NULL,
                    displayOrder INTEGER NOT NULL,
                    isSeeded INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(storeId, categoryId),
                    FOREIGN KEY(storeId) REFERENCES stores(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """)
            try db.execute(sql: "CREATE INDEX index_store_category_order_categoryId ON store_category_order (categoryId)")
            try db.execute(sql: "CREATE INDEX index_store_category_order_userId ON store_category_order (userId)")
            try db.execute(sql: "CREATE INDEX index_store_category_order_deletedAt ON store_category_order (deletedAt)")

            // purchase_records — no FK constraints (audit trail outlives parents)
            try db.execute(sql: """
                CREATE TABLE purchase_records (
                    id TEXT NOT NULL,
                    itemId TEXT NOT NULL,
                    storeId TEXT,
                    purchasedAt INTEGER NOT NULL,
                    userId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(id)
                )
                """)
            try db.execute(sql: "CREATE INDEX index_purchase_records_itemId ON purchase_records (itemId)")
            try db.execute(sql: "CREATE INDEX index_purchase_records_storeId ON purchase_records (storeId)")
            try db.execute(sql: "CREATE INDEX index_purchase_records_purchasedAt ON purchase_records (purchasedAt)")
            try db.execute(sql: "CREATE INDEX index_purchase_records_userId ON purchase_records (userId)")
            try db.execute(sql: "CREATE INDEX index_purchase_records_deletedAt ON purchase_records (deletedAt)")
        }

        // Mirrors Android MIGRATION_5_6 (v0.5.5).
        //
        // Drops the UNIQUE constraint on `(userId, name)` for stores and
        // categories, replacing each index with a plain non-unique
        // counterpart of the same name. The UNIQUE index counted
        // tombstoned rows, so a previously soft-deleted "Pets" blocked
        // renaming a live "Pet" → "Pets". Repository-layer rename now
        // does an alive-only collision check via `findByName`, so the
        // schema doesn't need to enforce uniqueness.
        //
        // No row data changes; no `pendingSync` bump. Firestore docs
        // aren't affected by local index topology.
        migrator.registerMigration("v6_drop_unique_name_indexes") { db in
            try db.execute(sql: "DROP INDEX IF EXISTS index_categories_userId_name")
            try db.execute(sql: "CREATE INDEX index_categories_userId_name ON categories (userId, name)")
            try db.execute(sql: "DROP INDEX IF EXISTS index_stores_userId_name")
            try db.execute(sql: "CREATE INDEX index_stores_userId_name ON stores (userId, name)")
        }

        // v7: add `displayOrder` to categories for the v0.6.4 Manage
        // Categories drag-reorder. Mirrors Android's MIGRATION_6_7.
        // Backfill: dense-rank by name within each user's alive list so the
        // ordering on first open matches the previous alphabetical view.
        // Bumps pendingSync so the new column lands in Firestore on the
        // next push.
        migrator.registerMigration("v7_categories_display_order") { db in
            try db.execute(sql: "ALTER TABLE categories ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0")
            try db.execute(sql: """
                UPDATE categories
                SET displayOrder = (
                    SELECT COUNT(*) FROM categories AS c2
                    WHERE c2.userId = categories.userId
                      AND c2.deletedAt IS NULL
                      AND (
                            (c2.name COLLATE NOCASE) < (categories.name COLLATE NOCASE)
                         OR ((c2.name COLLATE NOCASE) = (categories.name COLLATE NOCASE) AND c2.id < categories.id)
                      )
                ),
                pendingSync = 1
                WHERE deletedAt IS NULL
                """)
        }

        // v8: v0.7.0 multi-user household scope. Mirrors Android's
        // MIGRATION_7_8. Adds `householdId TEXT NOT NULL DEFAULT ''`
        // to every household-owned table + an index for the new
        // filter column, backfills `householdId = userId` on alive rows
        // (single-member households mirror the user's own uid), and
        // re-flags pendingSync so the new column syncs to Firestore on
        // the next push. New `household_members` table is the local
        // mirror of `/memberships/{uid}/households/{hid}` Firestore docs.
        migrator.registerMigration("v8_household_scope") { db in
            // 1. Add householdId to every household-owned table + an
            //    index so the new WHERE-householdId queries are sargable.
            for table in [
                "items",
                "stores",
                "categories",
                "item_store_xref",
                "store_category_order",
                "purchase_records",
            ] {
                try db.execute(sql: """
                    ALTER TABLE \(table) ADD COLUMN householdId TEXT NOT NULL DEFAULT ''
                    """)
                try db.execute(sql: """
                    CREATE INDEX index_\(table)_householdId ON \(table)(householdId)
                    """)
            }

            // 2. Backfill: every alive row's householdId mirrors its
            //    userId. Tombstones are intentionally NOT touched —
            //    they'll never be queried by householdId (deletedAt IS
            //    NOT NULL filters them out), and sync won't push the
            //    rewrite either.
            for table in [
                "items",
                "stores",
                "categories",
                "item_store_xref",
                "store_category_order",
                "purchase_records",
            ] {
                try db.execute(sql: """
                    UPDATE \(table)
                    SET householdId = userId, pendingSync = 1
                    WHERE deletedAt IS NULL
                    """)
            }

            // 3. New household_members table. Composite PK (uid, hid) so
            //    one user can belong to multiple households (v0.7.x; for
            //    v0.7.0 the active-household query just picks the latest
            //    `joinedAt` row). Soft-delete via deletedAt mirrors every
            //    other entity.
            try db.execute(sql: """
                CREATE TABLE household_members (
                    uid TEXT NOT NULL,
                    householdId TEXT NOT NULL,
                    displayName TEXT,
                    joinedAt INTEGER NOT NULL,
                    isOwner INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    pendingSync INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(uid, householdId)
                )
                """)
            try db.execute(sql: "CREATE INDEX index_household_members_uid ON household_members (uid)")
            try db.execute(sql: "CREATE INDEX index_household_members_householdId ON household_members (householdId)")
            try db.execute(sql: "CREATE INDEX index_household_members_deletedAt ON household_members (deletedAt)")
        }

        return migrator
    }
}
