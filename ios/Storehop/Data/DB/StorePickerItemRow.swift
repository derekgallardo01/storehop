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
}
