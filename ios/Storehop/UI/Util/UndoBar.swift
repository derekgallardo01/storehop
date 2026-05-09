import SwiftUI

/// Bottom-anchored "X done · UNDO · ×" bar. Mirrors Android's
/// `ui/util/UndoBar.kt`. Used by [ShopAtStoreView] (mark-purchased undo)
/// and [StorePickerView] (delete-store undo) so both surfaces have the
/// same affordances.
///
/// Three ways to dismiss:
///  1. Wait 3 seconds — driven by an internal `.task`.
///  2. Tap × — invokes `onDismiss`.
///  3. Swipe horizontally — `DragGesture` with a 60-pt threshold dismisses.
///
/// Tapping UNDO calls `onUndo` then `onDismiss`, so callers don't need to
/// clear their own state.
struct UndoBar: View {
    let message: String
    let onUndo: () -> Void
    let onDismiss: () -> Void

    @State private var dragOffset: CGFloat = 0

    private static let autoDismissNanos: UInt64 = 3_000_000_000  // 3s
    private static let swipeThreshold: CGFloat = 60

    var body: some View {
        HStack(spacing: 8) {
            Text(message)
                .font(StorehopTypography.bodyMedium)
                .foregroundStyle(StorehopColors.onSurface)
            Spacer()
            Button(String(localized: "action_undo")) {
                onUndo()
                onDismiss()
            }
            .font(StorehopTypography.labelLarge)
            .foregroundStyle(StorehopColors.primary)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.callout)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                    .padding(8)
            }
            .accessibilityLabel(String(localized: "action_close"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .shadow(radius: 4, y: 2)
        .offset(x: dragOffset)
        .gesture(
            DragGesture()
                .onChanged { value in
                    dragOffset = value.translation.width
                }
                .onEnded { value in
                    if abs(value.translation.width) > Self.swipeThreshold {
                        onDismiss()
                    } else {
                        withAnimation(.easeOut(duration: 0.15)) {
                            dragOffset = 0
                        }
                    }
                }
        )
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .task {
            try? await Task.sleep(nanoseconds: Self.autoDismissNanos)
            onDismiss()
        }
    }
}
