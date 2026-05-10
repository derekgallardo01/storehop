package com.storehop.app.ui.settings

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.storehop.app.auth.GoogleSignInUseCase
import com.storehop.app.data.prefs.ThemeMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the recently-changed busy-flag behavior on signInWithGoogle:
 * after the GoogleSignInUseCase succeeds, the VM must wait for the gated
 * sessionProvider.userId flow to flip to the new uid BEFORE clearing busy.
 * That gate is what hides the empty-state flicker on the Shop screen during
 * the orphan-claim migration -- regressing it would re-introduce the
 * "where did my stores go?" moment right after sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context: Context = mockk(relaxed = true)
    private val auth: FirebaseAuth = mockk(relaxed = true)
    private val googleSignIn: GoogleSignInUseCase = mockk()
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true) {
        every { themeMode } returns flowOf(ThemeMode.SYSTEM)
    }
    private val pullCoordinator: PullCoordinator = mockk(relaxed = true)
    private val pullStateRepo: PullStateRepository = mockk(relaxed = true) {
        every { observe(any()) } returns flowOf(PullState.SUCCEEDED)
    }

    @Test fun `signInWithGoogle keeps busy=true until session uid flips to the new uid`() = runTest {
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { uid } returns "anon-uid"
            every { isAnonymous } returns true
            every { email } returns null
            every { displayName } returns null
            every { photoUrl } returns null
        }
        val googleUser: FirebaseUser = mockk(relaxed = true) {
            every { uid } returns "google-uid"
            every { isAnonymous } returns false
            every { email } returns "user@example.com"
            every { displayName } returns "Test User"
            every { photoUrl } returns null
        }
        // currentUser is the anon at construction; flips to google after sign-in.
        every { auth.currentUser } returnsMany listOf(anonUser, googleUser, googleUser, googleUser)
        coEvery { googleSignIn.signIn(any()) } returns Result.success(Unit)

        val session = FakeSessionProvider(initial = "anon-uid")
        val vm = newVm(session)

        vm.state.test {
            assertThat(awaitItem().busy).isFalse() // initial

            vm.signInWithGoogle(context)
            assertThat(awaitItem().busy).isTrue()  // immediately after kickoff

            advanceUntilIdle() // googleSignIn.signIn() resumes; awaiting userId flip

            // Critical: busy MUST still be true because the session uid has
            // not yet flipped to "google-uid". This is what protects the
            // Shop screen from rendering empty during the migration window.
            expectNoEvents()

            // Simulate FirebaseAuthSessionProvider's gated emission --
            // the orphan-claim migration just completed, publish new uid.
            session.setUserId("google-uid")
            advanceUntilIdle()

            // NOW busy clears.
            val final = awaitItem()
            assertThat(final.busy).isFalse()
            assertThat(final.email).isEqualTo("user@example.com")
            assertThat(final.isAnonymous).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `signInWithGoogle clears busy with no error on user cancellation`() = runTest {
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { uid } returns "anon-uid"
            every { isAnonymous } returns true
            every { email } returns null
            every { displayName } returns null
            every { photoUrl } returns null
        }
        every { auth.currentUser } returns anonUser
        coEvery { googleSignIn.signIn(any()) } returns Result.failure(
            GetCredentialCancellationException("user dismissed sheet"),
        )

        val vm = newVm(FakeSessionProvider(initial = "anon-uid"))

        vm.state.test {
            assertThat(awaitItem().busy).isFalse()

            vm.signInWithGoogle(context)
            assertThat(awaitItem().busy).isTrue()
            advanceUntilIdle()

            val final = awaitItem()
            assertThat(final.busy).isFalse()
            // Cancellation isn't an error worth surfacing.
            assertThat(final.error).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `signInWithGoogle surfaces error message on a real failure`() = runTest {
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { uid } returns "anon-uid"
            every { isAnonymous } returns true
            every { email } returns null
            every { displayName } returns null
            every { photoUrl } returns null
        }
        every { auth.currentUser } returns anonUser
        coEvery { googleSignIn.signIn(any()) } returns Result.failure(
            RuntimeException("network error"),
        )

        val vm = newVm(FakeSessionProvider(initial = "anon-uid"))

        vm.state.test {
            assertThat(awaitItem().busy).isFalse()
            vm.signInWithGoogle(context)
            assertThat(awaitItem().busy).isTrue()
            advanceUntilIdle()

            val final = awaitItem()
            assertThat(final.busy).isFalse()
            assertThat(final.error).isEqualTo("network error")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `signInWithGoogle is a no-op when already busy`() = runTest {
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { uid } returns "anon-uid"
            every { isAnonymous } returns true
            every { email } returns null
            every { displayName } returns null
            every { photoUrl } returns null
        }
        every { auth.currentUser } returns anonUser
        // signIn never completes within this test -- enough to keep busy=true.
        coEvery { googleSignIn.signIn(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }

        val vm = newVm(FakeSessionProvider(initial = "anon-uid"))

        vm.signInWithGoogle(context)
        advanceUntilIdle() // first call enters the in-flight state

        vm.signInWithGoogle(context) // re-tap while busy=true should bail
        advanceUntilIdle()

        // signIn must have been called exactly once -- the second tap was
        // dropped at the busy-guard at the top of signInWithGoogle.
        io.mockk.coVerify(exactly = 1) { googleSignIn.signIn(any()) }
    }

    @Test fun `pullState reflects the active uid's stored state`() = runTest {
        every { auth.currentUser } returns mockk(relaxed = true) {
            every { uid } returns "google-uid"
            every { isAnonymous } returns false
            every { email } returns "u@x.com"
            every { displayName } returns "U"
            every { photoUrl } returns null
        }
        every { pullStateRepo.observe("google-uid") } returns flowOf(PullState.FAILED)

        val vm = newVm(FakeSessionProvider(initial = "google-uid"))
        vm.pullState.test {
            // Initial value before flow connects.
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).isEqualTo(PullState.FAILED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `retryPull invokes PullCoordinator with the current uid`() = runTest {
        every { auth.currentUser } returns mockk(relaxed = true) {
            every { uid } returns "google-uid"
            every { isAnonymous } returns false
            every { email } returns "u@x.com"
            every { displayName } returns "U"
            every { photoUrl } returns null
        }
        coEvery { pullCoordinator.pullForUid("google-uid") } returns
            PullCoordinator.PullResult.Success(0, 0, 0, 0, 0, 0)

        val vm = newVm(FakeSessionProvider(initial = "google-uid"))
        vm.retryPull()
        advanceUntilIdle()

        coVerify(exactly = 1) { pullCoordinator.pullForUid("google-uid") }
        // State transitions IN_PROGRESS -> SUCCEEDED on retry success.
        coVerify { pullStateRepo.set("google-uid", PullState.IN_PROGRESS) }
        coVerify { pullStateRepo.set("google-uid", PullState.SUCCEEDED) }
    }

    @Test fun `retryPull writes FAILED state when pull fails`() = runTest {
        every { auth.currentUser } returns mockk(relaxed = true) {
            every { uid } returns "google-uid"
            every { isAnonymous } returns false
            every { email } returns null
            every { displayName } returns null
            every { photoUrl } returns null
        }
        coEvery { pullCoordinator.pullForUid("google-uid") } returns
            PullCoordinator.PullResult.Failure(RuntimeException("network"))

        val vm = newVm(FakeSessionProvider(initial = "google-uid"))
        vm.retryPull()
        advanceUntilIdle()

        coVerify { pullStateRepo.set("google-uid", PullState.IN_PROGRESS) }
        coVerify { pullStateRepo.set("google-uid", PullState.FAILED) }
    }

    @Test fun `retryPull is a no-op when no uid is signed in`() = runTest {
        every { auth.currentUser } returns null
        val vm = newVm(FakeSessionProvider(initial = null))
        vm.retryPull()
        advanceUntilIdle()

        coVerify(exactly = 0) { pullCoordinator.pullForUid(any()) }
        coVerify(exactly = 0) { pullStateRepo.set(any(), any()) }
    }

    @Test fun `setThemeMode delegates to the prefs repo`() = runTest {
        val vm = newVm(FakeSessionProvider("u"))
        vm.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()
        coVerify(exactly = 1) { userPrefs.setThemeMode(ThemeMode.DARK) }
    }

    @Test fun `setLocale updates currentLocaleTag immediately`() = runTest {
        val vm = newVm(FakeSessionProvider("u"))
        vm.setLocale("pt-PT")
        advanceUntilIdle()
        // The actual locale apply routes to LocaleManager / AppCompatDelegate
        // depending on the SDK; here we pin the VM's own state-flow update
        // since that's what the radio-button selection binds to.
        assertThat(vm.currentLocaleTag.value).isEqualTo("pt-PT")
    }

    @Test fun `setLocale with empty tag clears the LocaleManager override`() = runTest {
        val vm = newVm(FakeSessionProvider("u"))
        vm.setLocale("")  // The "follow system" path -- empty LocaleList.
        advanceUntilIdle()
        assertThat(vm.currentLocaleTag.value).isEmpty()
    }

    @Test fun `onCleared removes the AuthStateListener`() = runTest {
        val vm = newVm(FakeSessionProvider("u"))
        // Trigger ViewModel.onCleared via the test helper.
        invokeOnCleared(vm)
        io.mockk.verify { auth.removeAuthStateListener(any()) }
    }

    private fun invokeOnCleared(vm: androidx.lifecycle.ViewModel) {
        // ViewModel.onCleared is protected; invoke via reflection in the test.
        val method = androidx.lifecycle.ViewModel::class.java
            .getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)
    }

    @Test fun `signOut signs out then re-anon and waits for the uid flip`() = runTest {
        // Pre-test: caller currently signed in with "u"; after signOut +
        // re-anon, currentUser flips to a new anon uid.
        val signedInUser: FirebaseUser = mockk(relaxed = true) { every { uid } returns "u" }
        val anonUser: FirebaseUser = mockk(relaxed = true) { every { uid } returns "anon-after" }
        every { auth.currentUser } returnsMany listOf(signedInUser, anonUser, anonUser)
        every { auth.signInAnonymously() } returns
            com.google.android.gms.tasks.Tasks.forResult(mockk<com.google.firebase.auth.AuthResult>(relaxed = true))

        val session = FakeSessionProvider("u")
        val vm = newVm(session)
        vm.signOut()
        // Simulate the FirebaseAuth listener flipping the session's uid
        // to the new anon uid -- this is what unblocks the gating
        // `userId.first { it == targetUid }` inside signOut.
        session.setUserId("anon-after")
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) { auth.signOut() }
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test fun `signOut catches exception and surfaces error`() = runTest {
        every { auth.signOut() } throws RuntimeException("network down")
        val vm = newVm(FakeSessionProvider("u"))
        vm.signOut()
        advanceUntilIdle()

        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.error).contains("network down")
    }

    @Test fun `signOut is a no-op when already busy`() = runTest {
        // Existing busy=true blocks a second signOut. Verified via the
        // early-return guard at the top of signOut().
        every { auth.currentUser } returns null
        coEvery { googleSignIn.signIn(any()) } coAnswers {
            // Hold the busy flag true.
            kotlinx.coroutines.delay(10_000L)
            Result.success(Unit)
        }
        val vm = newVm(FakeSessionProvider("u"))
        vm.signInWithGoogle(activityContext = mockk(relaxed = true))
        // VM is now busy.
        vm.signOut()  // Should early-return without calling auth.signOut().

        io.mockk.verify(exactly = 0) { auth.signOut() }
    }

    @Test fun `clearError wipes a sticky error from a prior signIn failure`() = runTest {
        every { auth.currentUser } returns null
        coEvery { googleSignIn.signIn(any()) } returns
            Result.failure(IllegalStateException("boom"))
        val vm = newVm(FakeSessionProvider("u"))
        vm.signInWithGoogle(activityContext = mockk(relaxed = true))
        advanceUntilIdle()
        assertThat(vm.state.value.error).isNotNull()

        vm.clearError()
        assertThat(vm.state.value.error).isNull()
    }

    private fun newVm(session: FakeSessionProvider) = SettingsViewModel(
        appContext = context,
        auth = auth,
        googleSignIn = googleSignIn,
        userPrefs = userPrefs,
        sessionProvider = session,
        pullCoordinator = pullCoordinator,
        pullStateRepo = pullStateRepo,
    )
}
