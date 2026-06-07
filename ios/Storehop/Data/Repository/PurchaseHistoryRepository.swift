import Foundation
import GRDB

struct PurchaseHistoryRepository: Sendable {
    let purchaseDao: PurchaseRecordDao
    let session: any UserSessionProvider
    let clock: any Clock

    func observeForItem(userId: String, itemId: String) -> AsyncValueObservation<[PurchaseRecord]> {
        purchaseDao.observeForItem(userId: userId, itemId: itemId)
    }

    func softDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        try await purchaseDao.softDelete(userId: userId, id: id, now: clock.nowMs())
    }

    // MARK: - Statistics aggregates
    //
    // Caller passes the active uid; the StatisticsViewModel cancels and
    // re-binds these observations on session change (matches the pattern
    // used by `ItemRepository.observeAll(userId:)`).

    func observeTotalCount(userId: String) -> AsyncValueObservation<Int> {
        purchaseDao.observeTotalCount(userId: userId)
    }

    func observeCountSince(userId: String, sinceMillis: Int64) -> AsyncValueObservation<Int> {
        purchaseDao.observeCountSince(userId: userId, sinceMillis: sinceMillis)
    }

    func observePurchasesPerDay(userId: String, sinceMillis: Int64) -> AsyncValueObservation<[DayCount]> {
        purchaseDao.observePurchasesPerDay(userId: userId, sinceMillis: sinceMillis)
    }

    func observePurchasesByDayOfWeek(userId: String) -> AsyncValueObservation<[DayOfWeekCount]> {
        purchaseDao.observePurchasesByDayOfWeek(userId: userId)
    }

    func observeTopItems(userId: String, limit: Int) -> AsyncValueObservation<[ItemPurchaseCount]> {
        purchaseDao.observeTopItems(userId: userId, limit: limit)
    }

    func observePurchasesByStore(userId: String) -> AsyncValueObservation<[StorePurchaseCount]> {
        purchaseDao.observePurchasesByStore(userId: userId)
    }

    func observePurchasesByCategory(userId: String) -> AsyncValueObservation<[CategoryPurchaseCount]> {
        purchaseDao.observePurchasesByCategory(userId: userId)
    }
}
