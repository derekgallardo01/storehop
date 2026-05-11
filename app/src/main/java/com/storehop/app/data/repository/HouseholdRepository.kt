package com.storehop.app.data.repository

import com.storehop.app.data.entity.HouseholdMember
import kotlinx.coroutines.flow.Flow

/**
 * v0.7.0 Phase 3: the Settings → Household screen API.
 *
 * Three flows mediate the multi-user sharing experience:
 *  - generate an invite code (Mike shares it out-of-band with Amanda)
 *  - accept an invite code (Amanda joins Mike's household; her local DB
 *    wipes + re-pulls from Mike's household path)
 *  - leave a household (drops local data, returns to a personal household
 *    with hid = uid)
 *
 * Plus a passive view of the current members for the Household screen's
 * member list.
 */
interface HouseholdRepository {

    /**
     * Live view of every member of the current household. Drives the
     * Settings → Household member list. Empty when the user isn't signed
     * in. For single-member households this emits a list of size 1.
     */
    fun observeMembers(): Flow<List<HouseholdMember>>

    /**
     * Generate a fresh 8-character invite code, write it to Firestore at
     * `/invites/{token}` with expiresAt = now + 24h, and return the
     * token. Throws if the user isn't signed in.
     *
     * Tokens use Crockford base32 (excludes 0/O/1/I/L) so users can
     * dictate codes over a phone call without confusion.
     */
    suspend fun generateInvite(): InviteCode

    /**
     * Atomically:
     *   1. Read `/invites/{token}` from Firestore.
     *   2. Validate (exists, not expired, not already used).
     *   3. Write a membership row under `/memberships/{currentUid}/households/{invite.householdId}`.
     *   4. Stamp the invite as accepted by this uid.
     *   5. Wipe every local household-scoped row (items, stores, categories,
     *      xrefs, SCOs, purchase records). The HouseholdSessionProvider
     *      flip then triggers a fresh pull from the new household's path.
     *
     * Returns [InviteResult.Success] with the joined household's id on
     * success, or one of the typed failure variants for the UI to render.
     */
    suspend fun acceptInvite(token: String): InviteResult

    /**
     * Leave the current household. Drops local rows, removes the local
     * membership, and (for the user's *own* personal household, an
     * already-personal household member) creates a fresh personal
     * household so the user lands on an empty list rather than a null
     * scope. Does not delete the cloud-side household — Mike continues
     * to see the data after Amanda leaves.
     */
    suspend fun leaveHousehold()
}

/** A freshly-generated invite code, ready to share. */
data class InviteCode(
    /** The 8-character Crockford base32 token. */
    val token: String,
    /** Epoch millis when the token expires (now + 24h on generation). */
    val expiresAt: Long,
)

/** Result of an invite-accept attempt. */
sealed class InviteResult {
    /** Accepted; the new active household id is included. */
    data class Success(val householdId: String) : InviteResult()

    /** No `/invites/{token}` document for this code. Wrong token. */
    object NotFound : InviteResult()

    /** Token exists but its expiresAt is in the past. */
    object Expired : InviteResult()

    /** Token was already redeemed by another user. */
    object AlreadyUsed : InviteResult()

    /** Network / permissions / unexpected error. Surface the cause for logging. */
    data class Failed(val cause: Throwable) : InviteResult()
}
