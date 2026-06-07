import SwiftUI

/// v0.7.1: Settings → Data → "Sync before uninstalling" section.
///
/// Drains every `pendingSync = 1` row to Firestore + force-pushes the
/// user-prefs doc, then surfaces "Safe to uninstall" when the queue is
/// empty. Mirrors Android's `ForceSyncCard` Composable.
///
/// Load-bearing for the eventual iOS sideload-IPA → App-Store-install
/// transition that the v0.7.1 Mike message foreshadowed on Android. iOS
/// doesn't have the same signing-cert-mismatch concern (TestFlight uses
/// Apple's certs from the start), but the UX is still useful any time a
/// user is about to delete + reinstall the app.
struct ForceSyncSection: View {
    @Environment(AppContainer.self) private var container

    @State private var state: ForceSyncState = .idle
    @State private var pendingCount: Int = 0
    @State private var countObserverTask: Task<Void, Never>?

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("settings_force_sync_explainer"))
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)

                switch state {
                case .idle:
                    Button(action: forceSyncTapped) {
                        Text(idleLabel)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                case .syncing(let n):
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(String(format: L("settings_force_sync_in_progress %lld"), n))
                            .font(StorehopTypography.bodyMedium)
                    }

                case .safeToUninstall:
                    Text(L("settings_force_sync_safe"))
                        .font(StorehopTypography.bodyMedium)
                        .foregroundStyle(.green)
                    Button(action: { state = .idle }) {
                        Text(L("settings_force_sync_button_done"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                case .failed(let n):
                    Text(String(format: L("settings_force_sync_failed %lld"), n))
                        .font(StorehopTypography.bodyMedium)
                        .foregroundStyle(.red)
                    Button(action: forceSyncTapped) {
                        Text(L("settings_force_sync_button_retry"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        } header: {
            Text(L("settings_force_sync_title"))
        }
        .task {
            await observePendingCount()
        }
    }

    private var idleLabel: String {
        if pendingCount > 0 {
            String(format: L("settings_force_sync_button_pending %lld"), pendingCount)
        } else {
            L("settings_force_sync_button_idle")
        }
    }

    private func forceSyncTapped() {
        // Allow re-entry from .idle (first tap) and .failed (retry tap).
        // Block double-taps while .syncing and the post-success
        // "Safe to uninstall" terminal state.
        switch state {
        case .idle, .failed:
            break
        case .syncing, .safeToUninstall:
            return
        }
        Task {
            let uid = await container.session.currentUserId
            let householdId = await container.householdSession.currentHouseholdId
            guard let uid, let householdId else { return }

            state = .syncing(pendingCount)
            let drained = await container.syncEngine.flushAllPending(
                householdId: householdId,
                uid: uid
            )
            state = drained ? .safeToUninstall : .failed(pendingCount)
        }
    }

    private func observePendingCount() async {
        let householdId = await container.householdSession.currentHouseholdId
        guard let householdId else { return }
        for await count in container.syncEngine.observeAllPendingCount(householdId: householdId) {
            await MainActor.run { pendingCount = count }
        }
    }
}

/// v0.7.1: state machine for the Force-sync-now button. Idle by default;
/// `forceSyncTapped` walks it through Syncing → SafeToUninstall (drain)
/// or Syncing → Failed (timeout). Mirrors Android's `ForceSyncState`.
enum ForceSyncState: Equatable {
    case idle
    case syncing(Int)
    case safeToUninstall
    case failed(Int)
}
