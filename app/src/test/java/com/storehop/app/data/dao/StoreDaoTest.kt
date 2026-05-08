package com.storehop.app.data.dao

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.entity.Store
import com.storehop.app.testing.TEST_USER_ID
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Direct DAO coverage for the bits the repository tests don't hit head-on:
 * the resurrect-on-re-add lookup path (findAnyByName / restoreFromTombstone)
 * and the displayOrder allocation arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
class StoreDaoTest {

    private lateinit var db: StorehopDatabase
    private lateinit var dao: StoreDao

    @Before fun setup() {
        db = createTestDb(seeded = false)
        dao = db.storeDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `nextDisplayOrder returns 0 when the user has no live stores`() = runTest {
        assertThat(dao.nextDisplayOrder(TEST_USER_ID)).isEqualTo(0)
    }

    @Test fun `nextDisplayOrder returns MAX(displayOrder) + 1`() = runTest {
        dao.upsert(store("a", "Aldi", displayOrder = 0))
        dao.upsert(store("b", "Lidl", displayOrder = 3))
        dao.upsert(store("c", "Pingo Doce", displayOrder = 1))

        assertThat(dao.nextDisplayOrder(TEST_USER_ID)).isEqualTo(4)
    }

    @Test fun `nextDisplayOrder ignores tombstoned rows`() = runTest {
        // Tombstoned at displayOrder=10. Live row at displayOrder=2 should
        // win, giving nextDisplayOrder = 3. Without the deletedAt filter
        // the user would get a gap (displayOrder=11) and visible bugs in
        // the picker after a soft-delete.
        dao.upsert(store("dead", "Aldi", displayOrder = 10, deletedAt = 99L))
        dao.upsert(store("live", "Lidl", displayOrder = 2))

        assertThat(dao.nextDisplayOrder(TEST_USER_ID)).isEqualTo(3)
    }

    @Test fun `findByName ignores tombstones`() = runTest {
        dao.upsert(store("dead", "Lidl", deletedAt = 99L))

        assertThat(dao.findByName(TEST_USER_ID, "Lidl")).isNull()
    }

    @Test fun `findAnyByName returns tombstones for the resurrect path`() = runTest {
        // The resurrect-on-re-add codepath needs to find a soft-deleted row
        // with the same name so it can revive the original row (preserving
        // its id for sync) instead of inserting a brand-new row with a
        // different id. findAnyByName MUST return tombstones for that to
        // work. (Pre-v6 the unique index also enforced this from below by
        // rejecting duplicate inserts; post-v6 it's purely an application
        // contract -- duplicates would be silently created if addCategory /
        // addStore ever skipped the lookup.)
        dao.upsert(store("dead", "Lidl", deletedAt = 99L))

        val found = dao.findAnyByName(TEST_USER_ID, "Lidl")
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo("dead")
        assertThat(found.deletedAt).isEqualTo(99L)
    }

    @Test fun `findByName is case-insensitive`() = runTest {
        dao.upsert(store("a", "Lidl"))
        assertThat(dao.findByName(TEST_USER_ID, "lidl")).isNotNull()
        assertThat(dao.findByName(TEST_USER_ID, "LIDL")).isNotNull()
    }

    @Test fun `restoreFromTombstone clears deletedAt and re-flags pendingSync`() = runTest {
        dao.upsert(store("dead", "Lidl", deletedAt = 99L))
        // Mark the dead row pendingSync=0 to verify restore re-flags it.
        dao.markPushed(TEST_USER_ID, "dead")

        dao.restoreFromTombstone(TEST_USER_ID, "dead", now = 200L)

        val restored = dao.findById(TEST_USER_ID, "dead")
        assertThat(restored).isNotNull()
        assertThat(restored!!.deletedAt).isNull()
        assertThat(restored.updatedAt).isEqualTo(200L)
        assertThat(restored.pendingSync).isTrue()
    }

    @Test fun `observeAll filters out tombstoned and (by default) archived stores`() = runTest {
        dao.upsert(store("a", "Aldi"))
        dao.upsert(store("b", "Bolt", isArchived = true))
        dao.upsert(store("c", "Continente", deletedAt = 99L))

        val live = dao.observeAll(TEST_USER_ID, includeArchived = false).first()
        assertThat(live.map { it.id }).containsExactly("a")

        val withArchived = dao.observeAll(TEST_USER_ID, includeArchived = true).first()
        assertThat(withArchived.map { it.id }).containsExactly("a", "b")
        // Tombstoned never returned regardless of includeArchived.
        assertThat(withArchived.any { it.id == "c" }).isFalse()
    }

    @Test fun `observePendingPush returns only pendingSync = 1 rows`() = runTest {
        dao.upsert(store("a", "Aldi")) // pendingSync defaults to true
        dao.upsert(store("b", "Bolt"))
        dao.markPushed(TEST_USER_ID, "a") // flip a to clean

        val pending = dao.observePendingPush(TEST_USER_ID).first()
        assertThat(pending.map { it.id }).containsExactly("b")
    }

    private fun store(
        id: String,
        name: String,
        displayOrder: Int = 0,
        isArchived: Boolean = false,
        deletedAt: Long? = null,
    ) = Store(
        id = id, name = name, colorArgb = null,
        isArchived = isArchived, isSeeded = false, userId = TEST_USER_ID,
        createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt,
        displayOrder = displayOrder,
    )
}
