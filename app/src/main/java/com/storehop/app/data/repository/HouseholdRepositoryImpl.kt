package com.storehop.app.data.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.PullWriteDao
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.HouseholdSwitcher
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject

class HouseholdRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val householdMemberDao: HouseholdMemberDao,
    private val pullWriteDao: PullWriteDao,
    private val userSession: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
    private val householdSwitcher: HouseholdSwitcher,
    private val clock: Clock,
) : HouseholdRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMembers(): Flow<List<HouseholdMember>> =
        householdSession.householdId.flatMapLatest { hid ->
            if (hid == null) flowOf(emptyList())
            else householdMemberDao.observeMembersOf(hid)
        }

    override suspend fun generateInvite(): InviteCode {
        val uid = userSession.currentUserId()
            ?: throw IllegalStateException("Cannot generate an invite without an authed user")
        val hid = householdSession.currentHouseholdId()
            ?: throw IllegalStateException("No active household yet — auth bootstrap incomplete")
        val now = clock.millis()
        val expiresAt = now + INVITE_TTL_MILLIS
        val token = randomInviteToken()
        firestore.collection(INVITES_COLLECTION).document(token).set(
            mapOf(
                "householdId" to hid,
                "createdBy" to uid,
                "createdAt" to now,
                "expiresAt" to expiresAt,
                "accepted" to false,
                "acceptedBy" to null,
                "acceptedAt" to null,
            ),
        ).await()
        return InviteCode(token = token, expiresAt = expiresAt)
    }

    override suspend fun acceptInvite(token: String): InviteResult = runCatching {
        val uid = userSession.currentUserId()
            ?: return InviteResult.Failed(IllegalStateException("Not signed in"))
        val currentHousehold = householdSession.currentHouseholdId()
            ?: return InviteResult.Failed(IllegalStateException("No active household yet"))
        val doc = firestore.collection(INVITES_COLLECTION).document(token)
        val snap = doc.get().await()
        if (!snap.exists()) return InviteResult.NotFound
        val expiresAt = snap.getLong("expiresAt") ?: 0L
        if (expiresAt < clock.millis()) return InviteResult.Expired
        if (snap.getBoolean("accepted") == true) return InviteResult.AlreadyUsed
        val newHouseholdId = snap.getString("householdId")
            ?: return InviteResult.Failed(IllegalStateException("Invite document missing householdId"))

        val now = clock.millis()

        // Stamp the invite as accepted first; if this write races against
        // another acceptor, the second one's update is dropped by the rules
        // (Phase 6) — the local accept then surfaces as AlreadyUsed on the
        // retry. We can't yet read-then-conditional-write inside a single
        // Firestore transaction here because security rules forbid arbitrary
        // updates; keeping the writes sequential matches the rules' affect-
        // ed-keys whitelist.
        doc.update(
            mapOf(
                "accepted" to true,
                "acceptedBy" to uid,
                "acceptedAt" to now,
            ),
        ).await()

        // Wipe local rows under the user's PRIOR household before flipping.
        // The membership record itself stays — soft-delete preserves the
        // timeline for sync auditing — so a future rejoin path can revive it.
        pullWriteDao.wipeAllForHousehold(currentHousehold)
        if (currentHousehold != uid) {
            // Defensive: only soft-delete the prior membership if it wasn't
            // the user's personal-household row (we don't want to tombstone
            // their own personal hid=uid entry on first-ever invite-accept;
            // they may rejoin themselves via the "leave" flow later).
            householdMemberDao.softDelete(uid, currentHousehold, now)
        }

        // Insert the new membership row. SyncEngine will push it to
        // /memberships/{uid}/households/{hid} on the next tick.
        householdMemberDao.upsert(
            HouseholdMember(
                uid = uid,
                householdId = newHouseholdId,
                displayName = null,
                joinedAt = now,
                isOwner = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        // Flip the active household: re-runs the sync gate against the new
        // path, then publishes the household id so SyncEngine picks it up.
        householdSwitcher.switchToHousehold(newHouseholdId)

        InviteResult.Success(newHouseholdId)
    }.getOrElse { e ->
        Log.e(TAG, "acceptInvite($token) failed", e)
        InviteResult.Failed(e)
    }

    override suspend fun leaveHousehold() {
        val uid = userSession.currentUserId()
            ?: throw IllegalStateException("Cannot leave a household without an authed user")
        val currentHousehold = householdSession.currentHouseholdId()
            ?: throw IllegalStateException("No active household to leave")
        if (currentHousehold == uid) {
            // Already in a personal household — there's nothing to leave.
            return
        }
        val now = clock.millis()

        // Soft-delete the cloud membership first; the cloud write paces
        // last-write-wins so a missed Firestore round-trip just leaves the
        // membership row pending sync. The local wipe + switch is what the
        // user sees immediately.
        runCatching {
            firestore.collection(MEMBERSHIPS_COLLECTION)
                .document(uid)
                .collection(HOUSEHOLDS_COLLECTION)
                .document(currentHousehold)
                .update(
                    mapOf(
                        "deletedAt" to now,
                        "updatedAt" to now,
                    ),
                )
                .await()
        }.onFailure { Log.w(TAG, "Cloud membership soft-delete failed; continuing locally", it) }

        pullWriteDao.wipeAllForHousehold(currentHousehold)
        householdMemberDao.softDelete(uid, currentHousehold, now)

        // Insert / revive the personal household row so the user lands in
        // hid=uid rather than a null scope. The seed pack repopulates on
        // first observation since the wipe also nuked the seeded rows that
        // came from the inviting household.
        householdMemberDao.upsert(
            HouseholdMember(
                uid = uid,
                householdId = uid,
                displayName = null,
                joinedAt = now,
                isOwner = true,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        householdSwitcher.switchToHousehold(uid)
    }

    /**
     * 8-character Crockford base32 (no 0/O/1/I/L) so users can dictate the
     * code over a phone call without confusion. ~10^12 entropy is ample
     * given the 24-hour TTL + single-use rule.
     */
    private fun randomInviteToken(): String {
        val sb = StringBuilder(INVITE_TOKEN_LENGTH)
        repeat(INVITE_TOKEN_LENGTH) {
            sb.append(INVITE_ALPHABET[secureRandom.nextInt(INVITE_ALPHABET.length)])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "HouseholdRepository"
        private const val INVITES_COLLECTION = "invites"
        private const val MEMBERSHIPS_COLLECTION = "memberships"
        private const val HOUSEHOLDS_COLLECTION = "households"
        private const val INVITE_TOKEN_LENGTH = 8
        private const val INVITE_TTL_MILLIS = 24L * 60 * 60 * 1000

        // Crockford base32: 0-9 + A-Z minus the visually-ambiguous 0/O/1/I/L.
        // Lowercase variants normalise via uppercase on input (handled in UI).
        private const val INVITE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        private val secureRandom = SecureRandom()
    }
}

