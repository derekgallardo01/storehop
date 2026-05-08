package com.storehop.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end migration coverage from v1 (very first ship) through v5 (per-
 * store need state). For each migration we pin two contracts:
 *
 *  1. Existing rows survive without data loss.
 *  2. New columns get the defaults / backfills the app expects when it
 *     subsequently queries.
 *
 * A bug in any of these silently corrupts an upgrading user's database --
 * which is the unreviewable failure mode the schema-export-to-VCS pattern
 * is supposed to backstop. These tests are the second backstop.
 *
 * We drive the migrations directly against a SupportSQLiteDatabase using the
 * v1 schema's CREATE TABLE statements -- the SQL under test (the migrate()
 * blocks) runs unchanged on this same kind of connection in production,
 * so we get the same coverage MigrationTestHelper would give without the
 * asset-path complications it has under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val helperFactory = FrameworkSQLiteOpenHelperFactory()

    @Test fun `full v1 to v6 pipeline preserves rows and applies expected defaults`() {
        withV1Db { db ->
            seedV1Cohort(db)
        }.migrateTo(6).use { db ->
            // Every entity still has its row.
            db.assertCount("items", 1)
            db.assertCount("stores", 2)
            db.assertCount("categories", 1)
            db.assertCount("item_store_xref", 2)
            db.assertCount("store_category_order", 1)
            db.assertCount("purchase_records", 1)

            // pendingSync flagged on after v1->v2.
            db.queryRow("SELECT pendingSync FROM stores WHERE id='s1'") { c ->
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            // v2->v3 added isStaple/isPriority defaulting to 0.
            db.queryRow("SELECT isStaple, isPriority FROM items WHERE id='i1'") { c ->
                assertThat(c.getInt(0)).isEqualTo(0)
                assertThat(c.getInt(1)).isEqualTo(0)
            }
            // v3->v4 dense-ranked stores alphabetically: Aldi=0, Lidl=1.
            db.queryRow("SELECT displayOrder FROM stores WHERE id='s2'") { c ->
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            db.queryRow("SELECT displayOrder FROM stores WHERE id='s1'") { c ->
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            // v4->v5 backfilled xref isNeeded from the parent item's old global value.
            db.queryRow("SELECT isNeeded, lastPurchasedAt FROM item_store_xref WHERE itemId='i1' AND storeId='s1'") { c ->
                assertThat(c.getInt(0)).isEqualTo(1)
                assertThat(c.getLong(1)).isEqualTo(500L)
            }
            // v5->v6 dropped UNIQUE on (userId, name) for both categories
            // and stores -- they're now plain non-unique indices.
            assertThat(readIndexUnique(db, "categories", "index_categories_userId_name")).isFalse()
            assertThat(readIndexUnique(db, "stores", "index_stores_userId_name")).isFalse()
        }
    }

    @Test fun `v1 to v2 sets pendingSync defaulted to 1 on every synced table`() {
        withV1Db { db ->
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s1','Lidl',NULL,0,0,'u',1,1,NULL)")
            db.execSQL("INSERT INTO categories(id,name,nameKey,icon,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('c1','Cat',NULL,NULL,0,0,'u',1,1,NULL)")
            db.execSQL("INSERT INTO items(id,name,categoryId,notes,quantity,isNeeded,lastPurchasedAt,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('i1','Milk','c1',NULL,NULL,1,NULL,'u',1,1,NULL)")
        }.migrateTo(2).use { db ->
            db.queryRow("SELECT pendingSync FROM stores") { c -> assertThat(c.getInt(0)).isEqualTo(1) }
            db.queryRow("SELECT pendingSync FROM categories") { c -> assertThat(c.getInt(0)).isEqualTo(1) }
            db.queryRow("SELECT pendingSync FROM items") { c -> assertThat(c.getInt(0)).isEqualTo(1) }
        }
    }

    @Test fun `v3 to v4 dense-ranks stores by name within each user`() {
        // Two users so we can confirm the ordering is per-user.
        withV1Db { db ->
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s1','Pingo Doce',NULL,0,0,'u1',1,1,NULL)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s2','Aldi',NULL,0,0,'u1',1,1,NULL)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s3','Continente',NULL,0,0,'u1',1,1,NULL)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s4','Auchan',NULL,0,0,'u2',1,1,NULL)")
        }.migrateTo(4).use { db ->
            // u1 alphabetical ranking.
            val u1 = mutableListOf<Pair<String, Int>>()
            db.query("SELECT id, displayOrder FROM stores WHERE userId='u1' ORDER BY displayOrder").use { c ->
                while (c.moveToNext()) u1 += c.getString(0) to c.getInt(1)
            }
            assertThat(u1).containsExactly(
                "s2" to 0, // Aldi
                "s3" to 1, // Continente
                "s1" to 2, // Pingo Doce
            ).inOrder()

            // u2 single store at 0.
            db.queryRow("SELECT displayOrder FROM stores WHERE userId='u2'") { c ->
                assertThat(c.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test fun `v5 to v6 drops unique indices on categories and stores`() {
        // After v6, name uniqueness is no longer enforced at the DB level
        // (see MIGRATION_5_6's docblock for why -- tombstones blocked name
        // reuse). Verify PRAGMA shows the named indices are now non-unique
        // AND that a duplicate-name insert succeeds at the SQLite level so
        // tombstones can coexist with alive same-name rows.
        withV1Db { db ->
            db.execSQL("INSERT INTO categories(id,name,nameKey,icon,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('c1','Pets',NULL,NULL,0,0,'u',1,1,NULL)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s1','Lidl',NULL,0,0,'u',1,1,NULL)")
        }.migrateTo(6).use { db ->
            assertThat(readIndexUnique(db, "categories", "index_categories_userId_name")).isFalse()
            assertThat(readIndexUnique(db, "stores", "index_stores_userId_name")).isFalse()

            // Insert a tombstoned twin of each row -- post-v6 this must
            // succeed without throwing. Pre-v6 this would have hit a UNIQUE
            // constraint violation. The two rows live alongside each other:
            // one alive, one tombstoned, both named "Pets".
            db.execSQL("INSERT INTO categories(id,name,nameKey,icon,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt,pendingSync) " +
                "VALUES('c1_tomb','Pets',NULL,NULL,0,0,'u',1,1,99,1)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt,pendingSync,displayOrder) " +
                "VALUES('s1_tomb','Lidl',NULL,0,0,'u',1,1,99,1,5)")
            db.assertCount("categories", 2)
            db.assertCount("stores", 2)

            // Existing row data preserved across the migration.
            db.queryRow("SELECT name FROM categories WHERE id='c1'") { c ->
                assertThat(c.getString(0)).isEqualTo("Pets")
            }
            db.queryRow("SELECT name FROM stores WHERE id='s1'") { c ->
                assertThat(c.getString(0)).isEqualTo("Lidl")
            }
        }
    }

    private fun readIndexUnique(
        db: SupportSQLiteDatabase,
        tableName: String,
        indexName: String,
    ): Boolean {
        db.query("PRAGMA index_list(`$tableName`)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == indexName) {
                    return c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                }
            }
        }
        error("index $indexName not found on $tableName")
    }

    @Test fun `v4 to v5 backfills xref isNeeded and lastPurchasedAt from the parent item`() {
        withV1Db { db ->
            db.execSQL("INSERT INTO categories(id,name,nameKey,icon,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('cat','Cat',NULL,NULL,0,0,'u',1,1,NULL)")
            db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('s1','Lidl',NULL,0,0,'u',1,1,NULL)")
            // Milk: pre-upgrade purchased (isNeeded=0), with a lastPurchasedAt.
            db.execSQL("INSERT INTO items(id,name,categoryId,notes,quantity,isNeeded,lastPurchasedAt,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('i1','Milk','cat',NULL,NULL,0,500,'u',1,1,NULL)")
            // Eggs: still needed, no lastPurchasedAt.
            db.execSQL("INSERT INTO items(id,name,categoryId,notes,quantity,isNeeded,lastPurchasedAt,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('i2','Eggs','cat',NULL,NULL,1,NULL,'u',1,1,NULL)")
            db.execSQL("INSERT INTO item_store_xref(itemId,storeId,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('i1','s1','u',1,1,NULL)")
            db.execSQL("INSERT INTO item_store_xref(itemId,storeId,userId,createdAt,updatedAt,deletedAt) " +
                "VALUES('i2','s1','u',1,1,NULL)")
        }.migrateTo(5).use { db ->
            // Milk's xref carries the parent's purchased state forward.
            db.queryRow("SELECT isNeeded, lastPurchasedAt FROM item_store_xref WHERE itemId='i1'") { c ->
                assertThat(c.getInt(0)).isEqualTo(0)
                assertThat(c.getLong(1)).isEqualTo(500L)
            }
            // Eggs: still needed, NULL lastPurchasedAt.
            db.queryRow("SELECT isNeeded, lastPurchasedAt FROM item_store_xref WHERE itemId='i2'") { c ->
                assertThat(c.getInt(0)).isEqualTo(1)
                assertThat(c.isNull(1)).isTrue()
            }
        }
    }

    // -------- helpers --------

    private fun seedV1Cohort(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO categories(id,name,nameKey,icon,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('cat','Cat',NULL,NULL,0,0,'u',1,1,NULL)")
        db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('s1','Lidl',NULL,0,0,'u',1,1,NULL)")
        db.execSQL("INSERT INTO stores(id,name,colorArgb,isArchived,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('s2','Aldi',NULL,0,0,'u',1,1,NULL)")
        db.execSQL("INSERT INTO items(id,name,categoryId,notes,quantity,isNeeded,lastPurchasedAt,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('i1','Milk','cat',NULL,NULL,1,500,'u',1,1,NULL)")
        db.execSQL("INSERT INTO item_store_xref(itemId,storeId,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('i1','s1','u',1,1,NULL)")
        db.execSQL("INSERT INTO item_store_xref(itemId,storeId,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('i1','s2','u',1,1,NULL)")
        db.execSQL("INSERT INTO store_category_order(storeId,categoryId,displayOrder,isSeeded,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('s1','cat',0,0,'u',1,1,NULL)")
        db.execSQL("INSERT INTO purchase_records(id,itemId,storeId,purchasedAt,userId,createdAt,updatedAt,deletedAt) " +
            "VALUES('p1','i1','s1',500,'u',1,1,NULL)")
    }

    /**
     * Returns a fresh in-memory v1 database with the v1 schema applied.
     * The caller seeds it with whatever rows the test needs, then chains
     * [DbHandle.migrateTo] to step through migrations.
     */
    private fun withV1Db(seed: (SupportSQLiteDatabase) -> Unit): DbHandle {
        val helper = helperFactory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Schema-version-1 CREATE TABLEs, copied from
                        // app/schemas/.../1.json. Includes the indexes the
                        // migrations may rely on.
                        applyV1Schema(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        val db = helper.writableDatabase
        seed(db)
        return DbHandle(helper, db)
    }

    private inner class DbHandle(
        val helper: SupportSQLiteOpenHelper,
        var db: SupportSQLiteDatabase,
    ) {
        fun migrateTo(targetVersion: Int): DbHandle {
            val migrations = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            val needed = migrations.filter { it.endVersion <= targetVersion }
            needed.forEach { it.migrate(db) }
            return this
        }

        inline fun <R> use(block: (SupportSQLiteDatabase) -> R): R {
            try {
                return block(db)
            } finally {
                helper.close()
            }
        }
    }

    private fun SupportSQLiteDatabase.queryRow(sql: String, block: (android.database.Cursor) -> Unit) {
        query(sql).use { c ->
            check(c.moveToFirst()) { "no row for: $sql" }
            block(c)
        }
    }

    private fun SupportSQLiteDatabase.assertCount(table: String, expected: Int) {
        query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(expected)
        }
    }
}

/**
 * The v1 schema, transcribed from app/schemas/.../1.json. Kept inline rather
 * than read from the JSON because (a) Robolectric assets paths are fiddly,
 * and (b) the test wants the v1 SQL to be visible at the test site --
 * if v1.json ever changes, the test will fail loudly and force the diff
 * to go through review.
 */
private fun applyV1Schema(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `categories` (
          `id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT, `icon` TEXT,
          `isArchived` INTEGER NOT NULL, `isSeeded` INTEGER NOT NULL,
          `userId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
          `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_userId_name` ON `categories` (`userId`, `name`)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `stores` (
          `id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER,
          `isArchived` INTEGER NOT NULL, `isSeeded` INTEGER NOT NULL,
          `userId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
          `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stores_userId_name` ON `stores` (`userId`, `name`)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `items` (
          `id` TEXT NOT NULL, `name` TEXT NOT NULL, `categoryId` TEXT, `notes` TEXT,
          `quantity` TEXT, `isNeeded` INTEGER NOT NULL, `lastPurchasedAt` INTEGER,
          `userId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
          `deletedAt` INTEGER, PRIMARY KEY(`id`),
          FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
    )

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `item_store_xref` (
          `itemId` TEXT NOT NULL, `storeId` TEXT NOT NULL, `userId` TEXT NOT NULL,
          `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
          PRIMARY KEY(`itemId`, `storeId`),
          FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
          FOREIGN KEY(`storeId`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `store_category_order` (
          `storeId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `displayOrder` INTEGER NOT NULL,
          `isSeeded` INTEGER NOT NULL, `userId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
          `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`storeId`, `categoryId`),
          FOREIGN KEY(`storeId`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
          FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `purchase_records` (
          `id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `storeId` TEXT,
          `purchasedAt` INTEGER NOT NULL, `userId` TEXT NOT NULL,
          `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
          PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}
