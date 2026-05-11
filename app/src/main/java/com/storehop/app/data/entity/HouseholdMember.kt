package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * v0.7.0 multi-user: local mirror of Firestore `/memberships/{uid}/households/{hid}`.
 *
 * A row exists for every (uid, householdId) pairing the device knows about.
 * The user's *active* household is the row with the latest `joinedAt` for
 * the current uid; that drives every other entity's access scope through
 * `HouseholdSessionProvider.householdId`.
 *
 * Composite primary key (uid, householdId) lets one user belong to
 * multiple households in the future (v0.7.x feature) — for v0.7.0 the
 * active-household selection picks exactly one.
 *
 * Soft-delete via `deletedAt` mirrors every other entity so leaving a
 * household + rejoining preserves the timeline. `pendingSync` flips when
 * the local row needs pushing to Firestore (e.g. just joined a household
 * via invite code).
 */
@Entity(
    tableName = "household_members",
    primaryKeys = ["uid", "householdId"],
    indices = [
        Index("uid"),
        Index("householdId"),
        Index("deletedAt"),
    ],
)
data class HouseholdMember(
    val uid: String,
    val householdId: String,
    /** Display name surfaced in the household members list. Nullable so
     *  freshly-joined members show as the email until the inviter's
     *  Google profile name pulls in. */
    val displayName: String?,
    val joinedAt: Long,
    /** True for the household founder. Owners can rename / dissolve the
     *  household in future versions; v0.7.0 treats everyone as equal at
     *  the access-control level (cosmetic badge only). */
    val isOwner: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
)
