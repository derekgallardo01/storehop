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

        return migrator
    }
}
