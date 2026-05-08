import Foundation

/// Process-scoped anchor for the active shopping trip.
///
/// Every Shop-at-Store ViewModel reads `sessionStartMs()` and feeds it to
/// the DAO query, so any item the user purchases after this anchor stays
/// visible (struck-through) at *every* store it's tagged to — not just the
/// one where the purchase was made. The strike-through is the cross-store
/// sync's visible confirmation: bought milk at Lidl, walk into Continente,
/// milk is struck-through there too.
///
/// Lazily initialized on first call; shared by every Shop-at-Store screen
/// within the same app process. Killing and relaunching the app resets it
/// — previously purchased items fall outside the new window and the lists
/// read clean. Deliberately does NOT reset on background/foreground or tab
/// switches: that would re-clean the list mid-trip, which is exactly the
/// failure mode this is fixing.
final actor ShoppingSessionTracker {
    private var startMs: Int64?
    private let clock: any Clock

    init(clock: any Clock) {
        self.clock = clock
    }

    /// Returns the session anchor, lazily initializing on first call.
    func sessionStartMs() -> Int64 {
        if let start = startMs { return start }
        let now = clock.nowMs()
        startMs = now
        return now
    }

    /// Clear the anchor so the next `sessionStartMs()` picks a fresh value.
    /// Reserved for a future "End shopping trip" UI affordance.
    func reset() {
        startMs = nil
    }
}
