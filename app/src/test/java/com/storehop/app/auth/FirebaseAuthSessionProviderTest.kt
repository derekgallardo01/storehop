package com.storehop.app.auth

import app.cash.turbine.test
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the gating coroutine: the public userId flow must NOT flip to a new
 * uid until [LocalOnlyMigrationDao]'s claims have re-stamped the rows.
 * Regressing this would re-introduce the "stores briefly disappear after
 * Sign-In" empty-state flicker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseAuthSessionProviderTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val auth: FirebaseAuth = mockk(relaxed = true)
    private val migrationDao: LocalOnlyMigrationDao = mockk(relaxed = true)

    @Test fun `userId starts at the disk-cached uid for returning users`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores(any()) } returns 0

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(auth, migrationDao, scope)

        // Initial value mirrors auth.currentUser.uid -- no `null` flicker on
        // cold launch for already-signed-in users.
        assertThat(provider.userId.value).isEqualTo("anon-uid")
        scope.cancel()
    }

    @Test fun `runs orphan claim BEFORE flipping the public userId flow`() = runTest {
        // Cold start with anon-uid cached. Then auth listener fires with a
        // fresh google-uid mid-session (sign-in flow). The public flow must
        // hold on the old uid until the orphan migration finishes.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))

        // Capture the listener so we can fire a uid change manually.
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit

        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("google-uid") } returns 1

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(auth, migrationDao, scope)

        provider.userId.test {
            assertThat(awaitItem()).isEqualTo("anon-uid")

            // Simulate auth listener firing with the new google-uid.
            val firedAuth: FirebaseAuth = mockk {
                every { currentUser } returns mockUser("google-uid")
            }
            listenerSlot.captured.onAuthStateChanged(firedAuth)

            advanceUntilIdle()

            // Public flow flips ONLY after the orphan claim.
            assertThat(awaitItem()).isEqualTo("google-uid")

            cancelAndIgnoreRemainingEvents()
        }

        // Order matters: claim runs before the flip would have been observed.
        coVerifyOrder {
            migrationDao.countOrphanStores("google-uid")
            migrationDao.claimAllOrphanRowsAs("google-uid")
        }
        scope.cancel()
    }

    @Test fun `skips the orphan claim when there are no orphan rows`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("google-uid") } returns 0

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        FirebaseAuthSessionProvider(auth, migrationDao, scope)

        val firedAuth: FirebaseAuth = mockk {
            every { currentUser } returns mockUser("google-uid")
        }
        listenerSlot.captured.onAuthStateChanged(firedAuth)
        advanceUntilIdle()

        // No claim issued -- the count check short-circuits the UPDATEs so
        // we don't dirty pendingSync flags on already-clean rows.
        coVerify(exactly = 0) { migrationDao.claimAllOrphanRowsAs(any()) }
        scope.cancel()
    }

    @Test fun `clears userId on sign-out without running any claims`() = runTest {
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(auth, migrationDao, scope)

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

        // Sign-out is a "no rows to migrate" path; we must not invoke any
        // claim DAO with a null uid -- that would throw on the require()
        // guard inside the DAO.
        coVerify(exactly = 0) { migrationDao.claimAllOrphanRowsAs(any()) }
        coVerify(exactly = 0) { migrationDao.claimAllLocalOnlyRowsAs(any()) }
        scope.cancel()
    }

    @Test fun `still publishes the new uid if the claim throws`() = runTest {
        // We don't want a corrupt-DB or transient SQLite error to permanently
        // trap the app on the previous uid. Logs the error and proceeds.
        every { auth.currentUser } returns mockUser("anon-uid")
        every { auth.signInAnonymously() } returns Tasks.forResult(mockk<AuthResult>(relaxed = true))
        val listenerSlot = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listenerSlot)) } returns Unit
        coEvery { migrationDao.countLocalOnlyStores() } returns 0
        coEvery { migrationDao.countOrphanStores("google-uid") } returns 1
        coEvery { migrationDao.claimAllOrphanRowsAs("google-uid") } throws RuntimeException("disk full")

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val provider = FirebaseAuthSessionProvider(auth, migrationDao, scope)

        provider.userId.test {
            assertThat(awaitItem()).isEqualTo("anon-uid")

            val firedAuth: FirebaseAuth = mockk {
                every { currentUser } returns mockUser("google-uid")
            }
            listenerSlot.captured.onAuthStateChanged(firedAuth)
            advanceUntilIdle()

            // Despite the migration failure, we do flip the public flow --
            // the alternative (silently stuck on the old uid) is worse.
            assertThat(awaitItem()).isEqualTo("google-uid")
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    private fun mockUser(uid: String): FirebaseUser = mockk(relaxed = true) {
        every { this@mockk.uid } returns uid
    }

    @Suppress("unused") // kept for potential future test that needs to clean up jobs
    private fun CoroutineScope.swallowChildJobs(): Job = SupervisorJob()
}
