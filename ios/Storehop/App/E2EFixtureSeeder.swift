import Foundation
import GRDB

/// Canonical fixture data for E2E UI tests. Mirrors Android's
/// `seedE2EFixtures` (under `app/src/androidTest/.../testing/E2EFixtures.kt`):
///
///   - 2 stores: "Lidl", "Aldi" (in that order, so Lidl appears first)
///   - 1 category: "Dairy"
///   - 3 items: "Milk" (tagged at both stores, needed), "Eggs" (Lidl only,
///     needed), "Bread" (no tagged stores — exercises the "+/- disabled"
///     branch on the Items list)
///
/// All rows alive, `pendingSync = true` (won't matter; the e2e SyncEngine
/// has a no-op Firestore client so nothing leaves the device).
///
/// Invoked by `AppContainer.e2e(seedFixtures: true)` when the host app
/// detects the `-E2ESeedFixtures` launch argument from XCUITest. Lives in
/// the production target (not StorehopTests) because the UI test runner
/// runs the host app in a separate process and can't reach into a test-
/// only target's symbols.
enum E2EFixtureSeeder {

    /// The single uid every E2E test runs under. Matches
    /// `LocalOnlyUserSessionProvider`'s default uid (`local-only`) so the
    /// data layer sees one consistent identity end-to-end.
    static let uid: String = DatabaseSeeder.localOnlyUserId

    enum Ids {
        static let storeLidl     = "e2e_store_lidl"
        static let storeAldi     = "e2e_store_aldi"
        static let categoryDairy = "e2e_cat_dairy"
        static let itemMilk      = "e2e_item_milk"
        static let itemEggs      = "e2e_item_eggs"
        static let itemBread     = "e2e_item_bread"
    }

    static func seed(database: StorehopDatabase) throws {
        let now: Int64 = 1_000
        try database.queue.write { db in
            var lidl = Store(
                id: Ids.storeLidl, name: "Lidl", colorArgb: nil,
                isArchived: false, isSeeded: false, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, displayOrder: 0, householdId: uid
            )
            try lidl.upsert(db)
            var aldi = Store(
                id: Ids.storeAldi, name: "Aldi", colorArgb: nil,
                isArchived: false, isSeeded: false, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, displayOrder: 1, householdId: uid
            )
            try aldi.upsert(db)
            var dairy = Storehop.Category(
                id: Ids.categoryDairy, name: "Dairy", nameKey: nil, icon: nil,
                isArchived: false, isSeeded: false, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, displayOrder: 0, householdId: uid
            )
            try dairy.upsert(db)

            var milk = Item(
                id: Ids.itemMilk, name: "Milk",
                categoryId: Ids.categoryDairy, notes: nil, quantity: nil,
                isNeeded: true, lastPurchasedAt: nil,
                userId: uid, createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, brand: nil, imageUrl: nil,
                isStaple: false, isPriority: false, householdId: uid
            )
            try milk.upsert(db)
            var eggs = Item(
                id: Ids.itemEggs, name: "Eggs",
                categoryId: nil, notes: nil, quantity: nil,
                isNeeded: true, lastPurchasedAt: nil,
                userId: uid, createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, brand: nil, imageUrl: nil,
                isStaple: false, isPriority: false, householdId: uid
            )
            try eggs.upsert(db)
            var bread = Item(
                id: Ids.itemBread, name: "Bread",
                categoryId: nil, notes: nil, quantity: nil,
                isNeeded: true, lastPurchasedAt: nil,
                userId: uid, createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, brand: nil, imageUrl: nil,
                isStaple: false, isPriority: false, householdId: uid
            )
            try bread.upsert(db)

            // Milk → tagged at both stores, needed.
            var milkAtLidl = ItemStoreXref(
                itemId: Ids.itemMilk, storeId: Ids.storeLidl, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil,
                householdId: uid
            )
            try milkAtLidl.upsert(db)
            var milkAtAldi = ItemStoreXref(
                itemId: Ids.itemMilk, storeId: Ids.storeAldi, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil,
                householdId: uid
            )
            try milkAtAldi.upsert(db)
            // Eggs → tagged at Lidl only, needed.
            var eggsAtLidl = ItemStoreXref(
                itemId: Ids.itemEggs, storeId: Ids.storeLidl, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil,
                householdId: uid
            )
            try eggsAtLidl.upsert(db)
            // Bread → no tagged stores. Exercises the +/- disabled branch.
        }
    }

    /// Add a priority-flagged "Coffee" item tagged at Lidl. Used by the
    /// critical-banner E2E to give the in-store banner something to render.
    /// Mirrors the extra `itemDao.upsert(... isPriority = true)` block in
    /// Android's `CriticalBannerCollapseE2ETest.setUp`.
    static func seedPriorityCoffeeAtLidl(database: StorehopDatabase) throws {
        let now: Int64 = 1_000
        try database.queue.write { db in
            var coffee = Item(
                id: "e2e_item_coffee", name: "Coffee",
                categoryId: nil, notes: nil, quantity: nil,
                isNeeded: true, lastPurchasedAt: nil,
                userId: uid, createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, brand: nil, imageUrl: nil,
                isStaple: false, isPriority: true, householdId: uid
            )
            try coffee.upsert(db)
            var coffeeAtLidl = ItemStoreXref(
                itemId: "e2e_item_coffee", storeId: Ids.storeLidl, userId: uid,
                createdAt: now, updatedAt: now, deletedAt: nil,
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil,
                householdId: uid
            )
            try coffeeAtLidl.upsert(db)
        }
    }
}
