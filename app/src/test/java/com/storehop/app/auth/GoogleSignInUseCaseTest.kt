package com.storehop.app.auth

import android.content.Context
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the auth-flow branching of [GoogleSignInUseCase]. Three load-bearing
 * paths:
 *   1. Anonymous user + Google credential -> `linkWithCredential` keeps the
 *      uid (no orphans).
 *   2. Anonymous user + link fails (e.g., Google account already mapped to
 *      another Firebase user) -> fall back to `signInWithCredential`,
 *      orphans accepted.
 *   3. No current user / non-anon user -> plain `signInWithCredential`.
 *
 * Plus the failure surfaces: cancellation propagates as a Result.failure
 * carrying the `GetCredentialCancellationException`.
 *
 * Heavy use of mockkStatic for `CredentialManager.create` and
 * `GoogleAuthProvider.getCredential`. This is intentional -- the use case
 * is currently structured around those static factories. v0.7.0 multi-user
 * work will likely refactor the surface for testability; if/when that
 * happens, these tests rewrite. For now they pin the v0.5.x contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleSignInUseCaseTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val auth: FirebaseAuth = mockk(relaxed = true)
    // Robolectric supplies a real ApplicationContext so `getString(...)`
    // resolves the google-services-generated `default_web_client_id`
    // string without us hand-mocking that overload (which collides
    // with mockk's varargs matcher resolution).
    private val appContext: Context get() = ApplicationProvider.getApplicationContext()
    private val activityContext: Context get() = ApplicationProvider.getApplicationContext()
    private val credentialManager: CredentialManager = mockk()

    private val firebaseCredential: AuthCredential = mockk(relaxed = true)
    private val googleIdToken = "google-id-token-fake"

    private fun newUseCase() = GoogleSignInUseCase(auth = auth, appContext = appContext)

    @Before fun setUp() {
        // CredentialManager.create() routes through the Kotlin companion
        // (CredentialManager is a Kotlin interface), so mockkObject is the
        // right tool, not mockkStatic.
        mockkObject(CredentialManager.Companion)
        every { CredentialManager.create(any<Context>()) } returns credentialManager
        mockkStatic(GoogleAuthProvider::class)
        every { GoogleAuthProvider.getCredential(any<String>(), any()) } returns firebaseCredential
    }

    @After fun tearDown() {
        unmockkObject(CredentialManager.Companion)
        unmockkStatic(GoogleAuthProvider::class)
    }

    private fun stubGoogleCredential(): GoogleIdTokenCredential {
        val token: GoogleIdTokenCredential = mockk(relaxed = true)
        every { token.idToken } returns googleIdToken
        every { token.type } returns GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        val response: GetCredentialResponse = mockk()
        every { response.credential } returns (token as Credential)
        coEvery { credentialManager.getCredential(any<Context>(), any<androidx.credentials.GetCredentialRequest>()) } returns response
        return token
    }

    @Test fun `anonymous user + google success links the credential to keep the uid`() = runTest {
        stubGoogleCredential()
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { isAnonymous } returns true
            every { uid } returns "anon-uid-123"
        }
        every { auth.currentUser } returns anonUser
        every { anonUser.linkWithCredential(firebaseCredential) } returns
            Tasks.forResult(mockk<AuthResult>(relaxed = true))

        val result = newUseCase().signIn(activityContext)

        assertThat(result.isSuccess).isTrue()
        verify(exactly = 1) { anonUser.linkWithCredential(firebaseCredential) }
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test fun `anonymous user + link failure falls back to signInWithCredential`() = runTest {
        stubGoogleCredential()
        val anonUser: FirebaseUser = mockk(relaxed = true) {
            every { isAnonymous } returns true
            every { uid } returns "anon-uid-456"
        }
        every { auth.currentUser } returns anonUser
        every { anonUser.linkWithCredential(firebaseCredential) } returns
            Tasks.forException(RuntimeException("account already in use"))
        every { auth.signInWithCredential(firebaseCredential) } returns
            Tasks.forResult(mockk<AuthResult>(relaxed = true))

        val result = newUseCase().signIn(activityContext)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { auth.signInWithCredential(firebaseCredential) }
    }

    @Test fun `no current user calls signInWithCredential directly`() = runTest {
        stubGoogleCredential()
        every { auth.currentUser } returns null
        every { auth.signInWithCredential(firebaseCredential) } returns
            Tasks.forResult(mockk<AuthResult>(relaxed = true))

        val result = newUseCase().signIn(activityContext)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { auth.signInWithCredential(firebaseCredential) }
    }

    @Test fun `non-anonymous current user also takes the signInWithCredential branch`() = runTest {
        stubGoogleCredential()
        val signedInUser: FirebaseUser = mockk(relaxed = true) {
            every { isAnonymous } returns false
        }
        every { auth.currentUser } returns signedInUser
        every { auth.signInWithCredential(firebaseCredential) } returns
            Tasks.forResult(mockk<AuthResult>(relaxed = true))

        val result = newUseCase().signIn(activityContext)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { auth.signInWithCredential(firebaseCredential) }
        // The link path is never invoked when the user is already signed in
        // with a non-anonymous credential.
        verify(exactly = 0) { signedInUser.linkWithCredential(any()) }
    }

    @Test fun `user cancellation surfaces as Result-failure with the cancellation exception`() = runTest {
        val cancellation = GetCredentialCancellationException("user dismissed sheet")
        coEvery {
            credentialManager.getCredential(any<Context>(), any<androidx.credentials.GetCredentialRequest>())
        } throws cancellation

        val result = newUseCase().signIn(activityContext)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(GetCredentialCancellationException::class.java)
        // Critically: we don't sign out or sign in on cancellation.
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }
}
