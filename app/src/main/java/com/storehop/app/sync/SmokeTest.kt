package com.storehop.app.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot Firestore connectivity check. Writes a small doc to
 * `/users/{uid}/_smoketest/{timestamp}` shortly after sign-in, logs the result,
 * and exits. **Removed in M4** once the real SyncEngine takes over the same path.
 */
@Singleton
class SmokeTest @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: UserSessionProvider,
    private val applicationScope: CoroutineScope,
) {
    fun start() {
        applicationScope.launch {
            val uid = session.userId.filterNotNull().first()
            val ts = System.currentTimeMillis()
            val doc = mapOf(
                "writtenAt" to ts,
                "from" to "M3 SmokeTest",
            )
            try {
                firestore.collection("users").document(uid)
                    .collection("_smoketest").document(ts.toString())
                    .set(doc).await()
                Log.i(TAG, "Firestore smoke-test write succeeded at users/$uid/_smoketest/$ts")
            } catch (e: Exception) {
                Log.e(TAG, "Firestore smoke-test write FAILED", e)
            }
        }
    }

    companion object {
        private const val TAG = "FirestoreSmokeTest"
    }
}
