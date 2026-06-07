import SwiftUI

/// Multi-select chip row for tagging an item (or batch of items) to stores.
/// Wraps to multiple lines when there are many stores. Tap toggles
/// selection. Caller owns the selected set and the `onToggle` callback —
/// this view is purely presentational.
///
/// Shared by the single-item edit form (`ItemFormView`) and the v0.8.1
/// bulk-tag picker (`BulkStorePickerSheet`) so both surfaces have
/// identical chip styling.
struct StoreChipsRow: View {
    let stores: [Store]
    let selected: Set<String>
    let onToggle: (String) -> Void

    var body: some View {
        FlexibleHStack(spacing: 8) {
            ForEach(stores) { store in
                let isOn = selected.contains(store.id)
                Button {
                    onToggle(store.id)
                } label: {
                    HStack(spacing: 6) {
                        if isOn {
                            Image(systemName: "checkmark")
                                .font(.caption2)
                        }
                        // v0.9.0 — append " (One-off)" suffix to the
                        // chip label so the user can tell at a glance
                        // which stores are one-off without expanding the
                        // form. The bulk-tag sheet inherits this for
                        // free since it renders through the same chip
                        // row.
                        Text(store.name + (store.isOneOff ? L("store_chip_one_off_suffix") : ""))
                            .font(StorehopTypography.labelMedium)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isOn ? StorehopColors.primary : StorehopColors.surfaceVariant,
                        in: Capsule()
                    )
                    .foregroundStyle(
                        isOn ? StorehopColors.onPrimary : StorehopColors.onSurface
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Minimal flow layout — wraps content to multiple lines as needed.
/// SwiftUI's `Layout` protocol makes this simple. Used for the store chips
/// where the chip count may exceed one line.
private struct FlexibleHStack: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        for sub in subviews {
            let s = sub.sizeThatFits(.unspecified)
            if x + s.width > maxWidth, x > 0 {
                totalHeight += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            rowHeight = max(rowHeight, s.height)
            x += s.width + spacing
            y = totalHeight + rowHeight
        }
        return CGSize(width: maxWidth, height: y)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0
        for sub in subviews {
            let s = sub.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            sub.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            rowHeight = max(rowHeight, s.height)
            x += s.width + spacing
        }
    }
}
