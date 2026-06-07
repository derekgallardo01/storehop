package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.util.FakeHouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class StoreRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: StoreRepositoryImpl

    @Before fun setup() {
        // Seeded so the test can attempt to add a store with the same name as a seeded one.
        db = createTestDb(seeded = true)
        repo = StoreRepositoryImpl(
            db = db,
            dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            // Same sentinel as the seed pack so the unique-(userId,name) index applies.
            session = FakeSessionProvider("local-only"),
            // v0.7.0: household scope mirrors userId for single-member households.
            householdSession = FakeHouseholdSessionProvider("local-only"),
        )
    }

    @After fun tearDown() { db.close() }

    @Test fun `addStore appends a user store alongside the seeded ones`() = runTest {
        repo.addStore("Mercadona", colorArgb = null)
        val all = repo.observeAll(includeArchived = false).first()
        // 14 seeded + 1 user = 15.
        assertThat(all).hasSize(15)
        val mercadona = all.single { it.name == "Mercadona" }
        assertThat(mercadona.isSeeded).isFalse()
    }

    @Test fun `addStore rejects a duplicate name (case-insensitive) with IllegalArgumentException`() {
        // Room's @Upsert silently no-ops on a non-PK unique-index conflict, so
        // StoreRepositoryImpl detects the collision explicitly. This test pins
        // that contract: same name as a seeded store, mixed case, both rejected.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("Lidl", colorArgb = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("lidl", colorArgb = null) }
        }
        // No phantom row was created.
        kotlinx.coroutines.runBlocking {
            val all = repo.observeAll(includeArchived = false).first()
            assertThat(all.count { it.name.equals("Lidl", ignoreCase = true) }).isEqualTo(1)
        }
    }

    @Test fun `NONA-2 addStore handles a 10000-char name without truncation or crash`() = runTest {
        val giant = "X".repeat(10_000)
        val id = repo.addStore(name = giant, colorArgb = null)
        val live = repo.observeAll(includeArchived = false).first()
            .single { it.id == id }
        assertThat(live.name).isEqualTo(giant)
        assertThat(live.name.length).isEqualTo(10_000)
    }

    @Test fun `NONA-3 addStore name with quotes apostrophes and SQL meta characters round-trips`() = runTest {
        // SQLite parameterized queries protect against injection. This locks in
        // that the repo isn't doing string concatenation anywhere we'd regress.
        val tricky = "Joe's \"Mart\"; DROP TABLE stores; -- 100% safe"
        val id = repo.addStore(name = tricky, colorArgb = null)
        // Verify the row exists, contains the literal string, AND that the stores
        // table is intact (DROP didn't run).
        val live = repo.observeAll(includeArchived = false).first()
        assertThat(live.single { it.id == id }.name).isEqualTo(tricky)
        // Seed pack still intact => DROP TABLE didn't execute.
        assertThat(live.count { it.isSeeded }).isEqualTo(14)
    }

    @Test fun `addStore rejects an empty or whitespace-only name`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("", colorArgb = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("   ", colorArgb = null) }
        }
    }

    @Test fun `archived seeded store is excluded from observeAll(false) and listed by observeAll(true)`() = runTest {
        repo.setArchived("store_lidl", archived = true)
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_lidl")
        assertThat(repo.observeAll(includeArchived = true).first().map { it.id })
            .contains("store_lidl")
    }

    @Test fun `addStore resurrects a previously soft-deleted store with the same name (preserves id for sync)`() = runTest {
        repo.softDelete("store_lidl")
        // Resurrection path: addStore returns the ORIGINAL id, not a fresh UUID.
        // Re-using the id is correct for sync semantics (other devices see the
        // tombstone clear, not a delete-and-create which would conflict with
        // their own tombstones).
        val resurrectedId = repo.addStore("Lidl", colorArgb = 0xFF112233.toInt())
        assertThat(resurrectedId).isEqualTo("store_lidl")

        val live = repo.observeAll(includeArchived = false).first()
            .filter { it.name == "Lidl" }
        assertThat(live).hasSize(1)
        // The resurrected row keeps its seeded provenance (it's still the seeded
        // Lidl, just temporarily deleted then restored), and picks up the new color.
        assertThat(live.single().id).isEqualTo("store_lidl")
        assertThat(live.single().isSeeded).isTrue()
        assertThat(live.single().colorArgb).isEqualTo(0xFF112233.toInt())
        assertThat(live.single().deletedAt).isNull()
    }

    @Test fun `addStore still rejects a duplicate when the existing row is live (not tombstoned)`() {
        // Sanity-check that the resurrection path doesn't accidentally also resurrect
        // live rows -- the live-duplicate behavior must still throw.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("Lidl", colorArgb = null) }
        }
    }

    @Test fun `concurrent addStore with the same name only succeeds once`() = runTest {
        // Schema v6 dropped the DB-level UNIQUE constraint on (userId, name)
        // (see Migrations.kt's MIGRATION_5_6 rationale). That puts the full
        // burden of single-name uniqueness on the application layer, which
        // means addStore's findAnyByName + upsert pair MUST execute inside a
        // single serialized transaction. Without the withTransaction wrap,
        // two coroutines could both pass findAnyByName == null and then both
        // insert, leaving two alive rows with the same name (pre-v6 the
        // second insert at least silently no-opped via Room's @Upsert
        // ignore-on-conflict; post-v6 there's no DB safety net). Stress
        // the serialization here:
        val results: List<Result<String>> = coroutineScope {
            val deferreds: List<Deferred<Result<String>>> = (0 until 100).map {
                async(Dispatchers.Default) {
                    runCatching { repo.addStore("RaceStore", colorArgb = null) }
                }
            }
            deferreds.awaitAll()
        }
        val successes = results.count { it.isSuccess }
        val failures = results.count { it.isFailure }

        // Octa-check bumped from 8 to 100 to make sure the lock truly serializes
        // under realistic contention rather than just a small lucky window.
        assertThat(successes).isEqualTo(1)
        assertThat(failures).isEqualTo(99)
        val ours = repo.observeAll(includeArchived = false).first()
            .filter { it.name == "RaceStore" }
        assertThat(ours).hasSize(1)
    }

    @Test fun `softDelete cascades to xrefs and store_category_orders for that store`() = runTest {
        // Seed: Lidl already has 13 SCO rows. Tag two new items to Lidl too.
        listOf("milk", "bread").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = null, notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = id, storeId = "store_lidl", userId = "local-only",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
        }
        // Pre-conditions: live xrefs and live SCO rows for Lidl exist.
        assertThat(db.itemStoreXrefDao().findForItem("milk")).isNotEmpty()
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isNotEmpty()

        repo.softDelete("store_lidl")

        // Lidl is gone from observeAll(includeArchived=false).
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_lidl")
        // Cascade tombstoned the xrefs (findForItem filters deletedAt IS NULL).
        assertThat(db.itemStoreXrefDao().findForItem("milk")).isEmpty()
        assertThat(db.itemStoreXrefDao().findForItem("bread")).isEmpty()
        // Cascade tombstoned the SCO rows (findForStore filters deletedAt IS NULL).
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isEmpty()
        // Items themselves are NOT deleted -- they're just untagged from Lidl.
        // (This is intentional: a user might re-add the store later, and the item
        //  data should outlive the store-tagging.)
        assertThat(db.itemDao().observeNeeded("local-only").first().map { it.id })
            .containsAtLeast("milk", "bread")
    }

    @Test fun `rename and setColor and setArchived re-flag pendingSync=1 after a previous push`() = runTest {
        // Same class of bug as ItemDaoTest's pendingSync regression: a row that
        // was previously pushed must be re-flagged dirty by any subsequent
        // local mutation, including ones that go through the repository's
        // `dao.upsert(current.copy(...))` path (which would otherwise preserve
        // the prior `pendingSync = 0`).
        val newId = repo.addStore("Mercadona", colorArgb = null)
        db.storeDao().markPushed("local-only", newId)
        // Sanity: post-push, clean.
        fun pendingSyncOf(id: String): Int = db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT pendingSync FROM stores WHERE id = '$id'"))
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertThat(pendingSyncOf(newId)).isEqualTo(0)

        repo.rename(newId, "Mercadona Express")
        assertThat(pendingSyncOf(newId)).isEqualTo(1)

        db.storeDao().markPushed("local-only", newId)
        repo.setColor(newId, colorArgb = 0xFF112233.toInt())
        assertThat(pendingSyncOf(newId)).isEqualTo(1)

        db.storeDao().markPushed("local-only", newId)
        repo.setArchived(newId, archived = true)
        assertThat(pendingSyncOf(newId)).isEqualTo(1)
    }

    @Test fun `rename rejects a duplicate name (case-insensitive) of an alive store with IllegalArgumentException`() {
        // Mirror of CategoryRepositoryImplTest's rename-duplicate test. The
        // unique index on (userId, name) covers tombstones too -- the repo
        // guard surfaces a clear error before the upsert hits a constraint.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("store_lidl", "Aldi") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("store_lidl", "aldi") }
        }
        kotlinx.coroutines.runBlocking {
            assertThat(db.storeDao().findById("local-only", "store_lidl")!!.name)
                .isEqualTo("Lidl")
        }
    }

    @Test fun `rename succeeds when the target name is held only by a tombstoned store`() = runTest {
        // Mirror of the categories tombstone-reuse test. Schema v6 dropped
        // the UNIQUE index, so a tombstoned "Aldi" doesn't block renaming
        // Lidl -> Aldi. The deleted Aldi row stays tombstoned and the user
        // sees only one alive "Aldi" (the renamed Lidl).
        repo.softDelete("store_aldi")
        repo.rename("store_lidl", "Aldi")

        val lidl = db.storeDao().findById("local-only", "store_lidl")!!
        assertThat(lidl.name).isEqualTo("Aldi")
        val visible = repo.observeAll(includeArchived = false).first()
            .filter { it.name.equals("Aldi", ignoreCase = true) }
        assertThat(visible).hasSize(1)
        assertThat(visible.single().id).isEqualTo("store_lidl")
    }

    @Test fun `rename allows a case-only change of the row's own name`() = runTest {
        repo.rename("store_lidl", "lidl")
        assertThat(db.storeDao().findById("local-only", "store_lidl")!!.name)
            .isEqualTo("lidl")
    }

    @Test fun `rename rejects an empty or whitespace-only name`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("store_lidl", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("store_lidl", "   ") }
        }
    }

    @Test fun `addStore resurrection path re-flags pendingSync=1 on the resurrected row`() = runTest {
        // Soft-deleting a previously-pushed store and then re-adding it via
        // resurrection must re-flag dirty so the cloud sees the tombstone clear.
        repo.softDelete("store_lidl")
        db.storeDao().markPushed("local-only", "store_lidl") // Simulate the soft-delete already synced.
        // Now re-add. This goes through the resurrect path (existing.copy()).
        val resurrected = repo.addStore("Lidl", colorArgb = null)
        assertThat(resurrected).isEqualTo("store_lidl")
        val pending = db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT pendingSync FROM stores WHERE id = 'store_lidl'"))
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertThat(pending).isEqualTo(1)
    }

    @Test fun `seeded stores observe in the order they appear in the seed pack`() = runTest {
        // The seeder assigns displayOrder = JSON list index. observeAll sorts
        // by displayOrder ASC, so the picker's initial order matches the seed.
        val all = repo.observeAll(includeArchived = false).first()
        // displayOrder values are 0..n-1 with no gaps for the seeded set.
        assertThat(all.map { it.displayOrder }).isInOrder()
        assertThat(all.first().displayOrder).isEqualTo(0)
        assertThat(all.last().displayOrder).isEqualTo(all.size - 1)
    }

    @Test fun `addStore appends new stores to the bottom (max displayOrder + 1)`() = runTest {
        val before = repo.observeAll(includeArchived = false).first()
        val maxBefore = before.maxOf { it.displayOrder }

        repo.addStore("Mercadona", colorArgb = null)

        val after = repo.observeAll(includeArchived = false).first()
        val mercadona = after.single { it.name == "Mercadona" }
        // New store sits one slot below the previous max -- end of the picker.
        assertThat(mercadona.displayOrder).isEqualTo(maxBefore + 1)
        // Existing rows weren't shifted.
        assertThat(after.filter { it.id != mercadona.id }.map { it.id })
            .isEqualTo(before.map { it.id })
    }

    @Test fun `reorderStores rewrites displayOrder to match the supplied list`() = runTest {
        val initial = repo.observeAll(includeArchived = false).first()
        // Reverse the seed-pack order. Pass every live id to mimic what the
        // drag UI sends (the full visible set).
        val reversed = initial.map { it.id }.reversed()

        repo.reorderStores(reversed)

        val after = repo.observeAll(includeArchived = false).first()
        // observeAll sorts by displayOrder ASC, so the post-reorder list IS
        // the reversed list.
        assertThat(after.map { it.id }).isEqualTo(reversed)
        // displayOrder values are 0..n-1 dense.
        assertThat(after.map { it.displayOrder })
            .isEqualTo((0 until after.size).toList())
    }

    @Test fun `reorderStores re-flags pendingSync on every reordered row`() = runTest {
        val ids = repo.observeAll(includeArchived = false).first().map { it.id }
        // Mark them all clean (simulate post-push state) before the reorder.
        ids.forEach { db.storeDao().markPushed("local-only", it) }

        repo.reorderStores(ids.reversed())

        // Every store the repo touched is dirty again.
        ids.forEach { id ->
            val pending = db.openHelper.readableDatabase
                .query(androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT pendingSync FROM stores WHERE id = '$id'"))
                .use { c -> c.moveToFirst(); c.getInt(0) }
            assertThat(pending).isEqualTo(1)
        }
    }

    @Test fun `reorderStores does not touch stores absent from the supplied list`() = runTest {
        val initial = repo.observeAll(includeArchived = false).first()
        // Add a fresh store and capture its displayOrder. Then issue a reorder
        // that explicitly excludes its id.
        val keepId = repo.addStore("UntouchedStore", colorArgb = null)
        val keepBefore = repo.observeById(keepId).first()!!
        db.storeDao().markPushed("local-only", keepId)

        repo.reorderStores(initial.map { it.id })

        val keepAfter = repo.observeById(keepId).first()!!
        assertThat(keepAfter.displayOrder).isEqualTo(keepBefore.displayOrder)
        // pendingSync also untouched -- no spurious sync write for the row.
        val pending = db.openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT pendingSync FROM stores WHERE id = '$keepId'"))
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertThat(pending).isEqualTo(0)
    }

    @Test fun `undoSoftDelete restores the store row and the cascade of xrefs and SCO rows`() = runTest {
        // Build state: tag two items to Lidl and verify the cascade tombstones,
        // then undo and verify everything comes back exactly as it was.
        listOf("milk", "bread").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = null, notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = id, storeId = "store_lidl", userId = "local-only",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
        }
        val xrefsBefore = db.itemStoreXrefDao().findForItem("milk").size + db.itemStoreXrefDao().findForItem("bread").size
        val scoBefore = db.storeCategoryOrderDao().findForStore("store_lidl").size
        assertThat(xrefsBefore).isAtLeast(2)
        assertThat(scoBefore).isAtLeast(1)

        repo.softDelete("store_lidl")

        // Sanity: store + cascade are tombstoned.
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_lidl")
        assertThat(db.itemStoreXrefDao().findForItem("milk")).isEmpty()
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isEmpty()

        repo.undoSoftDelete("store_lidl")

        // Store comes back live.
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .contains("store_lidl")
        // Both Lidl xrefs come back.
        assertThat(db.itemStoreXrefDao().findForItem("milk").map { it.storeId }).contains("store_lidl")
        assertThat(db.itemStoreXrefDao().findForItem("bread").map { it.storeId }).contains("store_lidl")
        // SCO rows for Lidl come back.
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).hasSize(scoBefore)
    }

    @Test fun `undoSoftDelete is a no-op when the store isn't tombstoned`() = runTest {
        // Lidl is live (seeded). undo on a live row should leave state unchanged.
        val before = repo.observeById("store_lidl").first()!!
        repo.undoSoftDelete("store_lidl")
        val after = repo.observeById("store_lidl").first()!!
        assertThat(after.deletedAt).isNull()
        // updatedAt is unchanged since no row was touched.
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
    }

    @Test fun `undoSoftDelete only restores rows tombstoned at the same instant`() = runTest {
        // Tombstone Lidl, then later (different `now`) tombstone Continente.
        // Undoing Lidl should NOT restore Continente's xrefs/SCOs.
        listOf("milk").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = null, notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = id, storeId = "store_lidl", userId = "local-only",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = id, storeId = "store_continente", userId = "local-only",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                    householdId = "local-only",
                ),
            )
        }
        // The repo is built with a fixed clock at 50_000L, so both deletes use the same ts.
        // Build a second repo at a different clock for Continente's delete.
        val repoLater = StoreRepositoryImpl(
            db = db,
            dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(99_999L), ZoneOffset.UTC),
            session = FakeSessionProvider("local-only"),
            householdSession = FakeHouseholdSessionProvider("local-only"),
        )

        repo.softDelete("store_lidl")              // ts = 50_000L
        repoLater.softDelete("store_continente")   // ts = 99_999L

        repo.undoSoftDelete("store_lidl")

        // Lidl restored.
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .contains("store_lidl")
        // Continente still tombstoned (different `deletedAt`, undoSoftDelete
        // filters by exact instant).
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_continente")
        // Continente's xref still tombstoned.
        val milkXrefs = db.itemStoreXrefDao().findForItem("milk").map { it.storeId }
        assertThat(milkXrefs).contains("store_lidl")
        assertThat(milkXrefs).doesNotContain("store_continente")
    }

    @Test fun `setArchived softDelete and rename are no-ops for stores the session does not own`() = runTest {
        // Build a separate repo whose session is some-other-user, then try to
        // mutate "store_lidl" (which is owned by local-only via the seed).
        val otherRepo = StoreRepositoryImpl(
            db = db,
            dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider("some-other-user"),
            // Different household from the seed too: ensures the "owned by
            // another user" isolation test exercises the household filter,
            // not just the userId filter (which no longer scopes access).
            householdSession = FakeHouseholdSessionProvider("some-other-user"),
        )

        otherRepo.setArchived("store_lidl", archived = true)
        otherRepo.softDelete("store_lidl")
        otherRepo.rename("store_lidl", "Hijacked")

        // From the original (correct) owner's perspective, store_lidl is unchanged.
        val lidl = repo.observeById("store_lidl").first()
        assertThat(lidl).isNotNull()
        assertThat(lidl!!.name).isEqualTo("Lidl")
        assertThat(lidl.isArchived).isFalse()
        assertThat(lidl.deletedAt).isNull()
    }

    // ---- v0.9 one-off store flag --------------------------------------

    @Test fun `addStore with isOneOff=true persists the flag`() = runTest {
        val id = repo.addStore("Online (One Off)", colorArgb = null, isOneOff = true)
        val store = repo.observeById(id).first()
        assertThat(store).isNotNull()
        assertThat(store!!.isOneOff).isTrue()
    }

    @Test fun `addStore defaults isOneOff to false when not specified`() = runTest {
        val id = repo.addStore("Plain Store", colorArgb = null)
        val store = repo.observeById(id).first()
        assertThat(store!!.isOneOff).isFalse()
    }

    @Test fun `setOneOff flips the flag both ways and bumps pendingSync`() = runTest {
        val id = repo.addStore("Flippy", colorArgb = null, isOneOff = false)
        // First, mark pendingSync = false to verify setOneOff flips it back.
        val initial = db.storeDao().findById("local-only", id)!!
        db.storeDao().upsert(initial.copy(pendingSync = false))

        repo.setOneOff(id, isOneOff = true)
        val afterOn = db.storeDao().findById("local-only", id)!!
        assertThat(afterOn.isOneOff).isTrue()
        assertThat(afterOn.pendingSync).isTrue()

        // Reset pendingSync, then flip back to false.
        db.storeDao().upsert(afterOn.copy(pendingSync = false))
        repo.setOneOff(id, isOneOff = false)
        val afterOff = db.storeDao().findById("local-only", id)!!
        assertThat(afterOff.isOneOff).isFalse()
        assertThat(afterOff.pendingSync).isTrue()
    }

    @Test fun `setOneOff is idempotent and does not bump pendingSync when value is unchanged`() = runTest {
        val id = repo.addStore("Stable", colorArgb = null, isOneOff = true)
        // Clear pendingSync so we can detect whether setOneOff (with the same value) writes.
        val initial = db.storeDao().findById("local-only", id)!!
        db.storeDao().upsert(initial.copy(pendingSync = false))

        repo.setOneOff(id, isOneOff = true)
        val after = db.storeDao().findById("local-only", id)!!
        assertThat(after.pendingSync).isFalse()  // no write -> still false
    }
}
