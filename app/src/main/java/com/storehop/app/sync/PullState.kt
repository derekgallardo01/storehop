package com.storehop.app.sync

/**
 * Per-uid sync state. Drives whether [SyncEngine] is allowed to push for a
 * given uid (only when [SUCCEEDED]).
 *
 * Persisted by [PullStateRepository] in DataStore so process death between
 * sign-in and pull completion doesn't strand the app — on next launch we
 * see [NEEDED] and run the pull again.
 */
enum class PullState {
    /**
     * Initial state for a uid we've never seen before, or after process death
     * mid-pull. The next reachable code path that observes this state should
     * trigger a pull.
     */
    NEEDED,

    /** Pull is currently running. Push side is paused. */
    IN_PROGRESS,

    /**
     * Pull completed successfully or wasn't needed (cloud was empty and the
     * orphan-claim path ran instead). Push side is unblocked.
     */
    SUCCEEDED,

    /**
     * Pull failed (network, auth, deserialization). Push side stays paused so
     * local seeded data can't leak to the cloud. The user sees a Retry banner
     * in Settings.
     */
    FAILED,
}
