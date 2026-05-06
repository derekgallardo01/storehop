package com.storehop.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.storehop.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the "Continue with Google" flow via Credential Manager (the modern
 * replacement for the deprecated `GoogleSignIn` API + `GoogleSignInClient`
 * from `play-services-auth`).
 *
 * If the user is currently signed in anonymously, we **link** the Google
 * credential to the existing Firebase account so the uid is preserved
 * (no data migration needed). If linking fails because the Google account is
 * already associated with another Firebase user, fall back to a plain
 * `signInWithCredential` -- the data layer's claim-migration only ran for the
 * `local-only` sentinel, so the prior anon uid's rows just become orphans
 * (invisible under the new uid). Acceptable for v1; v0.5 can offer a
 * "carry my data" path.
 */
@Singleton
class GoogleSignInUseCase @Inject constructor(
    private val auth: FirebaseAuth,
    @ApplicationContext private val appContext: Context,
) {
    /**
     * Launches the Sign In with Google sheet via Credential Manager.
     *
     * Must be called with an [Activity] context (Credential Manager attaches the
     * sheet to the calling activity). Returns success after Firebase has a Google
     * provider linked to the current uid.
     */
    suspend fun signIn(activityContext: Context): Result<Unit> = runCatching<Unit> {
        val webClientId = appContext.getString(R.string.default_web_client_id)

        val googleOption = GetSignInWithGoogleOption.Builder(webClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val response = CredentialManager.create(activityContext)
            .getCredential(activityContext, request)

        val credential = response.credential
        require(credential is GoogleIdTokenCredential || credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type: ${credential.type}"
        }
        val googleIdToken = (credential as? GoogleIdTokenCredential)
            ?: GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)

        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            // Anonymous-to-Google upgrade: preserve the uid via linkWithCredential.
            try {
                current.linkWithCredential(firebaseCredential).await()
                Log.i(TAG, "Linked anonymous account to Google; uid=${current.uid}")
            } catch (linkFailure: Exception) {
                // The Google account is already associated with another Firebase user.
                // Sign in to that account instead; the anon uid's local rows become
                // orphans (invisible under the new uid).
                Log.w(TAG, "linkWithCredential failed (${linkFailure.message}); falling back to signInWithCredential")
                auth.signInWithCredential(firebaseCredential).await()
            }
        } else {
            auth.signInWithCredential(firebaseCredential).await()
            Log.i(TAG, "Signed in with Google; uid=${auth.currentUser?.uid}")
        }
    }.onFailure { e ->
        if (e is GetCredentialCancellationException) {
            Log.i(TAG, "User cancelled the Sign In with Google sheet.")
        } else if (e is GetCredentialException) {
            Log.w(TAG, "Credential Manager error: ${e.message}", e)
        } else {
            Log.e(TAG, "Sign-in failed", e)
        }
    }

    companion object {
        private const val TAG = "GoogleSignIn"
    }
}
