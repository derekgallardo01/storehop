package com.storehop.app.data.prefs

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud sync for [UserPreferencesRepository] — added in v0.7.1 so the user's
 * theme / language / sort-mode choices survive an uninstall + reinstall
 * across signing-cert boundaries (the Mike sideload → Play Store transition).
 *
 * Per-user, not per-household: preferences are personal. Doc path:
 * `/userPrefs/{uid}`. Security rules in `firestore.rules` scope read/write
 * to `request.auth.uid == uid`.
 *
 * ## Reconcile semantics — `reconcile(uid)`
 *
 *  1. Pull `/userPrefs/{uid}`.
 *  2. If cloud is **absent** OR `local.updatedAt > cloud.updatedAt` → push
 *     local → cloud. This is the load-bearing case for Mike's first run of
 *     v0.7.1: the in-place upgrade preserved his DataStore but his prefs
 *     were never captured to cloud before. Reconcile fires on the next
 *     auth-state-stream emission and pushes immediately — even if Mike
 *     never opens Settings.
 *  3. If `cloud.updatedAt > local.updatedAt` → write cloud → DataStore.
 *     This is the post-Play-install rehydrate case: fresh local store
 *     (defaults) gets overwritten with the user's cloud copy on first
 *     Google sign-in.
 *  4. If equal → no-op.
 *
 * After reconcile, [reconcile] starts a continuous observe-and-push loop
 * with a 500 ms debounce. Cancels any prior loop, so cycling auth states
 * doesn't leak coroutines.
 *
 * ## Failure mode
 *
 * Network errors / `PERMISSION_DENIED` are swallowed (logged at WARN). Local
 * state is intact; next reconcile retries. This keeps the app functional
 * if Firestore rules haven't been deployed yet — preferences just don't
 * sync until they have.
 */
@Singleton
class UserPreferencesSync @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val prefs: UserPreferencesRepository,
    private val applicationScope: CoroutineScope,
) {
    private val reconcileMutex = Mutex()
    @Volatile private var pushLoop: Job? = null

    @OptIn(FlowPreview::class)
    suspend fun reconcile(uid: String) = reconcileMutex.withLock {
        if (uid.isBlank()) return@withLock

        try {
            val cloudSnap = firestore.collection(COLLECTION).document(uid).get().await()
            val cloud: UserPreferencesDto? = if (cloudSnap.exists()) {
                cloudSnap.toObject(UserPreferencesDto::class.java)
            } else null
            val local = prefs.currentSnapshot()

            when {
                cloud == null -> {
                    pushSnapshot(uid, local)
                }
                local.updatedAt > cloud.updatedAt -> {
                    pushSnapshot(uid, local)
                }
                cloud.updatedAt > local.updatedAt -> {
                    prefs.applyRemoteSnapshot(cloud.toSnapshot())
                }
                // else: equal, no-op.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reconcile failed for uid=$uid: ${e.message}")
        }

        // (Re)start the push-on-change loop for this uid.
        pushLoop?.cancel()
        pushLoop = applicationScope.launch {
            prefs.snapshot
                .drop(1) // skip current value (reconcile already pushed if needed)
                .debounce(DEBOUNCE_MS)
                .collectLatest { snapshot -> pushSnapshot(uid, snapshot) }
        }
    }

    /** Awaits any pending debounce + synchronously pushes the current
     *  snapshot. Called by `SyncEngine.flushAllPending` before its
     *  "Safe to uninstall" signal. */
    suspend fun flushPending(uid: String) {
        if (uid.isBlank()) return
        pushSnapshot(uid, prefs.currentSnapshot())
    }

    /** Tear down on sign-out. The push loop holds a reference to the
     *  uid that's no longer authoritative. */
    fun stop() {
        pushLoop?.cancel()
        pushLoop = null
    }

    private suspend fun pushSnapshot(uid: String, snapshot: UserPreferencesSnapshot) {
        try {
            firestore.collection(COLLECTION)
                .document(uid)
                .set(snapshot.toDto())
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Push prefs failed for uid=$uid: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "UserPreferencesSync"
        const val COLLECTION = "userPrefs"
        const val DEBOUNCE_MS = 500L
    }
}
