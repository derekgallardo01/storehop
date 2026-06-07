import Foundation
import GRDB

/// Loads the bundled stores, categories, and per-store category orderings on
/// first DB creation. Mirrors the Android `DatabaseSeeder` exactly so a fresh
/// install on either platform produces byte-identical seed rows — same IDs,
/// same `SEED_TIMESTAMP`, same `userId = "local-only"`, same display orders.
///
/// Idempotent: rows insert with `ON CONFLICT(...) DO NOTHING`, and the
/// `seedIfEmpty(_:)` entry point gates on the stores table being empty.
struct DatabaseSeeder: Sendable {
    /// Tied to the seed-pack version, not wall-clock time. Bump when the seed
    /// content changes. Matches `DatabaseSeeder.SEED_TIMESTAMP` on Android.
    static let seedTimestamp: Int64 = 1_730_000_000_000  // 2024-10-27T00:00:00Z

    /// Matches `LocalOnlyUserSessionProvider.LOCAL_ONLY` on Android. Pre-auth
    /// rows carry this; the orphan-uid claim path re-stamps them when a real
    /// Firebase uid lands.
    static let localOnlyUserId: String = "local-only"

    /// Run the seed on first DB creation. Safe to call repeatedly: once the
    /// stores table has rows, this is a no-op.
    ///
    /// If the seed JSON bundle resources can't be located (notably under the
    /// CI test host where `type: folder` resources don't always bundle the
    /// same way they do for `xcodebuild build`), the seed is skipped rather
    /// than crashing — an empty store list is a recoverable degraded state,
    /// a fatalError on app launch is not. Production binaries always ship
    /// the seed folder, so users never hit this branch.
    func seedIfEmpty(_ writer: any DatabaseWriter) throws {
        try writer.write { db in
            let storeCount = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM stores") ?? 0
            guard storeCount == 0 else { return }
            do {
                try seedStores(db)
                try seedCategories(db)
                try seedStoreCategoryOrders(db)
            } catch SeederError.missingResource {
                // Seed resources not bundled (CI test host). Continue with an
                // empty DB rather than crashing the app on launch.
                return
            }
        }
    }

    private func seedStores(_ db: Database) throws {
        let stores: [SeedStore] = try loadJsonResource("stores")
        for (index, store) in stores.enumerated() {
            try db.execute(
                sql: """
                INSERT OR IGNORE INTO stores
                (id, name, colorArgb, isArchived, isSeeded, userId, createdAt, updatedAt, deletedAt, displayOrder)
                VALUES (?, ?, NULL, 0, 1, ?, ?, ?, NULL, ?)
                """,
                arguments: [
                    store.id,
                    store.name,
                    Self.localOnlyUserId,
                    Self.seedTimestamp,
                    Self.seedTimestamp,
                    index,
                ]
            )
        }
    }

    private func seedCategories(_ db: Database) throws {
        let categories: [SeedCategory] = try loadJsonResource("categories")
        for category in categories {
            try db.execute(
                sql: """
                INSERT OR IGNORE INTO categories
                (id, name, nameKey, icon, isArchived, isSeeded, userId, createdAt, updatedAt, deletedAt)
                VALUES (?, ?, ?, ?, 0, 1, ?, ?, ?, NULL)
                """,
                arguments: [
                    category.id,
                    category.name,
                    category.nameKey,
                    category.icon,
                    Self.localOnlyUserId,
                    Self.seedTimestamp,
                    Self.seedTimestamp,
                ]
            )
        }
    }

    private func seedStoreCategoryOrders(_ db: Database) throws {
        let orders: [SeedStoreCategoryOrder] = try loadJsonResource("store_categories")
        for order in orders {
            try db.execute(
                sql: """
                INSERT OR IGNORE INTO store_category_order
                (storeId, categoryId, displayOrder, isSeeded, userId, createdAt, updatedAt, deletedAt)
                VALUES (?, ?, ?, 1, ?, ?, ?, NULL)
                """,
                arguments: [
                    order.storeId,
                    order.categoryId,
                    order.displayOrder,
                    Self.localOnlyUserId,
                    Self.seedTimestamp,
                    Self.seedTimestamp,
                ]
            )
        }
    }

    private func loadJsonResource<T: Decodable>(_ name: String) throws -> T {
        // Three-tier lookup, in order of preference:
        //   1. The `seed/` subdirectory of either the test bundle or the
        //      main app bundle — that's the layout local xcodebuild
        //      produces. Most common path.
        //   2. The bundle root, same two bundles, in case the resource
        //      landed flattened.
        //   3. The Swift-string mirror in [BundledSeedJson] — final
        //      fallback for the macos-15 CI runner where xcodegen +
        //      xcodebuild silently refuse to copy the JSONs into the
        //      simulator bundle at all (verified via `find Storehop.app`
        //      returning zero matches). Means production launches in CI
        //      builds still seed correctly even if the bundle is empty.
        let candidates: [Bundle] = [Bundle(for: BundleAnchor.self), .main]
        for bundle in candidates {
            let url = bundle.url(forResource: name, withExtension: "json", subdirectory: "seed")
                ?? bundle.url(forResource: name, withExtension: "json")
            if let url = url {
                let data = try Data(contentsOf: url)
                return try JSONDecoder().decode(T.self, from: data)
            }
        }
        if let embedded = BundledSeedJson.text(forName: name),
           let data = embedded.data(using: .utf8) {
            return try JSONDecoder().decode(T.self, from: data)
        }
        throw SeederError.missingResource(name: name)
    }

    enum SeederError: Error, Equatable {
        case missingResource(name: String)
    }

    private struct SeedStore: Decodable {
        let id: String
        let name: String
    }

    private struct SeedCategory: Decodable {
        let id: String
        let name: String
        let nameKey: String?
        let icon: String?
    }

    private struct SeedStoreCategoryOrder: Decodable {
        let storeId: String
        let categoryId: String
        let displayOrder: Int
    }
}

/// Marker class used as a `Bundle(for:)` anchor so the seeder can locate
/// the test bundle when running under XCTest. Internal to this file; not
/// exposed.
private final class BundleAnchor {}
