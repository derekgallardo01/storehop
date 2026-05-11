import SwiftUI

/// Shared empty-state surface for the Items, Shop-at-Store, and Store
/// Picker screens (and any future "nothing here yet" surface). Mirrors the
/// Android `EmptyState` Composable from `ui/util/EmptyState.kt` so the two
/// ports look consistent.
///
/// Layout: full-width VStack with a large SF Symbol (~64pt, tinted
/// `onSurfaceVariant`), a `titleMedium` title, and a `bodyMedium` body
/// (both centered). Drop it inside a `Section { ... }` when used in a
/// SwiftUI `List`, or as a standalone view otherwise.
///
/// Caller supplies the SF Symbol name + two already-resolved strings.
/// The second string is called `message` (not `body`) because `body`
/// is reserved by the `View` protocol — Android calls it `body`.
struct EmptyState: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 64, weight: .light))
                .foregroundStyle(StorehopColors.onSurfaceVariant)
            Text(title)
                .font(StorehopTypography.titleMedium)
                .foregroundStyle(StorehopColors.onSurface)
                .multilineTextAlignment(.center)
            Text(message)
                .font(StorehopTypography.bodyMedium)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .padding(.horizontal, 24)
    }
}
