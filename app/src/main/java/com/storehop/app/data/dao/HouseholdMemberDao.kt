package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.HouseholdMember
import kotlinx.coroutines.flow.Flow

/**
 * Local cache of `/memberships/{uid}/households/{hid}` Firestore rows.
 *
 * v0.7.0 scope: queries serve [HouseholdSessionProvider] (which household
 * is this uid's active one?) and the Settings → Household screen (who else
 * is in my household?).
 */
@Dao
interface HouseholdMemberDao {

    /** Returns this uid's active household — newest non-tombstoned join.
     *  Null when the device hasn't joined any household yet (fresh install
     *  before [com.storehop.app.auth.FirebaseAuthSessionProvider] runs its
     *  first-launch bootstrap). */
    @Query(
        """
        SELECT * FROM household_members
        WHERE uid = :uid AND deletedAt IS NULL
        ORDER BY joinedAt DESC
        LIMIT 1
        """,
    )
    suspend fun activeMembershipFor(uid: String): HouseholdMember?

    /** Same as [activeMembershipFor] but live; the session provider
     *  observes this to react when the user joins / leaves a household. */
    @Query(
        """
        SELECT * FROM household_members
        WHERE uid = :uid AND deletedAt IS NULL
        ORDER BY joinedAt DESC
        LIMIT 1
        """,
    )
    fun observeActiveMembershipFor(uid: String): Flow<HouseholdMember?>

    /** Every member of [householdId]. Drives the Settings → Household
     *  member list. */
    @Query(
        """
        SELECT * FROM household_members
        WHERE householdId = :householdId AND deletedAt IS NULL
        ORDER BY joinedAt ASC
        """,
    )
    fun observeMembersOf(householdId: String): Flow<List<HouseholdMember>>

    @Upsert
    suspend fun upsert(member: HouseholdMember)

    /** Soft-delete: keeps the row for sync auditing but removes the user
     *  from the active membership query. */
    @Query(
        """
        UPDATE household_members
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE uid = :uid AND householdId = :householdId
        """,
    )
    suspend fun softDelete(uid: String, householdId: String, now: Long)

    @Query(
        "SELECT * FROM household_members WHERE pendingSync = 1",
    )
    fun observePendingPush(): Flow<List<HouseholdMember>>

    /**
     * v0.7.1: row-count of pending pushes for the Force-sync-now UX.
     *
     * Unlike the other entity DAOs, household memberships aren't scoped
     * by householdId (a user's `/memberships/{uid}/households/...` is
     * per-user; their household isn't the access scope). Force-sync's
     * "all pending" sum aggregates this count alongside the
     * householdId-scoped counts from the other DAOs.
     */
    @Query("SELECT COUNT(*) FROM household_members WHERE pendingSync = 1")
    fun countPendingPush(): Flow<Int>

    @Query(
        "UPDATE household_members SET pendingSync = 0 WHERE uid = :uid AND householdId = :householdId",
    )
    suspend fun markPushed(uid: String, householdId: String)
}
