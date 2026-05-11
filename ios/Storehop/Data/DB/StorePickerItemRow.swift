import Foundation
import GRDB

/// One row per (item, store) pair currently relevant to a Store Picker badge:
/// either still needed at this store, OR purchased there within the active
/// shopping session. Lets the picker render an "All set" affirmation on a
/// store after the user has checked off everything needed there.
///
/// The picker repository groups by `storeId` and produces one badge per
/// store. Multiple rows for the same `itemId` if it's tagged to multiple
/// stores.
struct StorePickerItemRow: FetchableRecord, Decodable, Hashable, Sendable {
    let storeId: String
    let itemId: String
    let itemName: String
    let isPriority: Bool
    let isNeeded: Bool
    let isStaple: Bool
    /// `true` iff `isx.lastPurchasedAt` falls inside the current session
    /// window — computed in SQL via a `CASE WHEN ... THEN 1 ELSE 0 END` so
    /// the repo doesn't need to re-thread `sessionStartMs` to partition
    /// staples. A priority staple that hasn't been bought this session
    /// still counts as "on the list" for chip / banner purposes; one bought
    /// this session moves to picked-up.
    let purchasedThisSession: Bool
}
