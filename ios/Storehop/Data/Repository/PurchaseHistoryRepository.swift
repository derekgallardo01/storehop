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
}
