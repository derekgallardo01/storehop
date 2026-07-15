package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.Store
import com.storehop.app.data.util.FakeHouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Pins the **non-destructive import** contract:
 *  - Existing alive items with the same name as a CSV row are NEVER modified.
 *  - The duplicate skip is the user-confirmed hard constraint:
 *    "i dont erase nothing he has now already in the db or his account."
 *  - Categories and stores referenced by items are auto-created (or
 *    tombstone-resurrected) by name via the existing add-with-resurrection paths.
 *  - The whole import is wrapped in a transaction so a partial run can't
 *    leave inconsistent state.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: ImportExportRepositoryImpl
    private val session = FakeSessionProvider(TEST_USER_ID)
    private val householdSession = FakeHouseholdSessionProvider(TEST_USER_ID)
    private val clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC)
    private val ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() }

    @Before fun setup() {
        db = createTestDb(seeded = false)
        // Wire the underlying single-purpose repos directly (mirrors how Hilt
        // assembles them in production, but spelled out for a unit test).
        val itemRepo = ItemRepositoryImpl(
            db = db, itemDao = db.itemDao(), xrefDao = db.itemStoreXrefDao(),
            purchaseRecordDao = db.purchaseRecordDao(),
            scoDao = db.storeCategoryOrderDao(),
            storeDao = db.storeDao(),
            ids = ids, clock = clock, session = session,
            householdSession = householdSession,
        )
        val categoryRepo = CategoryRepositoryImpl(
            db = db, dao = db.categoryDao(), itemDao = db.itemDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = ids, clock = clock, session = session,
            householdSession = householdSession,
        )
        val storeRepo = StoreRepositoryImpl(
            db = db, dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(), scoDao = db.storeCategoryOrderDao(),
            ids = ids, clock = clock, session = session,
            householdSession = householdSession,
        )
        repo = ImportExportRepositoryImpl(
            db = db,
            categoryDao = db.categoryDao(),
            storeDao = db.storeDao(),
            itemDao = db.itemDao(),
            categoryRepository = categoryRepo,
            storeRepository = storeRepo,
            itemRepository = itemRepo,
            session = session,
        )
    }

    @After fun tearDown() { db.close() }

    @Test fun `importItemsCsv catches generic Exception from itemRepository_addItem`() = runTest {
        // Replace the real itemRepository with a throwing mock so we exercise
        // the row-loop catch(Exception) branch.
        val throwingItemRepo = io.mockk.mockk<ItemRepository>(relaxed = true) {
            io.mockk.coEvery {
                addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("disk full")
            io.mockk.coEvery { observeAll() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val mockedRepo = ImportExportRepositoryImpl(
            db = db,
            categoryDao = db.categoryDao(),
            storeDao = db.storeDao(),
            itemDao = db.itemDao(),
            categoryRepository = CategoryRepositoryImpl(
                db = db, dao = db.categoryDao(), itemDao = db.itemDao(),
                scoDao = db.storeCategoryOrderDao(),
                ids = ids, clock = clock, session = session,
                householdSession = householdSession,
            ),
            storeRepository = StoreRepositoryImpl(
                db = db, dao = db.storeDao(),
                xrefDao = db.itemStoreXrefDao(), scoDao = db.storeCategoryOrderDao(),
                ids = ids, clock = clock, session = session,
                householdSession = householdSession,
            ),
            itemRepository = throwingItemRepo,
            session = session,
        )

        val result = mockedRepo.importItemsCsv("name\nMilk\n")
        assertThat(result.itemsImported).isEqualTo(0)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("Milk")
        assertThat(result.errors[0]).contains("disk full")
    }

    @Test fun `importCategoriesCsv catches IllegalArgumentException from categoryRepository_addCategory`() = runTest {
        val throwingCategoryRepo = io.mockk.mockk<CategoryRepository>(relaxed = true) {
            io.mockk.coEvery {
                addCategory(any(), any())
            } throws IllegalArgumentException("name too long")
        }
        val mockedRepo = ImportExportRepositoryImpl(
            db = db,
            categoryDao = db.categoryDao(),
            storeDao = db.storeDao(),
            itemDao = db.itemDao(),
            categoryRepository = throwingCategoryRepo,
            storeRepository = StoreRepositoryImpl(
                db = db, dao = db.storeDao(),
                xrefDao = db.itemStoreXrefDao(), scoDao = db.storeCategoryOrderDao(),
                ids = ids, clock = clock, session = session,
                householdSession = householdSession,
            ),
            itemRepository = ItemRepositoryImpl(
                db = db, itemDao = db.itemDao(), xrefDao = db.itemStoreXrefDao(),
                purchaseRecordDao = db.purchaseRecordDao(),
                scoDao = db.storeCategoryOrderDao(),
                storeDao = db.storeDao(),
                ids = ids, clock = clock, session = session,
                householdSession = householdSession,
            ),
            session = session,
        )

        val result = mockedRepo.importCategoriesCsv("name\nVery long name\n")
        assertThat(result.categoriesImported).isEqualTo(0)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("Very long name")
        assertThat(result.errors[0]).contains("name too long")
    }

    @Test fun `importItemsCsv inserts new items, auto-creating their referenced categories and stores`() = runTest {
        val csv = """
            name,category,stores,brand
            Bread,Bakery,"Aldi,Lidl",
            Apples,Produce,Aldi,Granny Smith
        """.trimIndent()

        val result = repo.importItemsCsv(csv)

        assertThat(result.itemsImported).isEqualTo(2)
        assertThat(result.duplicatesSkipped).isEqualTo(0)
        assertThat(result.categoriesImported).isEqualTo(2) // Bakery + Produce
        assertThat(result.storesImported).isEqualTo(2)     // Aldi + Lidl
        assertThat(result.errors).isEmpty()

        val items = db.itemDao().observeAll(TEST_USER_ID).first()
        assertThat(items.map { it.item.name }).containsExactly("Apples", "Bread")
        // Bread is tagged at Aldi + Lidl (sorted alphabetically by xref's joinForItem)
        val bread = items.single { it.item.name == "Bread" }
        assertThat(bread.stores.map { it.name }).containsExactly("Aldi", "Lidl")
    }

    @Test fun `importItemsCsv NEVER modifies an existing alive item with the same name (the user's hard constraint)`() = runTest {
        // Pre-populate: alive item "Milk" tagged at Lidl with brand "Mimosa".
        val lidlId = "store_lidl"
        db.storeDao().upsert(store(lidlId, "Lidl"))
        db.itemDao().upsert(
            Item(
                id = "milk-1", name = "Milk", categoryId = null, notes = null,
                quantity = "1 L", isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                brand = "Mimosa", imageUrl = null, isStaple = false, isPriority = false,
                householdId = TEST_USER_ID,
            ),
        )
        db.itemStoreXrefDao().setStoresForItem(
            itemId = "milk-1", storeIds = setOf(lidlId),
            householdId = TEST_USER_ID, userId = TEST_USER_ID, now = 1L,
        )

        // CSV that tries to "overwrite" Milk with different brand and stores.
        val csv = """
            name,brand,stores
            Milk,DIFFERENT_BRAND,"Aldi,Continente"
            Bread,,Aldi
        """.trimIndent()

        val result = repo.importItemsCsv(csv)

        assertThat(result.itemsImported).isEqualTo(1) // Bread only
        assertThat(result.duplicatesSkipped).isEqualTo(1)  // Milk skipped

        // Existing Milk is byte-for-byte unchanged.
        val milk = db.itemDao().findAnyById(TEST_USER_ID, "milk-1")!!
        assertThat(milk.brand).isEqualTo("Mimosa")
        assertThat(milk.quantity).isEqualTo("1 L")
        // Milk's xref is still only Lidl, NOT Aldi/Continente from the CSV.
        val milkStores = db.itemStoreXrefDao().findForItem("milk-1").map { it.storeId }
        assertThat(milkStores).containsExactly("store_lidl")
    }

    @Test fun `importItemsCsv duplicate-skip is case-insensitive`() = runTest {
        // Pre-populate "Milk" (capitalized).
        db.itemDao().upsert(
            Item(
                id = "milk-1", name = "Milk", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                brand = "ExistingBrand", imageUrl = null,
                isStaple = false, isPriority = false,
                householdId = TEST_USER_ID,
            ),
        )

        // CSV uses "milk" (lowercase) — same item, different case. Must be
        // treated as a duplicate, not a new row, so the existing one is
        // preserved unchanged. Case-insensitive matching is intentional;
        // dropping the COLLATE NOCASE in ItemDao.findByName must fail this test.
        val result = repo.importItemsCsv("name,brand\nmilk,DIFFERENT_BRAND\n")

        assertThat(result.itemsImported).isEqualTo(0)
        assertThat(result.duplicatesSkipped).isEqualTo(1)
        // Pre-existing alive Milk is unchanged — the brand was NOT overwritten.
        assertThat(db.itemDao().findAnyById(TEST_USER_ID, "milk-1")!!.brand)
            .isEqualTo("ExistingBrand")
        // No second "Milk" row was created.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first()).hasSize(1)
    }

    @Test fun `importItemsCsv resurrects a tombstoned category by name`() = runTest {
        // Tombstoned "Bakery" with a stable id.
        val bakeryId = "tombstoned-bakery"
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = bakeryId, name = "Bakery", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = 100L,
                householdId = TEST_USER_ID,
            ),
        )

        repo.importItemsCsv("name,category\nCroissant,Bakery\n")

        // The same row is back alive, NOT a brand-new id.
        val bakery = db.categoryDao().findAnyById(TEST_USER_ID, bakeryId)!!
        assertThat(bakery.deletedAt).isNull()
        // The new item references this resurrected category id, not a duplicate.
        val croissant = db.itemDao().observeAll(TEST_USER_ID).first().single { it.item.name == "Croissant" }
        assertThat(croissant.item.categoryId).isEqualTo(bakeryId)
    }

    @Test fun `importItemsCsv reuses an alive category instead of duplicating it`() = runTest {
        val bakeryId = "alive-bakery"
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = bakeryId, name = "Bakery", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID,
            ),
        )

        val result = repo.importItemsCsv("name,category\nCroissant,Bakery\nBaguette,Bakery\n")

        // Existing Bakery reused for both items; no new categories created.
        assertThat(result.categoriesImported).isEqualTo(0)
        val all = db.categoryDao().observeAll(TEST_USER_ID, includeArchived = false).first()
        assertThat(all.map { it.name }).containsExactly("Bakery")
        val items = db.itemDao().observeAll(TEST_USER_ID).first()
        // Both items must exist AND must link to the existing Bakery row;
        // the .all check on an empty list passes vacuously, so we assert size
        // explicitly. Without the alive-category short-circuit in
        // resolveCategory, addCategory throws on duplicate-name and items end
        // up with categoryId=null — this asserts that's NOT what happens.
        assertThat(items).hasSize(2)
        assertThat(items.map { it.item.categoryId }).containsExactly(bakeryId, bakeryId)
    }

    @Test fun `importItemsCsv with a header-error CSV is a clean no-op`() = runTest {
        val before = db.itemDao().observeAll(TEST_USER_ID).first().size
        val result = repo.importItemsCsv("badheader\nfoo\n")
        assertThat(result.itemsImported).isEqualTo(0)
        assertThat(result.errors).isNotEmpty()
        // Nothing got written.
        assertThat(db.itemDao().observeAll(TEST_USER_ID).first().size).isEqualTo(before)
    }

    @Test fun `importCategoriesCsv resurrects tombstones and reuses alive duplicates`() = runTest {
        val tombId = "tomb-bakery"
        val aliveId = "alive-produce"
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = tombId, name = "Bakery", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = 100L,
                householdId = TEST_USER_ID,
            ),
        )
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = aliveId, name = "Produce", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID,
            ),
        )

        val csv = "name\nBakery\nProduce\nBeverages\n"
        val result = repo.importCategoriesCsv(csv)

        // Bakery resurrected (counted as imported); Produce already alive (skipped);
        // Beverages newly inserted.
        assertThat(result.categoriesImported).isEqualTo(2)
        // Produce was the alive duplicate — it must show up in the rolled-up
        // duplicate-skip count so the snackbar reads correctly. Pre-v0.5.3 the
        // categories-import path silently `continue`-d on duplicates without
        // tracking the count; the snackbar said "Skipped 0" even with a
        // genuine duplicate. Pin the new behavior so it doesn't regress.
        assertThat(result.duplicatesSkipped).isEqualTo(1)
        assertThat(db.categoryDao().findAnyById(TEST_USER_ID, tombId)!!.deletedAt).isNull()
        assertThat(db.categoryDao().findAnyById(TEST_USER_ID, aliveId)!!.deletedAt).isNull()
        assertThat(
            db.categoryDao().observeAll(TEST_USER_ID, includeArchived = false).first().map { it.name },
        ).containsExactly("Bakery", "Beverages", "Produce")
    }

    @Test fun `exportItemsCsv emits one row per alive item with header in the documented order`() = runTest {
        // Set up two items, one tagged at Lidl + Aldi.
        db.storeDao().upsert(store("store_lidl", "Lidl"))
        db.storeDao().upsert(store("store_aldi", "Aldi"))
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = "cat_dairy", name = "Dairy", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = null,
                householdId = TEST_USER_ID,
            ),
        )
        db.itemDao().upsert(
            Item(
                id = "i_milk", name = "Milk", categoryId = "cat_dairy", notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = TEST_USER_ID, createdAt = 1L, updatedAt = 1L, deletedAt = null,
                brand = "Mimosa",
                householdId = TEST_USER_ID,
            ),
        )
        db.itemStoreXrefDao().setStoresForItem(
            itemId = "i_milk", storeIds = setOf("store_lidl", "store_aldi"),
            householdId = TEST_USER_ID, userId = TEST_USER_ID, now = 1L,
        )

        val csv = repo.exportItemsCsv()

        val firstLine = csv.lineSequence().first()
        assertThat(firstLine).isEqualTo("name,category,stores,brand,notes,quantity,isStaple,isPriority")
        assertThat(csv).contains("Milk")
        assertThat(csv).contains("Dairy")
        // Stores joined into a single quoted field
        assertThat(csv).contains("\"Aldi,Lidl\"")
    }

    @Test fun `exportCategoriesCsv emits header + one row per alive category, skipping tombstones`() = runTest {
        // Two alive categories + one tombstoned. Export should include only the alive ones.
        listOf(
            "alive-bakery" to "Bakery",
            "alive-produce" to "Produce",
        ).forEach { (id, name) ->
            db.categoryDao().upsert(
                com.storehop.app.data.entity.Category(
                    id = id, name = name, nameKey = null, icon = null,
                    isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = TEST_USER_ID,
                ),
            )
        }
        db.categoryDao().upsert(
            com.storehop.app.data.entity.Category(
                id = "tomb", name = "Tombstoned", nameKey = null, icon = null,
                isArchived = false, isSeeded = false, userId = TEST_USER_ID,
                createdAt = 1L, updatedAt = 1L, deletedAt = 100L,
                householdId = TEST_USER_ID,
            ),
        )

        val csv = repo.exportCategoriesCsv()

        // Header row + 2 data rows + trailing newline → 3 lines.
        val lines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        assertThat(lines.first()).isEqualTo("name,icon")
        assertThat(lines).hasSize(3)
        assertThat(csv).contains("Bakery")
        assertThat(csv).contains("Produce")
        assertThat(csv).doesNotContain("Tombstoned")
    }

    @Test fun `undoImport soft-deletes exactly the ids the import returned`() = runTest {
        val result = repo.importItemsCsv(
            """
            name,category,stores
            X,NewCat,NewStore
            """.trimIndent(),
        )
        assertThat(result.itemsImported).isEqualTo(1)
        assertThat(result.categoriesImported).isEqualTo(1)
        assertThat(result.storesImported).isEqualTo(1)

        repo.undoImport(result)

        // All three new rows are tombstoned.
        assertThat(
            db.itemDao().findAnyById(TEST_USER_ID, result.importedItemIds.single())!!.deletedAt,
        ).isNotNull()
        assertThat(
            db.categoryDao().findAnyById(TEST_USER_ID, result.importedCategoryIds.single())!!.deletedAt,
        ).isNotNull()
        assertThat(
            db.storeDao().findAnyById(TEST_USER_ID, result.importedStoreIds.single())!!.deletedAt,
        ).isNotNull()
    }

    private fun store(id: String, name: String) = Store(
        id = id, name = name, colorArgb = null,
        isArchived = false, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
        householdId = TEST_USER_ID,
    )
}
