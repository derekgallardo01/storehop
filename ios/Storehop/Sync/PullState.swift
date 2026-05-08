import Foundation

/// Lifecycle of a Firestore pull for a given uid. Drives the SyncEngine's
/// push gate (push only runs when `SUCCEEDED`) and the Settings cloud-sync
/// banner (visible when `FAILED`).
///
/// Mirrors Android `PullState`. Same names so Firestore documents under
/// `users/{uid}/_pullState` are wire-compatible if we ever sync the pull
/// state itself (we don't today, but the option is open).
enum PullState: String, Codable, Sendable, CaseIterable {
    /// Initial state for a uid we haven't seen sync for yet. Either it's
    /// never been observed, or pull was attempted but the device was
    /// offline.
    case needed = "NEEDED"
    /// Pull is currently running. Push is paused until this resolves.
    case inProgress = "IN_PROGRESS"
    /// Pull completed successfully. Push is unblocked.
    case succeeded = "SUCCEEDED"
    /// Pull failed. Push stays paused; Settings shows a Retry banner.
    case failed = "FAILED"
}
