import Foundation
import Observation

/// Mirror of Android's `StatisticsViewModel` — aggregates purchase history
/// + library counts into a single `StatisticsUiState` for the Settings →
/// Statistics screen.
///
/// The Android VM combines 11 flows in one `combine` call; SwiftUI doesn't
/// have a direct equivalent, so we maintain a stash of latest values per
/// source and re-derive the UI state whenever any source changes.
@Observable
@MainActor
final class StatisticsViewModel {
    static let trendWindowDays = 84
    static let staleThresholdDays = 60
    static let topItemsLimit = 10
    static let staleItemsLimit = 20
    private static let dayMillis: Int64 = 24 * 60 * 60 * 1000

    var state: StatisticsUiState = .loading

    // Latest values from each source. Whenever any of these change,
    // `recomputeState()` is called to rebuild `state`.
    private var totalCount: Int = 0
    private var last30: Int = 0
    private var last7: Int = 0
    private var perDay: [DayCount] = []
    private var byDow: [DayOfWeekCount] = []
    private var topItemsRaw: [ItemPurchaseCount] = []
    private var byStoreRaw: [StorePurchaseCount] = []
    private var byCategoryRaw: [CategoryPurchaseCount] = []
    private var items: [ItemWithCategoryAndStores] = []
    private var stores: [Store] = []
    private var categories: [Category] = []

    private let now: Int64
    private let thirtyDaysAgo: Int64
    private let sevenDaysAgo: Int64
    private let trendStart: Int64
    private let staleCutoff: Int64

    private let purchases: PurchaseHistoryRepository
    private let itemRepository: ItemRepository
    private let storeRepository: StoreRepository
    private let categoryRepository: CategoryRepository
    private let session: any UserSessionProvider
    private let totalBinder = SessionBinder()
    private let last30Binder = SessionBinder()
    private let last7Binder = SessionBinder()
    private let perDayBinder = SessionBinder()
    private let byDowBinder = SessionBinder()
    private let topItemsBinder = SessionBinder()
    private let byStoreBinder = SessionBinder()
    private let byCategoryBinder = SessionBinder()
    private let itemsBinder = SessionBinder()
    private let storesBinder = SessionBinder()
    private let categoriesBinder = SessionBinder()

    init(
        purchases: PurchaseHistoryRepository,
        itemRepository: ItemRepository,
        storeRepository: StoreRepository,
        categoryRepository: CategoryRepository,
        session: any UserSessionProvider,
        clock: any Clock
    ) {
        self.purchases = purchases
        self.itemRepository = itemRepository
        self.storeRepository = storeRepository
        self.categoryRepository = categoryRepository
        self.session = session

        let now = clock.nowMs()
        self.now = now
        self.thirtyDaysAgo = now - 30 * Self.dayMillis
        self.sevenDaysAgo = now - 7 * Self.dayMillis
        self.trendStart = now - Int64(Self.trendWindowDays) * Self.dayMillis
        self.staleCutoff = now - Int64(Self.staleThresholdDays) * Self.dayMillis
    }

    func bind() {
        let thirtyDaysAgo = self.thirtyDaysAgo
        let sevenDaysAgo = self.sevenDaysAgo
        let trendStart = self.trendStart

        totalBinder.bind(session: session, emptyValue: 0) { [purchases] uid in
            purchases.observeTotalCount(userId: uid)
        } onValue: { [weak self] v in
            self?.totalCount = v
            self?.recomputeState()
        }
        last30Binder.bind(session: session, emptyValue: 0) { [purchases] uid in
            purchases.observeCountSince(userId: uid, sinceMillis: thirtyDaysAgo)
        } onValue: { [weak self] v in
            self?.last30 = v
            self?.recomputeState()
        }
        last7Binder.bind(session: session, emptyValue: 0) { [purchases] uid in
            purchases.observeCountSince(userId: uid, sinceMillis: sevenDaysAgo)
        } onValue: { [weak self] v in
            self?.last7 = v
            self?.recomputeState()
        }
        perDayBinder.bind(session: session, emptyValue: [DayCount]()) { [purchases] uid in
            purchases.observePurchasesPerDay(userId: uid, sinceMillis: trendStart)
        } onValue: { [weak self] v in
            self?.perDay = v
            self?.recomputeState()
        }
        byDowBinder.bind(session: session, emptyValue: [DayOfWeekCount]()) { [purchases] uid in
            purchases.observePurchasesByDayOfWeek(userId: uid)
        } onValue: { [weak self] v in
            self?.byDow = v
            self?.recomputeState()
        }
        topItemsBinder.bind(session: session, emptyValue: [ItemPurchaseCount]()) { [purchases] uid in
            purchases.observeTopItems(userId: uid, limit: Self.topItemsLimit)
        } onValue: { [weak self] v in
            self?.topItemsRaw = v
            self?.recomputeState()
        }
        byStoreBinder.bind(session: session, emptyValue: [StorePurchaseCount]()) { [purchases] uid in
            purchases.observePurchasesByStore(userId: uid)
        } onValue: { [weak self] v in
            self?.byStoreRaw = v
            self?.recomputeState()
        }
        byCategoryBinder.bind(session: session, emptyValue: [CategoryPurchaseCount]()) { [purchases] uid in
            purchases.observePurchasesByCategory(userId: uid)
        } onValue: { [weak self] v in
            self?.byCategoryRaw = v
            self?.recomputeState()
        }
        itemsBinder.bind(session: session, emptyValue: [ItemWithCategoryAndStores]()) { [itemRepository] uid in
            itemRepository.observeAll(userId: uid)
        } onValue: { [weak self] v in
            self?.items = v
            self?.recomputeState()
        }
        storesBinder.bind(session: session, emptyValue: [Store]()) { [storeRepository] uid in
            storeRepository.observeAll(userId: uid, includeArchived: true)
        } onValue: { [weak self] v in
            self?.stores = v
            self?.recomputeState()
        }
        categoriesBinder.bind(session: session, emptyValue: [Category]()) { [categoryRepository] uid in
            categoryRepository.observeAll(userId: uid, includeArchived: true)
        } onValue: { [weak self] v in
            self?.categories = v
            self?.recomputeState()
        }
    }

    func teardown() {
        totalBinder.cancel()
        last30Binder.cancel()
        last7Binder.cancel()
        perDayBinder.cancel()
        byDowBinder.cancel()
        topItemsBinder.cancel()
        byStoreBinder.cancel()
        byCategoryBinder.cancel()
        itemsBinder.cancel()
        storesBinder.cancel()
        categoriesBinder.cancel()
    }

    // MARK: - State derivation

    private func recomputeState() {
        let totalItems = items.count
        let stapleItems = items.filter { $0.item.isStaple }.count
        let priorityItems = items.filter { $0.item.isPriority }.count

        if totalCount == 0 && totalItems == 0 {
            state = .empty
            return
        }

        let itemNamesById = Dictionary(uniqueKeysWithValues: items.map { ($0.item.id, $0.item.name) })
        let storeNamesById = Dictionary(uniqueKeysWithValues: stores.map { ($0.id, $0.name) })
        let categoryNamesById = Dictionary(uniqueKeysWithValues: categories.map { ($0.id, $0.name) })

        let topItems: [NamedCount] = topItemsRaw.compactMap { row in
            guard let name = itemNamesById[row.itemId] else { return nil }
            return NamedCount(name: name, count: row.count)
        }

        let purchasesByStore: [NamedCount] = byStoreRaw.compactMap { row in
            guard let name = storeNamesById[row.storeId] else { return nil }
            return NamedCount(name: name, count: row.count)
        }

        let purchasesByCategory: [NamedCount] = byCategoryRaw.map { row in
            let name: String?
            if row.categoryId.isEmpty {
                name = nil  // surfaced as "Uncategorised" in the UI
            } else {
                name = categoryNamesById[row.categoryId]
            }
            return NamedCount(name: name ?? "", count: row.count)
        }

        let staleItems: [StaleItemRow] = items
            .compactMap { row -> StaleItemRow? in
                guard let last = row.item.lastPurchasedAt,
                      last < staleCutoff,
                      !row.item.isNeeded else { return nil }
                let days = Int((now - last) / Self.dayMillis)
                return StaleItemRow(name: row.item.name, daysSinceLastPurchase: days)
            }
            .sorted { $0.daysSinceLastPurchase > $1.daysSinceLastPurchase }
            .prefix(Self.staleItemsLimit)
            .map { $0 }

        let mostShoppedStore = purchasesByStore.first
        let mostActiveDayOfWeek = byDow.max(by: { $0.count < $1.count })?.dayOfWeek

        state = .ready(
            StatisticsReadyState(
                totalPurchases: totalCount,
                purchasesLast30Days: last30,
                purchasesLast7Days: last7,
                purchasesPerDay: perDay,
                mostActiveDayOfWeek: mostActiveDayOfWeek,
                totalItems: totalItems,
                stapleItems: stapleItems,
                priorityItems: priorityItems,
                topItems: topItems,
                staleItems: staleItems,
                totalStores: stores.filter { !$0.isArchived && $0.deletedAt == nil }.count,
                mostShoppedStore: mostShoppedStore,
                purchasesByStore: purchasesByStore,
                totalCategories: categories.count,
                purchasesByCategory: purchasesByCategory
            )
        )
    }
}

// MARK: - UI state

enum StatisticsUiState: Equatable, Sendable {
    case loading
    case empty
    case ready(StatisticsReadyState)
}

struct StatisticsReadyState: Equatable, Sendable {
    let totalPurchases: Int
    let purchasesLast30Days: Int
    let purchasesLast7Days: Int
    let purchasesPerDay: [DayCount]
    /// 0 = Sunday … 6 = Saturday, nil when there are no purchases yet.
    let mostActiveDayOfWeek: Int?
    let totalItems: Int
    let stapleItems: Int
    let priorityItems: Int
    let topItems: [NamedCount]
    let staleItems: [StaleItemRow]
    let totalStores: Int
    let mostShoppedStore: NamedCount?
    let purchasesByStore: [NamedCount]
    let totalCategories: Int
    let purchasesByCategory: [NamedCount]
}

struct NamedCount: Equatable, Hashable, Identifiable, Sendable {
    let name: String
    let count: Int
    var id: String { name }
}

struct StaleItemRow: Equatable, Hashable, Identifiable, Sendable {
    let name: String
    let daysSinceLastPurchase: Int
    var id: String { name }
}
