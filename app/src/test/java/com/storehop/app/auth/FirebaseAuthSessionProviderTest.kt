package com.storehop.app.auth

import app.cash.turbine.test
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import com.storehop.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the gating coroutine across both v0.3 and v0.4 branches:
 *
 *  - public userId flow doesn't flip until sync completes (no flicker)
 *  - cloud has data → pull path runs, orphan-claim does NOT
 *  - cloud empty → orphan-claim runs, pull does NOT (avoids overwriting
 *    seeded local data on the very first push)
 *  - peek failure or pull failure → pullState=FAILED, but uid still
 *    publishes (the alternative — getting stuck — is worse UX)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseAuthSessionProviderTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val auth: FirebaseAuth = mockk(relaxed = true)
    private val migrationDao: LocalOnlyMigrationDao = mockk(relaxed = true)
    private val pullCoordinator: PullCoordinator = mockk(relaxed = true)
    private val pullStateRepo: PullStateRepository = mockk(relaxed = true) {
        // Default: every uid is already SUCCEEDED so the gating coroutine's
        // cold-launch short-circuit fires when uid hasn't changed. Tests that
        // exercise the "still needs sync" path (NEEDED, FAILED, IN_PROGRESS)
        // override this stub.
        every { observe(any()) } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)
    }
    // Phase 2: the provider resolves the user's active household from this
    // DAO during sign-in. Default: no existing membership → provider creates
    // a personal household (hid = uid). Tests that exercise the "already
    // joined" path override `activeMembershipFor` per-uid.
    private val householdMemberDao: HouseholdMemberDao = mockk(relaxed = true) {
        coEvery { activeMembershipFor(any()) } returns null
    }
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    /**
     * Default behavior: cloud is empty (returning user with no cloud data
     * yet). Tests that exercise the pull path override these stubs.
     */
    private fun stubCloudEmpty() {
        coEvery { pullCoordinator.peek(any()) } returns false
    }


    @Test fun `userId starts at the disk-cached uid for returning users`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores(any()) } returns 0
        stubCloudEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)

        // Initial value mirrors auth.currentUser.uid -- no `null` flicker on
        // cold launch for already-signed-in users.
        assertThat(provider.userId.value).isEqualTo("anon-uid")
        scope.cancel()
    }

    @Test fun `cold-launch returning user runs sync even when initial uid matches if pullState is NEEDED`() = runTest {
        // Regression for an emulator-caught bug: on cold launch of a returning
        // user, FirebaseAuth.currentUser?.uid is non-null from disk cache, so
        // both rawUid and _userId initialize to the same value. The gating
        // coroutine's first emission sees `_userId.value == newUid` and used
        // to short-circuit before runSyncFor ran -- leaving pullState stuck
        // at NEEDED forever and SyncEngine permanently paused. Fix: short-
        // circuit only when pullState is already SUCCEEDED.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        every { pullStateRepo.observe("anon-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.NEEDED)
        coEvery { pullCoordinator.peek("anon-uid") } returns false
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("anon-uid") } returns 0

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)
        advanceUntilIdle()

        // peek must run -- sync was NOT short-circuited.
        coVerify(exactly = 1) { pullCoordinator.peek("anon-uid") }
        // pullState transitions IN_PROGRESS -> SUCCEEDED.
        coVerifyOrder {
            pullStateRepo.set("anon-uid", PullState.IN_PROGRESS)
            pullStateRepo.set("anon-uid", PullState.SUCCEEDED)
        }
        scope.cancel()
    }

    @Test fun `cold-launch returning user with SUCCEEDED state skips sync (cheap launch)`() = runTest {
        // Inverse of the regression test above: when pullState is already
        // SUCCEEDED for the cached uid, we DO short-circuit -- otherwise every
        // cold launch would issue a Firestore peek read, costing bandwidth
        // and reads on the free tier.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        every { pullStateRepo.observe("anon-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)
        advanceUntilIdle()

        coVerify(exactly = 0) { pullCoordinator.peek(any()) }
        coVerify(exactly = 0) { pullCoordinator.pullForHousehold(any()) }
        scope.cancel()
    }

    @Test fun `host of shared household with multiple members runs sync on cold launch even if SUCCEEDED`() = runTest {
        every { auth.currentUser } returns mockUser("mike-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        every { pullStateRepo.observe("mike-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)
        coEvery { householdMemberDao.activeMembershipFor("mike-uid") } returns HouseholdMember(
            uid = "mike-uid",
            householdId = "mike-uid",
            displayName = "Mike",
            joinedAt = 100L,
            isOwner = true,
            createdAt = 100L,
            updatedAt = 100L,
            deletedAt = null,
        )
        // Two members in Mike's household: Mike and Amanda
        coEvery { householdMemberDao.countMembersOf("mike-uid") } returns 2
        coEvery { pullCoordinator.peek("mike-uid") } returns true
        coEvery { pullCoordinator.pullForHousehold("mike-uid") } returns PullCoordinator.PullResult.Success(1, 1, 1, 1, 1, 1, 2)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)
        advanceUntilIdle()

        // Sync must run because household has multiple members!
        coVerify(exactly = 1) { pullCoordinator.pullForHousehold("mike-uid") }
        scope.cancel()
    }

    @Test fun `cloud has data triggers pull and skips orphan-claim`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } returns true
        coEvery { pullCoordinator.pullForHousehold("google-uid") } returns
            PullCoordinator.PullResult.Success(1, 1, 1, 1, 1, 1)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)

        val firedAuth: FirebaseAuth = mockk {
            every { currentUser } returns mockUser("google-uid")
        }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // Pull ran; orphan-claim did NOT (cloud is authoritative).
        coVerify(exactly = 1) { pullCoordinator.pullForHousehold("google-uid") }
        coVerify(exactly = 0) { migrationDao.claimAllOrphanRowsAs(any()) }
        coVerify(exactly = 0) { migrationDao.claimAllLocalOnlyRowsAs(any()) }
        // pullState transitions: IN_PROGRESS then SUCCEEDED.
        coVerifyOrder {
            pullStateRepo.set("google-uid", PullState.IN_PROGRESS)
            pullStateRepo.set("google-uid", PullState.SUCCEEDED)
        }
        scope.cancel()
    }

    @Test fun `cloud empty triggers orphan-claim and skips pull`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } returns false
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("google-uid") } returns 1

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)

        val firedAuth: FirebaseAuth = mockk {
            every { currentUser } returns mockUser("google-uid")
        }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // Orphan-claim ran; pull did NOT (cloud was empty for this uid).
        coVerify(exactly = 1) { migrationDao.claimAllOrphanRowsAs("google-uid") }
        coVerify(exactly = 0) { pullCoordinator.pullForHousehold(any()) }
        coVerifyOrder {
            pullStateRepo.set("google-uid", PullState.IN_PROGRESS)
            pullStateRepo.set("google-uid", PullState.SUCCEEDED)
        }
        scope.cancel()
    }

    @Test fun `pull failure leaves pullState FAILED but still publishes uid`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } returns true
        coEvery { pullCoordinator.pullForHousehold("google-uid") } returns
            PullCoordinator.PullResult.Failure(RuntimeException("network"))

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)

        provider.userId.test {
            assertThat(awaitItem()).isEqualTo("anon-uid")

            val firedAuth: FirebaseAuth = mockk {
                every { currentUser } returns mockUser("google-uid")
            }
            listenerSlot.captured.onAuthStateChanged(firedAuth)
            advanceUntilIdle()

            // uid still publishes -- the alternative (stuck on the old uid
            // forever) would lock the user out worse.
            assertThat(awaitItem()).isEqualTo("google-uid")
            cancelAndIgnoreRemainingEvents()
        }
        coVerifyOrder {
            pullStateRepo.set("google-uid", PullState.IN_PROGRESS)
            pullStateRepo.set("google-uid", PullState.FAILED)
        }
        scope.cancel()
    }

    @Test fun `peek failure fails closed - no orphan-claim, no pull, FAILED state`() = runTest {
        // Critical: when peek itself fails, we cannot trust the "cloud is
        // empty" signal. Falling through to orphan-claim could re-stamp
        // local seeded data and let it leak to cloud once peek recovers.
        // Better to fail closed: pullState=FAILED, banner shows, user retries.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } throws RuntimeException("network")

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)

        val firedAuth: FirebaseAuth = mockk {
            every { currentUser } returns mockUser("google-uid")
        }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // Neither pull NOR orphan-claim ran -- we don't know which is right.
        coVerify(exactly = 0) { pullCoordinator.pullForHousehold(any()) }
        coVerify(exactly = 0) { migrationDao.claimAllOrphanRowsAs(any()) }
        coVerifyOrder {
            pullStateRepo.set("google-uid", PullState.IN_PROGRESS)
            pullStateRepo.set("google-uid", PullState.FAILED)
        }
        scope.cancel()
    }

    @Test fun `clears userId on sign-out without running any sync`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        stubCloudEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)

        provider.userId.test {
            assertThat(awaitItem()).isEqualTo("anon-uid")

            val firedAuth: FirebaseAuth = mockk {
                every { currentUser } returns null
            }
            listenerSlot.captured.onAuthStateChanged(firedAuth)
            advanceUntilIdle()

            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }

        // Sign-out is a "no sync needed" path; no pull, no claim, no state writes.
        coVerify(exactly = 0) { pullCoordinator.peek(any()) }
        coVerify(exactly = 0) { pullCoordinator.pullForHousehold(any()) }
        coVerify(exactly = 0) { migrationDao.claimAllOrphanRowsAs(any()) }
        coVerify(exactly = 0) { migrationDao.claimAllLocalOnlyRowsAs(any()) }
        scope.cancel()
    }

    @Test fun `claim path still publishes uid when claim throws`() = runTest {
        // Defense-in-depth: a corrupt-DB error in the orphan-claim path
        // shouldn't permanently trap the app on the previous uid. Logs and
        // proceeds.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } returns false
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("google-uid") } returns 1
        coEvery { migrationDao.claimAllOrphanRowsAs("google-uid") } throws RuntimeException("disk full")

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)

        provider.userId.test {
            assertThat(awaitItem()).isEqualTo("anon-uid")

            val firedAuth: FirebaseAuth = mockk {
                every { currentUser } returns mockUser("google-uid")
            }
            listenerSlot.captured.onAuthStateChanged(firedAuth)
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo("google-uid")
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    // ---- v0.7.0 Phase 2: household bootstrap ---------------------------------

    @Test fun `cold-launch with no prior membership creates a personal household with hid equal to uid`() = runTest {
        // First-launch-ever bootstrap (or a wipe-and-reinstall) — the user has
        // an authed uid but no household_members row yet. The provider must
        // insert one with hid = uid + isOwner = true before publishing the
        // household flow.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        coEvery { householdMemberDao.activeMembershipFor("anon-uid") } returns null
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores(any()) } returns 0
        stubCloudEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            householdMemberDao.upsert(match { it.uid == "anon-uid" && it.householdId == "anon-uid" && it.isOwner })
        }
        // householdId publishes alongside userId.
        assertThat(provider.householdId.value).isEqualTo("anon-uid")
        scope.cancel()
    }

    @Test fun `cold-launch with an existing membership reuses that household and does NOT create another`() = runTest {
        // Returning user who previously joined Amanda's household. The
        // provider must reuse the existing membership (no household_members
        // upsert) and publish the shared household id.
        every { auth.currentUser } returns mockUser("amanda-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        coEvery { householdMemberDao.activeMembershipFor("amanda-uid") } returns HouseholdMember(
            uid = "amanda-uid",
            householdId = "mike-uid",
            displayName = "Amanda",
            joinedAt = 100L,
            isOwner = false,
            createdAt = 100L,
            updatedAt = 100L,
            deletedAt = null,
        )
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores(any()) } returns 0
        stubCloudEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        advanceUntilIdle()

        // No new membership row written -- the existing one is reused.
        coVerify(exactly = 0) { householdMemberDao.upsert(any()) }
        // The provider publishes the shared household id, not Amanda's own uid.
        assertThat(provider.householdId.value).isEqualTo("mike-uid")
        scope.cancel()
    }

    @Test fun `sign-out clears both userId and householdId`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { householdMemberDao.activeMembershipFor("anon-uid") } returns null
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores(any()) } returns 0
        stubCloudEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        advanceUntilIdle()
        // Sanity: both ids populated.
        assertThat(provider.userId.value).isEqualTo("anon-uid")
        assertThat(provider.householdId.value).isEqualTo("anon-uid")

        // Fire sign-out: auth.currentUser → null.
        val firedAuth: FirebaseAuth = mockk { every { currentUser } returns null }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // Both flows clear together — never an inconsistent (uid=null, hid="anon-uid") state.
        assertThat(provider.userId.value).isNull()
        assertThat(provider.householdId.value).isNull()
        scope.cancel()
    }

    @Test fun `currentDisplayName returns displayName when non-blank`() = runTest {
        val user = mockUser("uid1").apply {
            every { displayName } returns "Amanda"
            every { email } returns "amanda@test.com"
        }
        every { auth.currentUser } returns user
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        assertThat(provider.currentDisplayName()).isEqualTo("Amanda")
        scope.cancel()
    }

    @Test fun `currentDisplayName falls back to email prefix when displayName is null or blank`() = runTest {
        val user = mockUser("uid2").apply {
            every { displayName } returns "   "
            every { email } returns "mike.smith@test.com"
        }
        every { auth.currentUser } returns user
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        assertThat(provider.currentDisplayName()).isEqualTo("mike.smith")
        scope.cancel()
    }

    @Test fun `currentDisplayName returns null when both displayName and email are missing`() = runTest {
        val user = mockUser("uid3").apply {
            every { displayName } returns null
            every { email } returns null
        }
        every { auth.currentUser } returns user
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        assertThat(provider.currentDisplayName()).isNull()
        scope.cancel()
    }

    @Test fun `cold launch with local stores publishes userId and householdId before background sync completes`() = runTest {
        every { auth.currentUser } returns mockUser("returning-uid")
        every { auth.addAuthStateListener(any()) } returns Unit
        coEvery { householdMemberDao.activeMembershipFor("returning-uid") } returns HouseholdMember(
            uid = "returning-uid", householdId = "returning-uid", displayName = null,
            joinedAt = 1L, isOwner = true, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("returning-uid") } returns 0

        val storeDao: com.storehop.app.data.dao.StoreDao = mockk {
            coEvery { countForHousehold("returning-uid") } returns 3
        }
        // Simulate a slow network peek that hasn't completed yet
        val peekGate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { pullCoordinator.peek("returning-uid") } coAnswers { peekGate.await() }
        every { pullStateRepo.observe("returning-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.NEEDED)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(
            auth = auth,
            migrationDao = migrationDao,
            householdMemberDao = householdMemberDao,
            pullCoordinator = pullCoordinator,
            pullStateRepo = pullStateRepo,
            userPreferencesSync = mockk(relaxed = true),
            clock = fixedClock,
            applicationScope = scope,
            storeDao = storeDao,
        )

        // Advance until the coroutine hits peekGate.await()
        testScheduler.runCurrent()

        // userId and householdId are ALREADY published while peekGate is still suspended!
        assertThat(provider.userId.value).isEqualTo("returning-uid")
        assertThat(provider.householdId.value).isEqualTo("returning-uid")

        peekGate.complete(false)
        advanceUntilIdle()
        scope.cancel()
    }

    @Test fun `shared household with SUCCEEDED pullState pulls in background without peek or IN_PROGRESS`() = runTest {
        every { auth.currentUser } returns mockUser("amanda-uid")
        every { auth.addAuthStateListener(any()) } returns Unit
        coEvery { householdMemberDao.activeMembershipFor("amanda-uid") } returns HouseholdMember(
            uid = "amanda-uid", householdId = "mike-uid", displayName = "Amanda",
            joinedAt = 1L, isOwner = false, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("amanda-uid") } returns 0
        every { pullStateRepo.observe("amanda-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)
        coEvery { pullCoordinator.pullForHousehold("mike-uid") } returns PullCoordinator.PullResult.Success(1, 1, 1, 1, 1, 1)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = newProvider(scope)
        advanceUntilIdle()

        assertThat(provider.userId.value).isEqualTo("amanda-uid")
        assertThat(provider.householdId.value).isEqualTo("mike-uid")
        coVerify(exactly = 0) { pullCoordinator.peek(any()) }
        coVerify(exactly = 0) { pullStateRepo.set("amanda-uid", PullState.IN_PROGRESS) }
        coVerify(exactly = 1) { pullCoordinator.pullForHousehold("mike-uid") }
        scope.cancel()
    }

    @Test fun `onAppForegrounded triggers pullForHousehold and refreshMembers with 30s cooldown`() = runTest {
        var nowMs = 100_000L
        val mutableClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        }
        every { auth.currentUser } returns mockUser("amanda-uid")
        every { auth.addAuthStateListener(any()) } returns Unit
        coEvery { householdMemberDao.activeMembershipFor("amanda-uid") } returns HouseholdMember(
            uid = "amanda-uid", householdId = "mike-uid", displayName = "Amanda",
            joinedAt = 1L, isOwner = false, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("amanda-uid") } returns 0
        every { pullStateRepo.observe("amanda-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)
        coEvery { pullCoordinator.pullForHousehold("mike-uid") } returns PullCoordinator.PullResult.Success(1, 1, 1, 1, 1, 1)
        val householdRepo: com.storehop.app.data.repository.HouseholdRepository = mockk(relaxed = true)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(
            auth = auth,
            migrationDao = migrationDao,
            householdMemberDao = householdMemberDao,
            pullCoordinator = pullCoordinator,
            pullStateRepo = pullStateRepo,
            userPreferencesSync = mockk(relaxed = true),
            clock = mutableClock,
            applicationScope = scope,
            householdRepository = dagger.Lazy { householdRepo },
        )
        advanceUntilIdle()

        // Cold start already ran 1 pull at nowMs = 100_000L; an immediate onResume()
        // at the same timestamp is throttled so cold start doesn't double-pull.
        provider.onAppForegrounded()
        advanceUntilIdle()
        coVerify(exactly = 1) { pullCoordinator.pullForHousehold("mike-uid") }

        // Foreground refresh 31s later runs pull + refreshMembers
        nowMs += 31_000L
        provider.onAppForegrounded()
        advanceUntilIdle()
        coVerify(exactly = 2) { pullCoordinator.pullForHousehold("mike-uid") }
        coVerify(exactly = 1) { householdRepo.refreshMembers() }

        // Foreground refresh 5s later is throttled by 30s cooldown
        nowMs += 5_000L
        provider.onAppForegrounded()
        advanceUntilIdle()
        coVerify(exactly = 2) { pullCoordinator.pullForHousehold("mike-uid") }

        // Foreground refresh 31s later runs again
        nowMs += 31_000L
        provider.onAppForegrounded()
        advanceUntilIdle()
        coVerify(exactly = 3) { pullCoordinator.pullForHousehold("mike-uid") }
        coVerify(exactly = 2) { householdRepo.refreshMembers() }
        scope.cancel()
    }

    @Test fun `host household with single member triggers background healHostMembersIfNeeded`() = runTest {
        every { auth.currentUser } returns mockUser("mike-uid")
        every { auth.addAuthStateListener(any()) } returns Unit
        val hostMember = HouseholdMember(
            uid = "mike-uid", householdId = "mike-uid", displayName = "Mike",
            joinedAt = 1L, isOwner = true, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        coEvery { householdMemberDao.activeMembershipFor("mike-uid") } returns hostMember
        coEvery { householdMemberDao.countMembersOf("mike-uid") } returns 1
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("mike-uid") } returns 0
        every { pullStateRepo.observe("mike-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.SUCCEEDED)
        val householdRepo: com.storehop.app.data.repository.HouseholdRepository = mockk(relaxed = true)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(
            auth = auth,
            migrationDao = migrationDao,
            householdMemberDao = householdMemberDao,
            pullCoordinator = pullCoordinator,
            pullStateRepo = pullStateRepo,
            userPreferencesSync = mockk(relaxed = true),
            clock = fixedClock,
            applicationScope = scope,
            householdRepository = dagger.Lazy { householdRepo },
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { householdRepo.refreshMembers() }
        scope.cancel()
    }

    @Test fun `host household with single member and NEEDED pullState still triggers healHostMembersIfNeeded after sync`() = runTest {
        every { auth.currentUser } returns mockUser("mike-uid")
        every { auth.addAuthStateListener(any()) } returns Unit
        val hostMember = HouseholdMember(
            uid = "mike-uid", householdId = "mike-uid", displayName = "Mike",
            joinedAt = 1L, isOwner = true, createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        coEvery { householdMemberDao.activeMembershipFor("mike-uid") } returns hostMember
        coEvery { householdMemberDao.countMembersOf("mike-uid") } returns 1
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("mike-uid") } returns 0
        val storeDao: com.storehop.app.data.dao.StoreDao = mockk {
            coEvery { countForHousehold("mike-uid") } returns 2
        }
        stubCloudEmpty()
        every { pullStateRepo.observe("mike-uid") } returns kotlinx.coroutines.flow.flowOf(PullState.NEEDED)
        val householdRepo: com.storehop.app.data.repository.HouseholdRepository = mockk(relaxed = true)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(
            auth = auth,
            migrationDao = migrationDao,
            householdMemberDao = householdMemberDao,
            pullCoordinator = pullCoordinator,
            pullStateRepo = pullStateRepo,
            userPreferencesSync = mockk(relaxed = true),
            clock = fixedClock,
            applicationScope = scope,
            storeDao = storeDao,
            householdRepository = dagger.Lazy { householdRepo },
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { householdRepo.refreshMembers() }
        scope.cancel()
    }

    // -------------------------------------------------------------------------

    private fun newProvider(scope: CoroutineScope) = FirebaseAuthSessionProvider(
        auth = auth,
        migrationDao = migrationDao,
        householdMemberDao = householdMemberDao,
        pullCoordinator = pullCoordinator,
        pullStateRepo = pullStateRepo,
        userPreferencesSync = mockk(relaxed = true),
        clock = fixedClock,
        applicationScope = scope,
    )

    private fun mockUser(uid: String): FirebaseUser = mockk(relaxed = true) {
        every { this@mockk.uid } returns uid
    }
}
