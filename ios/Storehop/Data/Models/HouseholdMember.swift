import Foundation
import GRDB

/// v0.7.0 multi-user: local mirror of Firestore
/// `/memberships/{uid}/households/{hid}`.
///
/// A row exists for every (uid, householdId) pairing the device knows about.
/// The user's *active* household is the row with the latest `joinedAt` for
/// the current uid; that drives every other entity's access scope through
/// `HouseholdSessionProvider.householdId`.
///
/// Composite primary key `(uid, householdId)` lets one user belong to
/// multiple households in the future (v0.7.x feature) — for v0.7.0 the
/// active-household selection picks exactly one.
///
/// Soft-delete via `deletedAt` mirrors every other entity so leaving a
/// household + rejoining preserves the timeline. `pendingSync` flips when
/// the local row needs pushing to Firestore (e.g. just joined a household
/// via invite code).
///
/// Mirrors Android `HouseholdMember` entity at
/// `app/src/main/java/com/storehop/app/data/entity/HouseholdMember.kt`.
struct HouseholdMember: Codable, FetchableRecord, MutablePersistableRecord, Hashable, Sendable {
    static let databaseTableName = "household_members"

    var uid: String
    var householdId: String
    /// Display name surfaced in the household members list. Nullable so
    /// freshly-joined members show as the email until the inviter's
    /// Google profile name pulls in.
    var displayName: String?
    var joinedAt: Int64
    /// True for the household founder. Owners can rename / dissolve the
    /// household in future versions; v0.7.0 treats everyone as equal at
    /// the access-control level (cosmetic badge only).
    var isOwner: Bool
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool

    enum Columns {
        static let uid         = Column(CodingKeys.uid)
        static let householdId = Column(CodingKeys.householdId)
        static let displayName = Column(CodingKeys.displayName)
        static let joinedAt    = Column(CodingKeys.joinedAt)
        static let isOwner     = Column(CodingKeys.isOwner)
        static let createdAt   = Column(CodingKeys.createdAt)
        static let updatedAt   = Column(CodingKeys.updatedAt)
        static let deletedAt   = Column(CodingKeys.deletedAt)
        static let pendingSync = Column(CodingKeys.pendingSync)
    }
}
