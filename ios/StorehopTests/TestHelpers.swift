import Foundation
import GRDB
@testable import Storehop

/// Shared helpers for DAO tests. Keeps test bodies focused on the behavior
/// under test rather than fixture plumbing.
enum TestFixtures {

    /// Build a minimal Item with sensible defaults; pass overrides as needed.
    ///
    /// v0.7.0: `householdId` defaults to the same value as `userId` so
    /// fixtures land in a coherent single-member household. Tests that
    /// need to exercise cross-household isolation can override it.
    static func item(
        id: String = "i1",
        name: String = "Apple",
        categoryId: String? = nil,
        userId: String = "u1",
        householdId: String? = nil,
        isNeeded: Bool = true,
        isStaple: Bool = false,
        isPriority: Bool = false,
        now: Int64 = 0
    ) -> Item {
        Item(
            id: id,
            name: name,
            categoryId: categoryId,
            notes: nil,
            quantity: nil,
            isNeeded: isNeeded,
            lastPurchasedAt: nil,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            brand: nil,
            imageUrl: nil,
            isStaple: isStaple,
            isPriority: isPriority,
            householdId: householdId ?? userId
        )
    }

    static func store(
        id: String = "s1",
        name: String = "Lidl",
        userId: String = "u1",
        householdId: String? = nil,
        isArchived: Bool = false,
        isSeeded: Bool = false,
        displayOrder: Int = 0,
        now: Int64 = 0
    ) -> Store {
        Store(
            id: id,
            name: name,
            colorArgb: nil,
            isArchived: isArchived,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            displayOrder: displayOrder,
            householdId: householdId ?? userId
        )
    }

    static func category(
        id: String = "c1",
        name: String = "Produce",
        nameKey: String? = nil,
        icon: String? = nil,
        userId: String = "u1",
        householdId: String? = nil,
        isSeeded: Bool = false,
        now: Int64 = 0,
        displayOrder: Int = 0
    ) -> Storehop.Category {
        Storehop.Category(
            id: id,
            name: name,
            nameKey: nameKey,
            icon: icon,
            isArchived: false,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            displayOrder: displayOrder,
            householdId: householdId ?? userId
        )
    }

    static func xref(
        itemId: String,
        storeId: String,
        userId: String = "u1",
        householdId: String? = nil,
        isNeeded: Bool = true,
        lastPurchasedAt: Int64? = nil,
        now: Int64 = 0
    ) -> ItemStoreXref {
        ItemStoreXref(
            itemId: itemId,
            storeId: storeId,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            isNeeded: isNeeded,
            lastPurchasedAt: lastPurchasedAt,
            householdId: householdId ?? userId
        )
    }

    static func sco(
        storeId: String,
        categoryId: String,
        displayOrder: Int = 0,
        userId: String = "u1",
        householdId: String? = nil,
        isSeeded: Bool = false,
        now: Int64 = 0
    ) -> StoreCategoryOrder {
        StoreCategoryOrder(
            storeId: storeId,
            categoryId: categoryId,
            displayOrder: displayOrder,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            householdId: householdId ?? userId
        )
    }
}

extension StorehopDatabase {
    /// Insert raw fixture rows. Tests use this to set up known state without
    /// going through DAOs that themselves are under test.
    func seed(
        items: [Item] = [],
        stores: [Store] = [],
        categories: [Storehop.Category] = [],
        xrefs: [ItemStoreXref] = [],
        scoOrders: [StoreCategoryOrder] = [],
        purchaseRecords: [PurchaseRecord] = []
    ) throws {
        try queue.write { db in
            for var c in categories { try c.insert(db) }
            for var s in stores { try s.insert(db) }
            for var i in items { try i.insert(db) }
            for var x in xrefs { try x.insert(db) }
            for var sco in scoOrders { try sco.insert(db) }
            for var p in purchaseRecords { try p.insert(db) }
        }
    }
}
