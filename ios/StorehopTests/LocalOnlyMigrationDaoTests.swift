import XCTest
import GRDB
@testable import Storehop

final class LocalOnlyMigrationDaoTests: XCTestCase {

    private let local = DatabaseSeeder.localOnlyUserId

    private func setup() throws -> (StorehopDatabase, LocalOnlyMigrationDao) {
        let db = try StorehopDatabase.inMemoryForTests()
        return (db, LocalOnlyMigrationDao(writer: db.queue))
    }

    func testClaimAllLocalOnlyRowsMovesEverySentinelRowToTheNewUid() async throws {
        let (db, dao) = try setup()
        try db.seed(
            items: [TestFixtures.item(id: "i1", userId: local)],
            stores: [TestFixtures.store(id: "s1", userId: local)],
            categories: [TestFixtures.category(id: "c1", userId: local)],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: "s1", userId: local)],
            scoOrders: [TestFixtures.sco(storeId: "s1", categoryId: "c1", userId: local)]
        )
        try await dao.claimAllLocalOnlyRowsAs(uid: "uid_alice")

        let counts = try await db.queue.read { conn -> [String: Int] in
            var out: [String: Int] = [:]
            for table in ["items", "categories", "stores", "item_store_xref", "store_category_order"] {
                let n = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM \(table) WHERE userId = 'uid_alice'") ?? -1
                out[table] = n
            }
            return out
        }
        XCTAssertEqual(counts["items"], 1)
        XCTAssertEqual(counts["stores"], 1)
        XCTAssertEqual(counts["categories"], 1)
        XCTAssertEqual(counts["item_store_xref"], 1)
        XCTAssertEqual(counts["store_category_order"], 1)
    }

    func testClaimAllLocalOnlyRowsIsIdempotent() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [TestFixtures.store(id: "s1", userId: local)])
        try await dao.claimAllLocalOnlyRowsAs(uid: "uid_alice")
        try await dao.claimAllLocalOnlyRowsAs(uid: "uid_alice")  // no-op, no rows left at sentinel

        let stillLocal = try await dao.countLocalOnlyStores()
        XCTAssertEqual(stillLocal, 0)
    }

    func testClaimAllOrphanRowsLeavesLocalOnlyAndCurrentUntouched() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [
            TestFixtures.store(id: "s_local", userId: local),
            TestFixtures.store(id: "s_alice", userId: "uid_alice"),
            TestFixtures.store(id: "s_bob", userId: "uid_bob"),
        ])
        try await dao.claimAllOrphanRowsAs(uid: "uid_alice")

        let bobCount = try await db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores WHERE userId = 'uid_bob'") ?? -1
        }
        let aliceCount = try await db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores WHERE userId = 'uid_alice'") ?? -1
        }
        let localCount = try await db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores WHERE userId = ?", arguments: [self.local]) ?? -1
        }
        XCTAssertEqual(bobCount, 0, "Bob's orphan row should be claimed by Alice")
        XCTAssertEqual(aliceCount, 2, "Alice keeps her row + claims Bob's")
        XCTAssertEqual(localCount, 1, "local-only sentinel rows must NOT be touched by orphan claim")
    }

    func testCountOrphanStoresIgnoresLocalOnly() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [
            TestFixtures.store(id: "s_local", userId: local),
            TestFixtures.store(id: "s_orphan", userId: "uid_other"),
            TestFixtures.store(id: "s_self", userId: "uid_alice"),
        ])
        let n = try await dao.countOrphanStores(uid: "uid_alice")
        XCTAssertEqual(n, 1, "local-only and current uid must be excluded from orphan count")
    }
}
