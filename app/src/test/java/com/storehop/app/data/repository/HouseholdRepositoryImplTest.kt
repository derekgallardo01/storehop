package com.storehop.app.data.repository

import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.PullWriteDao
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.util.FakeHouseholdSessionProvider
import com.storehop.app.data.util.HouseholdSwitcher
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.TEST_USER_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pins HouseholdRepositoryImpl's invite-generate/accept/leave flow without
 * spinning up real Firestore. Mocks every wire-touching collaborator so the
 * tests are pure JVM.
 */
class HouseholdRepositoryImplTest {

    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC)
    private val firestore: FirebaseFirestore = mockk(relaxed = true)
    private val householdMemberDao: HouseholdMemberDao = mockk(relaxed = true)
    private val pullWriteDao: PullWriteDao = mockk(relaxed = true)
    private val userSession = FakeSessionProvider(TEST_USER_ID)
    private val householdSession = FakeHouseholdSessionProvider(TEST_USER_ID)
    private val householdSwitcher: HouseholdSwitcher = mockk(relaxed = true)

    private val repo by lazy {
        HouseholdRepositoryImpl(
            firestore = firestore,
            householdMemberDao = householdMemberDao,
            pullWriteDao = pullWriteDao,
            userSession = userSession,
            householdSession = householdSession,
            householdSwitcher = householdSwitcher,
            clock = fixedClock,
        )
    }

    @Test fun `generateInvite writes a token to invites collection with expiresAt = now + 24h`() = runTest {
        val docRef: DocumentReference = mockk { coEvery { set(any()) } returns Tasks.forResult(null) }
        val collRef: CollectionReference = mockk { every { document(any<String>()) } returns docRef }
        every { firestore.collection("invites") } returns collRef

        val invite = repo.generateInvite()

        assertThat(invite.token).hasLength(8)
        // Crockford base32 (excludes 0/O/1/I/L) so the alphabet here is the
        // matching one.
        assertThat(invite.token).matches("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]+$")
        assertThat(invite.expiresAt).isEqualTo(50_000L + 24L * 60 * 60 * 1000)
        coVerify(exactly = 1) { docRef.set(any()) }
    }

    @Test fun `acceptInvite returns NotFound when the token doesnt resolve to a doc`() = runTest {
        val snap: DocumentSnapshot = mockk { every { exists() } returns false }
        stubInviteGet(snap)

        val result = repo.acceptInvite("MISSING1")
        assertThat(result).isEqualTo(InviteResult.NotFound)
        // No local writes when the invite doc isn't found.
        coVerify(exactly = 0) { pullWriteDao.wipeAllForHousehold(any()) }
        coVerify(exactly = 0) { householdSwitcher.switchToHousehold(any()) }
    }

    @Test fun `acceptInvite returns Expired when expiresAt is in the past`() = runTest {
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { getLong("expiresAt") } returns 1L
            every { getBoolean("accepted") } returns false
            every { getString("householdId") } returns "mike-hid"
        }
        stubInviteGet(snap)

        val result = repo.acceptInvite("OLDCODEX")
        assertThat(result).isEqualTo(InviteResult.Expired)
    }

    @Test fun `acceptInvite returns AlreadyUsed when accepted = true`() = runTest {
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { getLong("expiresAt") } returns 999_999_999_999L
            every { getBoolean("accepted") } returns true
            every { getString("householdId") } returns "mike-hid"
        }
        stubInviteGet(snap)

        val result = repo.acceptInvite("USEDCODE")
        assertThat(result).isEqualTo(InviteResult.AlreadyUsed)
    }

    @Test fun `acceptInvite happy path wipes prior household, inserts new membership, and switches`() = runTest {
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { getLong("expiresAt") } returns 999_999_999_999L
            every { getBoolean("accepted") } returns false
            every { getString("householdId") } returns "mike-hid"
        }
        val updateSlot = slot<Map<String, Any?>>()
        val inviteDoc: DocumentReference = mockk {
            coEvery { get() } returns Tasks.forResult(snap)
            coEvery { update(capture(updateSlot)) } returns Tasks.forResult(null)
        }
        val invitesColl: CollectionReference = mockk { every { document(any<String>()) } returns inviteDoc }
        every { firestore.collection("invites") } returns invitesColl

        val memberSlot = slot<HouseholdMember>()
        coEvery { householdMemberDao.upsert(capture(memberSlot)) } returns Unit

        val result = repo.acceptInvite("GOODCODE")

        assertThat(result).isEqualTo(InviteResult.Success("mike-hid"))
        // Invite stamped as accepted.
        assertThat(updateSlot.captured["accepted"]).isEqualTo(true)
        assertThat(updateSlot.captured["acceptedBy"]).isEqualTo(TEST_USER_ID)
        // Local wipe of prior household ran before the switch.
        coVerify(exactly = 1) { pullWriteDao.wipeAllForHousehold(TEST_USER_ID) }
        // New membership inserted with hid = mike-hid.
        assertThat(memberSlot.captured.uid).isEqualTo(TEST_USER_ID)
        assertThat(memberSlot.captured.householdId).isEqualTo("mike-hid")
        assertThat(memberSlot.captured.isOwner).isFalse()
        // Active household switched.
        coVerify(exactly = 1) { householdSwitcher.switchToHousehold("mike-hid") }
    }

    @Test fun `leaveHousehold returns to a personal household and switches`() = runTest {
        // Pre: user is in a shared household (mike-hid). Leaving should
        // wipe local + insert a personal membership + switch.
        householdSession.setHouseholdId("mike-hid")
        val docRef: DocumentReference = mockk { coEvery { update(any<Map<String, Any?>>()) } returns Tasks.forResult(null) }
        val householdsColl: CollectionReference = mockk { every { document(any<String>()) } returns docRef }
        val membershipDoc: DocumentReference = mockk { every { collection("households") } returns householdsColl }
        val membershipsColl: CollectionReference = mockk { every { document(any<String>()) } returns membershipDoc }
        every { firestore.collection("memberships") } returns membershipsColl

        val memberSlot = slot<HouseholdMember>()
        coEvery { householdMemberDao.upsert(capture(memberSlot)) } returns Unit

        repo.leaveHousehold()

        coVerify(exactly = 1) { pullWriteDao.wipeAllForHousehold("mike-hid") }
        coVerify(exactly = 1) { householdMemberDao.softDelete(TEST_USER_ID, "mike-hid", 50_000L) }
        // New personal household inserted.
        assertThat(memberSlot.captured.householdId).isEqualTo(TEST_USER_ID)
        assertThat(memberSlot.captured.isOwner).isTrue()
        coVerify(exactly = 1) { householdSwitcher.switchToHousehold(TEST_USER_ID) }
    }

    @Test fun `leaveHousehold is a no-op when the user is already in a personal household`() = runTest {
        // Pre: household == uid (personal). Leaving must NOT wipe local or
        // tombstone the membership — otherwise the user loses their own
        // single-member household's data on a stray tap.
        householdSession.setHouseholdId(TEST_USER_ID)
        repo.leaveHousehold()
        coVerify(exactly = 0) { pullWriteDao.wipeAllForHousehold(any()) }
        coVerify(exactly = 0) { householdMemberDao.softDelete(any(), any(), any()) }
        coVerify(exactly = 0) { householdSwitcher.switchToHousehold(any()) }
    }

    private fun stubInviteGet(snap: DocumentSnapshot) {
        val docRef: DocumentReference = mockk { coEvery { get() } returns Tasks.forResult(snap) }
        val coll: CollectionReference = mockk { every { document(any<String>()) } returns docRef }
        every { firestore.collection("invites") } returns coll
    }
}
