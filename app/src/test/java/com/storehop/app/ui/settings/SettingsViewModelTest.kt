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
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
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
    private val userPrefs: UserPreferencesRepository = mockk {
        every { themeMode } returns flowOf(ThemeMode.SYSTEM)
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

    private fun newVm(session: FakeSessionProvider) = SettingsViewModel(
        appContext = context,
        auth = auth,
        googleSignIn = googleSignIn,
        userPrefs = userPrefs,
        sessionProvider = session,
    )
}
