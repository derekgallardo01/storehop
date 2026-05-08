import Foundation
import GRDB

/// Owns the live `DatabaseQueue` for the app. Creation runs migrations and
/// the first-time seed so the caller gets back a database that's ready to
/// use. There is exactly one of these in the app — held by `AppContainer`.
///
/// Foreign key checks are enabled at connection time. The Android Room layer
/// enforces FKs by default; we match that so cascade-on-delete behavior is
/// identical (even though the app never hard-deletes; the cascades are
/// belt-and-braces against bugs).
final class StorehopDatabase: Sendable {
    let queue: DatabaseQueue

    init(queue: DatabaseQueue) {
        self.queue = queue
    }

    /// Production database, persisted to the app's Application Support directory.
    static func live() throws -> StorehopDatabase {
        let url = try defaultDatabaseURL()
        var config = Configuration()
        config.foreignKeysEnabled = true
        config.prepareDatabase { db in
            try db.execute(sql: "PRAGMA journal_mode = WAL")
        }
        let queue = try DatabaseQueue(path: url.path, configuration: config)
        try Migrations.migrator().migrate(queue)
        try DatabaseSeeder().seedIfEmpty(queue)
        return StorehopDatabase(queue: queue)
    }

    /// In-memory database for tests. Migrations applied; no seed unless the
    /// caller invokes `DatabaseSeeder` themselves (some tests want a known
    /// empty DB; others want the seed pack).
    static func inMemoryForTests() throws -> StorehopDatabase {
        var config = Configuration()
        config.foreignKeysEnabled = true
        let queue = try DatabaseQueue(configuration: config)
        try Migrations.migrator().migrate(queue)
        return StorehopDatabase(queue: queue)
    }

    private static func defaultDatabaseURL() throws -> URL {
        let dir = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return dir.appendingPathComponent("storehop.sqlite")
    }
}
