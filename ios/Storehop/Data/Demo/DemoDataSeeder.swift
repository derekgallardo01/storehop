import Foundation
import GRDB

#if DEBUG

/// DEBUG / marketing-only helper that fills the app with a curated, realistic
/// dataset so landing-page screenshots and the walkthrough tour look complete.
///
/// A fresh install ships with empty shelves — `DatabaseSeeder` seeds only
/// stores/categories/aisles, never items or purchase history — so this builds
/// everything on top via the public repositories (which keep the xref /
/// store-category-order / ownership invariants correct). Purchase history is
/// the one low-level exception: it's inserted straight through
/// `PurchaseRecordDao` with backdated `purchasedAt` so the Statistics screen
/// shows weeks of trend, because the repository purchase path can only stamp
/// "now".
///
/// Port of Android's `com.storehop.app.data.demo.DemoDataSeeder` — same
/// stores, categories, items, flags, and backdated purchase cadence, so the
/// two platforms produce visually matching marketing shots.
///
/// Reused by two consumers: the debug-only Settings loader
/// (`DemoDataDebugSection`) and the `DesignSystemTourTest` screenshot tour
/// (via the `-E2ESeedDemoData` launch argument). The whole file is compiled
/// out of release builds by the surrounding `#if DEBUG`, mirroring the way
/// R8 strips the Kotlin original.
///
/// Items intentionally carry no `imageUrl` — the UI renders its coloured
/// initial-avatar, which keeps the demo free of copyrighted brand imagery.
struct DemoDataSeeder: Sendable {
    /// Reader used only by `clear()` to snapshot the alive rows to remove.
    /// All content writes go through the repositories below.
    let writer: any DatabaseWriter
    let storeRepository: StoreRepository
    let categoryRepository: CategoryRepository
    let itemRepository: ItemRepository
    let storeCategoryOrderRepository: StoreCategoryOrderRepository
    let purchaseRecordDao: PurchaseRecordDao
    let ids: any IdGenerator
    let clock: any Clock
    let session: any UserSessionProvider
    let householdSession: any HouseholdSessionProvider

    // MARK: - Curated dataset

    /// One curated store.
    private struct StoreSpec {
        let key: String
        let name: String
        let colorArgb: Int64
        var oneOff: Bool = false
    }

    /// One curated item. `stores`/`category` reference the keys below.
    private struct ItemSpec {
        let name: String
        let category: String
        let stores: [String]
        var brand: String? = nil
        var staple: Bool = false
        var priority: Bool = false
        var buyToday: Bool = false
        /// Mark purchased "now" so it renders checked-off in the store list.
        var purchased: Bool = false
    }

    private let stores: [StoreSpec] = [
        StoreSpec(key: "continente", name: "Continente", colorArgb: 0xFF1565C0),
        StoreSpec(key: "pingo", name: "Pingo Doce", colorArgb: 0xFF2E7D32),
        StoreSpec(key: "lidl", name: "Lidl", colorArgb: 0xFFF9A825),
        StoreSpec(key: "aldi", name: "Aldi", colorArgb: 0xFF00838F),
        StoreSpec(key: "farmacia", name: "Farmácia", colorArgb: 0xFFC62828, oneOff: true),
    ]

    // Ordered — the list order becomes each store's default aisle order.
    private let categories: [String] = [
        "Produce", "Bakery", "Dairy", "Meat & Fish",
        "Frozen", "Pantry", "Household", "Toiletries",
    ]

    private let items: [ItemSpec] = [
        // Produce
        ItemSpec(name: "Bananas", category: "Produce", stores: ["continente", "pingo"]),
        ItemSpec(name: "Baby Spinach", category: "Produce", stores: ["continente"], brand: "Florette"),
        ItemSpec(name: "Avocados", category: "Produce", stores: ["pingo", "lidl"]),
        ItemSpec(name: "Tomatoes", category: "Produce", stores: ["continente"], purchased: true),
        // Bakery
        ItemSpec(name: "Sourdough Bread", category: "Bakery", stores: ["continente", "pingo"], staple: true),
        ItemSpec(name: "Croissants", category: "Bakery", stores: ["lidl"]),
        // Dairy
        ItemSpec(name: "Whole Milk", category: "Dairy", stores: ["continente", "pingo", "aldi"], brand: "Mimosa", staple: true),
        ItemSpec(name: "Greek Yogurt", category: "Dairy", stores: ["continente"], brand: "Fage"),
        ItemSpec(name: "Butter", category: "Dairy", stores: ["pingo"], staple: true),
        ItemSpec(name: "Eggs", category: "Dairy", stores: ["continente", "aldi"], staple: true, purchased: true),
        // Meat & Fish
        ItemSpec(name: "Chicken Breasts", category: "Meat & Fish", stores: ["continente", "pingo"]),
        ItemSpec(name: "Salmon Fillets", category: "Meat & Fish", stores: ["continente"]),
        ItemSpec(name: "Ground Beef", category: "Meat & Fish", stores: ["aldi"]),
        // Frozen
        ItemSpec(name: "Chicken Wings - Frozen", category: "Frozen", stores: ["lidl", "aldi"]),
        ItemSpec(name: "Garden Peas", category: "Frozen", stores: ["lidl"]),
        ItemSpec(name: "Ice Cream", category: "Frozen", stores: ["continente"], brand: "Olá"),
        // Pantry
        ItemSpec(name: "Coffee", category: "Pantry", stores: ["continente", "pingo"], brand: "Delta", staple: true),
        ItemSpec(name: "Olive Oil", category: "Pantry", stores: ["continente"], brand: "Gallo"),
        ItemSpec(name: "Spaghetti", category: "Pantry", stores: ["lidl", "aldi"], purchased: true),
        ItemSpec(name: "Basmati Rice", category: "Pantry", stores: ["continente"]),
        ItemSpec(name: "Sugar", category: "Pantry", stores: ["pingo", "aldi"]),
        // Household
        ItemSpec(name: "Toilet Paper", category: "Household", stores: ["continente", "pingo"], brand: "Renova", buyToday: true),
        ItemSpec(name: "Dish Soap", category: "Household", stores: ["continente"], brand: "Fairy"),
        ItemSpec(name: "Trash Bags", category: "Household", stores: ["lidl"]),
        ItemSpec(name: "Laundry Detergent", category: "Household", stores: ["aldi"], brand: "Skip", priority: true),
        // Toiletries
        ItemSpec(name: "Toothpaste", category: "Toiletries", stores: ["continente"], brand: "Colgate"),
        ItemSpec(name: "Shampoo", category: "Toiletries", stores: ["pingo"]),
        ItemSpec(name: "Razors", category: "Toiletries", stores: ["continente"], brand: "Gillette"),
        ItemSpec(name: "Ibuprofen", category: "Toiletries", stores: ["farmacia"], brand: "Brufen", priority: true, buyToday: true),
    ]

    // MARK: - Seed

    /// Wipe any existing alive content, then build the curated demo dataset in
    /// the current session scope. Safe to run on an empty DB (screenshot tour)
    /// or on the seeded debug app (archives the shipped stores/categories first
    /// so only the demo set shows).
    func seed() async throws {
        try await clear()

        // Categories first — the item + aisle-order writes reference them.
        // Insert sequentially so `displayOrder` lands in list order (each
        // `addCategory` appends at MAX(displayOrder)+1).
        var categoryIds: [String: String] = [:]
        for name in categories {
            categoryIds[name] = try await categoryRepository.addCategory(name: name, icon: nil)
        }

        // Stores next, sequentially so `displayOrder` follows the array order.
        var storeIds: [String: String] = [:]
        for spec in stores {
            storeIds[spec.key] = try await storeRepository.addStore(
                name: spec.name,
                colorArgb: spec.colorArgb,
                isOneOff: spec.oneOff
            )
        }

        // Give each store a sensible aisle order (the category list order).
        let orderedCategoryIds = categories.compactMap { categoryIds[$0] }
        for storeId in storeIds.values {
            try await storeCategoryOrderRepository.reorderCategoriesForStore(
                storeId: storeId,
                orderedCategoryIds: orderedCategoryIds
            )
        }

        var itemIdByName: [String: String] = [:]
        for spec in items {
            let itemId = try await itemRepository.addItem(
                name: spec.name,
                categoryId: categoryIds[spec.category],
                storeIds: Set(spec.stores.compactMap { storeIds[$0] }),
                quantity: nil,
                notes: nil,
                brand: spec.brand,
                imageUrl: nil,
                isStaple: spec.staple,
                isPriority: spec.priority,
                isBuyToday: spec.buyToday
            )
            itemIdByName[spec.name] = itemId
            if spec.purchased {
                // Renders struck-through in the store list (session-purchased
                // window) and contributes a "now" purchase to the stats.
                if let storeId = spec.stores.compactMap({ storeIds[$0] }).first {
                    try await itemRepository.markPurchasedAtStore(itemId: itemId, storeId: storeId)
                }
            }
        }

        try await seedPurchaseHistory(itemIdByName: itemIdByName, storeIdByKey: storeIds)
    }

    /// Backdated purchase history so Statistics shows weeks of trend, top items,
    /// per-store and per-category bars. Deterministic (no RNG) so screenshots are
    /// reproducible. Skips silently when there's no active session.
    ///
    /// Anchored on `clock.nowMs()` — the same instant `StatisticsViewModel`
    /// uses to compute its trailing windows — so the backdated records always
    /// land inside the trend/last-30/last-7 ranges regardless of whether the
    /// clock is the live `SystemClock` or the E2E `FixedClock`.
    private func seedPurchaseHistory(
        itemIdByName: [String: String],
        storeIdByKey: [String: String]
    ) async throws {
        guard let userId = await session.currentUserId else { return }
        let householdId = (await householdSession.currentHouseholdId) ?? userId
        let now = clock.nowMs()
        let day: Int64 = 24 * 60 * 60 * 1000

        // Items that get bought often (drive "top items" + the trend line),
        // paired with the store they're usually bought at.
        let recurring: [(item: String, store: String)] = [
            ("Whole Milk", "continente"),
            ("Eggs", "continente"),
            ("Sourdough Bread", "pingo"),
            ("Bananas", "continente"),
            ("Coffee", "pingo"),
            ("Butter", "pingo"),
            ("Chicken Breasts", "continente"),
            ("Greek Yogurt", "continente"),
            ("Spaghetti", "lidl"),
            ("Ice Cream", "continente"),
        ]

        // Build the full record set first, then commit in a single
        // transaction (iOS idiom for bulk inserts; the Kotlin original loops
        // `dao.insert` one row at a time).
        var records: [PurchaseRecord] = []
        var count = 0
        // Spread ~8 weeks of shopping trips. Each "trip" is a day a few times
        // a week; on each trip a rotating handful of the recurring items is
        // bought.
        for dayAgo in 1...56 {
            // Shop roughly Mon/Thu/Sat — pick 3 cadence days per week.
            guard [0, 3, 5].contains(dayAgo % 7) else { continue }
            let tripItems = recurring.enumerated().filter { (idx, _) in (idx + dayAgo) % 3 == 0 }
            for (_, entry) in tripItems {
                guard let itemId = itemIdByName[entry.item],
                      let storeId = storeIdByKey[entry.store] else { continue }
                let purchasedAt = now - Int64(dayAgo) * day - Int64(count % 6) * 60 * 60 * 1000
                records.append(
                    PurchaseRecord(
                        id: ids.newId(),
                        itemId: itemId,
                        storeId: storeId,
                        purchasedAt: purchasedAt,
                        userId: userId,
                        createdAt: now,
                        updatedAt: now,
                        deletedAt: nil,
                        pendingSync: true,
                        householdId: householdId
                    )
                )
                count += 1
            }
        }

        guard !records.isEmpty else { return }
        try await writer.write { db in
            for record in records {
                try PurchaseRecordDao.insert(record, on: db)
            }
        }
    }

    // MARK: - Clear

    /// Remove demo (and any other) alive content so a re-seed starts clean:
    /// soft-delete every alive item and archive every alive store + category.
    /// A fresh install still restores the shipped seed; this is debug-only.
    func clear() async throws {
        // The repository read/observe surface is scoped by householdId; the
        // repositories forward the value they receive as `userId:`. Snapshot
        // the alive ids directly (a one-shot read) rather than pulling the
        // first element off each `AsyncValueObservation`, which has no clean
        // "first value" accessor.
        let householdId = try await householdSession.requireHouseholdId()

        let itemIds = try await writer.read { db in
            try String.fetchAll(db, sql: """
                SELECT id FROM items
                WHERE householdId = ? AND deletedAt IS NULL
                """, arguments: [householdId])
        }
        for id in itemIds {
            try await itemRepository.softDelete(id: id)
        }

        let storeIds = try await writer.read { db in
            try String.fetchAll(db, sql: """
                SELECT id FROM stores
                WHERE householdId = ? AND deletedAt IS NULL AND isArchived = 0
                """, arguments: [householdId])
        }
        for id in storeIds {
            try await storeRepository.setArchived(id: id, archived: true)
        }

        let categoryIds = try await writer.read { db in
            try String.fetchAll(db, sql: """
                SELECT id FROM categories
                WHERE householdId = ? AND deletedAt IS NULL AND isArchived = 0
                """, arguments: [householdId])
        }
        for id in categoryIds {
            try await categoryRepository.setArchived(id: id, archived: true)
        }
    }
}

#endif
