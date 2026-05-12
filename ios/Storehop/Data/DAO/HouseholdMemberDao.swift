import Foundation
import GRDB

/// Local cache of `/memberships/{uid}/households/{hid}` Firestore rows.
///
/// v0.7.0 scope: queries serve `HouseholdSessionProvider` (which household
/// is this uid's active one?) and the Settings → Household screen (who else
/// is in my household?).
///
/// Mirrors Android `HouseholdMemberDao` at
/// `app/src/main/java/com/storehop/app/data/dao/HouseholdMemberDao.kt`.
struct HouseholdMemberDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Snapshot reads

    /// Returns this uid's active household — newest non-tombstoned join.
    /// Nil when the device hasn't joined any household yet (fresh install
    /// before `FirebaseAuthSessionProvider` runs its first-launch bootstrap).
    func activeMembershipFor(uid: String) async throws -> HouseholdMember? {
        try await writer.read { db in try Self.activeMembershipFor(on: db, uid: uid) }
    }

    static func activeMembershipFor(on db: Database, uid: String) throws -> HouseholdMember? {
        try HouseholdMember.fetchOne(db, sql: """
            SELECT * FROM household_members
            WHERE uid = ? AND deletedAt IS NULL
            ORDER BY joinedAt DESC
            LIMIT 1
            """, arguments: [uid])
    }

    // MARK: - Reactive

    /// Same as `activeMembershipFor` but live; the session provider
    /// observes this to react when the user joins / leaves a household.
    func observeActiveMembershipFor(uid: String) -> AsyncValueObservation<HouseholdMember?> {
        ValueObservation
            .tracking { db in
                try HouseholdMember.fetchOne(db, sql: """
                    SELECT * FROM household_members
                    WHERE uid = ? AND deletedAt IS NULL
                    ORDER BY joinedAt DESC
                    LIMIT 1
                    """, arguments: [uid])
            }
            .values(in: writer)
    }

    /// Every member of `householdId`. Drives the Settings → Household
    /// member list.
    func observeMembersOf(householdId: String) -> AsyncValueObservation<[HouseholdMember]> {
        ValueObservation
            .tracking { db in
                try HouseholdMember.fetchAll(db, sql: """
                    SELECT * FROM household_members
                    WHERE householdId = ? AND deletedAt IS NULL
                    ORDER BY joinedAt ASC
                    """, arguments: [householdId])
            }
            .values(in: writer)
    }

    func observePendingPush() -> AsyncValueObservation<[HouseholdMember]> {
        ValueObservation
            .tracking { db in
                try HouseholdMember.fetchAll(db, sql: """
                    SELECT * FROM household_members WHERE pendingSync = 1
                    """)
            }
            .values(in: writer)
    }

    /// v0.7.1: row-count of pending pushes for the Force-sync-now UX.
    /// Unlike entity DAOs, memberships are uid-scoped not household-
    /// scoped, so no householdId parameter. Force-sync aggregates this
    /// count alongside the household-scoped counts from the other DAOs.
    func countPendingPush() -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM household_members WHERE pendingSync = 1") ?? 0
            }
            .values(in: writer)
    }

    // MARK: - Writes

    func upsert(_ member: HouseholdMember) async throws {
        try await writer.write { db in
            var copy = member
            try copy.upsert(db)
        }
    }

    /// Soft-delete: keeps the row for sync auditing but removes the user
    /// from the active membership query.
    func softDelete(uid: String, householdId: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE household_members
                SET deletedAt = ?, updatedAt = ?, pendingSync = 1
                WHERE uid = ? AND householdId = ?
                """, arguments: [now, now, uid, householdId])
        }
    }

    func markPushed(uid: String, householdId: String) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE household_members SET pendingSync = 0
                WHERE uid = ? AND householdId = ?
                """, arguments: [uid, householdId])
        }
    }
}
