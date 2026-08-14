import SwiftUI

#if DEBUG

/// DEBUG-only marketing helper surfaced at the bottom of Settings. Mirrors
/// Android's "Demo data (debug)" `SettingsCard` with its Load / Clear
/// buttons: fills the app with a curated dataset for landing-page
/// screenshots and the walkthrough tour, or wipes it again.
///
/// The whole view is compiled out of release builds by the surrounding
/// `#if DEBUG`, matching the way R8 strips the Kotlin loader call sites.
struct DemoDataDebugSection: View {
    @Environment(AppContainer.self) private var container

    @State private var busy = false
    @State private var errorMessage: String?

    var body: some View {
        Section {
            Text("Fill the app with curated demo data for landing-page screenshots and the walkthrough tour.")
                .font(StorehopTypography.bodySmall)
                .foregroundStyle(StorehopColors.onSurfaceVariant)

            HStack(spacing: 12) {
                Button("Load demo data") { run { try await container.makeDemoDataSeeder().seed() } }
                    .buttonStyle(.borderedProminent)
                    .tint(StorehopColors.primary)
                    .disabled(busy)
                Button("Clear") { run { try await container.makeDemoDataSeeder().clear() } }
                    .buttonStyle(.bordered)
                    .disabled(busy)
                if busy {
                    ProgressView().scaleEffect(0.7)
                }
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(.red)
            }
        } header: {
            Text("Demo data (debug)")
        }
    }

    private func run(_ operation: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        errorMessage = nil
        Task {
            defer { busy = false }
            do {
                try await operation()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

#endif
