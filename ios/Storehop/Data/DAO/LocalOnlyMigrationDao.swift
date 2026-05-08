import Foundation
import GRDB

/// One-shot migration that re-stamps every row whose `userId` is the
/// pre-Firebase `"local-only"` sentinel onto the first real Firebase uid the
/// device acquires.
///
/// Runs from `FirebaseAuthSessionProvider` (Phase 4) before each uid change
/// is published to observers, so the public userId stream only flips after
/// the rows have been re-stamped. Idempotent — subsequent runs find no
/// `local-only` rows and no-op.
///
/// Also handles the orphan-uid case: if `linkWithCredential` failed during
/// Google sign-in and the flow fell back to a plain `signInWithCredential`
/// under a different uid, the rows the user wrote under the prior anonymous
/// uid would otherwise be invisible. Single-user v1 means all data on this
/// device is the active user's — so claim it.
struct LocalOnlyMigrationDao: Sendable {
    let writer: any DatabaseWriter

    private static let localOnly = DatabaseSeeder.localOnlyUserId

    private static let tables: [String] = [
        "items",
        "categories",
        "stores",
        "item_store_xref",
        "store_category_order",
        "purchase_records",
    ]

    /// Re-stamp every `local-only` row onto `uid`. Wraps all six entity
    /// updates in a single transaction so a partial claim never reaches
    /// disk.
    func claimAllLocalOnlyRowsAs(uid: String) async throws {
        precondition(uid != Self.localOnly, "Cannot claim local-only rows back to the local-only sentinel")
        try await writer.write { db in
            for table in Self.tables {
                try db.execute(
                    sql: "UPDATE \(table) SET userId = ? WHERE userId = ?",
                    arguments: [uid, Self.localOnly]
                )
            }
        }
    }

    /// Re-stamp every row whose `userId` is neither `local-only` nor the
    /// current session uid onto the current session uid. Idempotent: when
    /// no orphan rows exist, the UPDATEs are no-ops.
    func claimAllOrphanRowsAs(uid: String) async throws {
        precondition(uid != Self.localOnly, "Cannot claim orphan rows back to the local-only sentinel")
        try await writer.write { db in
            for table in Self.tables {
                try db.execute(
                    sql: "UPDATE \(table) SET userId = ? WHERE userId != ? AND userId != ?",
                    arguments: [uid, uid, Self.localOnly]
                )
            }
        }
    }

    func countLocalOnlyStores() async throws -> Int {
        try await writer.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM stores WHERE userId = ?", arguments: [Self.localOnly]) ?? 0
        }
    }

    func countOrphanStores(uid: String) async throws -> Int {
        try await writer.read { db in
            try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM stores
                WHERE userId != ? AND userId != ?
                """, arguments: [uid, Self.localOnly]) ?? 0
        }
    }
}
