import Foundation
import GRDB

struct StoreCategoryOrderRepository: Sendable {
    let scoDao: StoreCategoryOrderDao
    let session: any UserSessionProvider
    let clock: any Clock

    func observeForStore(storeId: String) -> AsyncValueObservation<[StoreCategoryOrder]> {
        scoDao.observeForStore(storeId: storeId)
    }

    /// Reorder the categories at this store. Wraps `replaceAllForStore` so
    /// rows missing from the new list get tombstoned and the rest get their
    /// `displayOrder` bumped — all in one transaction.
    func reorderCategoriesForStore(storeId: String, orderedCategoryIds: [String]) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        let rows = orderedCategoryIds.enumerated().map { (index, categoryId) in
            StoreCategoryOrder(
                storeId: storeId,
                categoryId: categoryId,
                displayOrder: index,
                isSeeded: false,
                userId: userId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true
            )
        }
        try await scoDao.replaceAllForStore(storeId: storeId, ordered: rows, now: now)
    }
}
