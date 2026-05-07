package com.storehop.app.auth

import app.cash.turbine.test
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import com.storehop.app.testing.MainDispatcherRule
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
    private val pullStateRepo: PullStateRepository = mockk(relaxed = true)

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

    @Test fun `cloud has data triggers pull and skips orphan-claim`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { pullCoordinator.peek("google-uid") } returns true
        coEvery { pullCoordinator.pullForUid("google-uid") } returns
            PullCoordinator.PullResult.Success(1, 1, 1, 1, 1, 1)

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        newProvider(scope)

        val firedAuth: FirebaseAuth = mockk {
            every { currentUser } returns mockUser("google-uid")
        }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // Pull ran; orphan-claim did NOT (cloud is authoritative).
        coVerify(exactly = 1) { pullCoordinator.pullForUid("google-uid") }
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
        coVerify(exactly = 0) { pullCoordinator.pullForUid(any()) }
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
        coEvery { pullCoordinator.pullForUid("google-uid") } returns
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
        coVerify(exactly = 0) { pullCoordinator.pullForUid(any()) }
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
        coVerify(exactly = 0) { pullCoordinator.pullForUid(any()) }
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

    private fun newProvider(scope: CoroutineScope) = FirebaseAuthSessionProvider(
        auth = auth,
        migrationDao = migrationDao,
        pullCoordinator = pullCoordinator,
        pullStateRepo = pullStateRepo,
        applicationScope = scope,
    )

    private fun mockUser(uid: String): FirebaseUser = mockk(relaxed = true) {
        every { this@mockk.uid } returns uid
    }
}
