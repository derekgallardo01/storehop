import SwiftUI
import UIKit

struct StorePickerView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StorePickerViewModel?
    @State private var showAddDialog = false
    @State private var pendingRename: StorePickerRow?
    @State private var pendingColor: StorePickerRow?
    @State private var pendingDelete: StorePickerRow?
    @State private var undoStoreId: String?
    @State private var undoMessage: String?

    let onPickStore: (String) -> Void
    let onEditAisles: (String) -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = StorePickerViewModel(
                    storeRepository: container.storeRepository,
                    shoppingRepository: container.shoppingRepository,
                    session: container.session,
                    householdSession: container.householdSession,
                    sessionTracker: container.shoppingSessionTracker,
                    pullCoordinator: container.pullCoordinator
                )
            }
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: StorePickerViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottomTrailing) {
            List {
                if let buyToday = viewModel.buyTodayBannerState {
                    Section {
                        BuyTodayBanner(state: buyToday, onPickStore: onPickStore)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
                if let banner = viewModel.criticalBannerState {
                    Section {
                        CriticalNeedsBanner(state: banner, onPickStore: onPickStore)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
                if viewModel.rows.isEmpty {
                    Section {
                        EmptyState(
                            systemImage: "storefront",
                            title: L("storepicker_empty_title"),
                            message: L("storepicker_empty_body")
                        )
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                    }
                }
                Section {
                    ForEach(viewModel.rows) { row in
                        StorePickerRowView(row: row)
                            .contentShape(Rectangle())
                            .onTapGesture { onPickStore(row.store.id) }
                            .listRowBackground(
                                row.store.colorArgb.map { StoreColorPalette.color(from: $0) }
                            )
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = row
                                } label: {
                                    Label(L("action_delete"), systemImage: "trash")
                                }
                                Button {
                                    pendingRename = row
                                } label: {
                                    Label(L("action_rename"), systemImage: "pencil")
                                }
                                .tint(StorehopColors.secondary)
                            }
                            .contextMenu {
                                Button(L("action_rename"), systemImage: "pencil") {
                                    pendingRename = row
                                }
                                Button(L("action_store_color"), systemImage: "paintpalette") {
                                    pendingColor = row
                                }
                                Button(L("action_edit_aisles"), systemImage: "list.number") {
                                    onEditAisles(row.store.id)
                                }
                                // v0.9.0 — flip one-off classification.
                                // Idempotent at the repository layer;
                                // safe to tap repeatedly.
                                Button {
                                    Task { await viewModel.setOneOff(id: row.store.id, isOneOff: !row.store.isOneOff) }
                                } label: {
                                    Label(
                                        row.store.isOneOff
                                            ? L("store_action_unmark_one_off")
                                            : L("store_action_mark_one_off"),
                                        systemImage: row.store.isOneOff ? "checkmark.circle.fill" : "circle"
                                    )
                                }
                                Button(role: .destructive) {
                                    pendingDelete = row
                                } label: {
                                    Label(L("action_delete"), systemImage: "trash")
                                }
                            }
                    }
                    .onMove { source, destination in
                        var current = viewModel.rows
                        current.move(fromOffsets: source, toOffset: destination)
                        viewModel.commitOrder(current.map(\.store.id))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .refreshable {
                await viewModel.refresh()
            }
            .navigationTitle(L("title_shop"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        onOpenSettings()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(L("action_settings"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }

            FloatingAddButton {
                showAddDialog = true
            }
            .padding(20)

            if let undoMessage, let undoStoreId {
                UndoBar(
                    message: undoMessage,
                    onUndo: { viewModel.undoDeleteStore(id: undoStoreId) },
                    onDismiss: clearUndo
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 88)  // clear the FAB
            }
        }
        .sheet(isPresented: $showAddDialog) {
            StoreNameDialog(
                title: L("add_store_dialog_title"),
                initialName: "",
                actionTitle: L("action_add"),
                showOneOffToggle: true,
                showColorPicker: true,
                onSubmit: { name, isOneOff, colorArgb in
                    let err = await viewModel.addStore(name: name, isOneOff: isOneOff, colorArgb: colorArgb)
                    return err
                },
                onDismiss: { showAddDialog = false }
            )
        }
        .sheet(item: $pendingRename) { row in
            StoreNameDialog(
                title: L("rename_store_dialog_title"),
                initialName: row.store.name,
                actionTitle: L("action_save"),
                // Rename doesn't surface the one-off toggle — that's a
                // separate property and is flipped through the row's
                // context menu instead. The `isOneOff` argument here is
                // unused by `renameStore`.
                onSubmit: { name, _, _ in
                    await viewModel.renameStore(id: row.store.id, name: name)
                },
                onDismiss: { pendingRename = nil }
            )
        }
        .sheet(item: $pendingColor) { row in
            StoreColorDialog(
                storeName: row.store.name,
                initialColorArgb: row.store.colorArgb,
                onSave: { newColorArgb in
                    await viewModel.setColor(id: row.store.id, colorArgb: newColorArgb)
                    pendingColor = nil
                },
                onDismiss: { pendingColor = nil }
            )
        }
        .alert(item: $pendingDelete) { row in
            Alert(
                title: Text(String(format: L("delete_store_dialog_title %@"), row.store.name)),
                message: Text(L("delete_store_dialog_message")),
                primaryButton: .destructive(Text(L("action_delete"))) {
                    viewModel.deleteStore(id: row.store.id)
                    showUndo(for: row)
                },
                secondaryButton: .cancel()
            )
        }
    }

    private func showUndo(for row: StorePickerRow) {
        undoStoreId = row.store.id
        undoMessage = String(format: L("undo_store_deleted %@"), row.store.name)
        // Auto-dismiss is owned by UndoBar's internal `.task` (3s); no
        // parent-side timer needed.
    }

    private func clearUndo() {
        undoStoreId = nil
        undoMessage = nil
    }
}

// MARK: - Subviews

private struct StorePickerRowView: View {
    let row: StorePickerRow

    private var customContentColor: Color? {
        row.store.colorArgb.map { StoreColorPalette.contentColor(for: $0) }
    }

    var body: some View {
        HStack(spacing: 12) {
            ColorDot(name: row.store.name, color: row.store.colorArgb)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.store.name)
                    .font(StorehopTypography.titleMedium)
                    .foregroundStyle(customContentColor ?? StorehopColors.onSurface)
                Text(subtitle)
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(subtitleColor)
            }
            Spacer()
            if !row.criticalItemNames.isEmpty {
                CriticalChip(
                    count: row.criticalItemNames.count,
                    customContentColor: customContentColor
                )
            }
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(customContentColor?.opacity(0.85) ?? StorehopColors.onSurfaceVariant)
        }
        .padding(.vertical, 4)
    }

    private var subtitle: String {
        if row.neededCount > 0 {
            return String(format: L("store_items_needed %lld"), row.neededCount)
        }
        if row.pickedUpInSessionCount > 0 {
            return L("store_all_set")
        }
        return L("store_nothing_needed")
    }

    private var subtitleColor: Color {
        if let customContentColor {
            if row.neededCount == 0 && row.pickedUpInSessionCount > 0 {
                return customContentColor
            }
            return customContentColor.opacity(0.85)
        }
        if row.neededCount == 0 && row.pickedUpInSessionCount > 0 {
            return StorehopColors.primary  // sage affirmation when trip done
        }
        return StorehopColors.onSurfaceVariant
    }
}

private struct ColorDot: View {
    let name: String
    let color: Int64?

    var body: some View {
        Circle()
            .fill(fillColor)
            .frame(width: 36, height: 36)
            .overlay {
                Text(initial)
                    .font(StorehopTypography.titleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(textColor)
            }
    }

    private var initial: String {
        guard let first = name.first else { return "?" }
        return String(first).uppercased()
    }

    private var fillColor: Color {
        if let color {
            return StoreColorPalette.contentColor(for: color).opacity(0.18)
        }
        return StorehopColors.surfaceVariant
    }

    private var textColor: Color {
        if let color {
            return StoreColorPalette.contentColor(for: color)
        }
        return StorehopColors.onSurface
    }
}

private struct CriticalChip: View {
    let count: Int
    var customContentColor: Color? = nil

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption2)
            Text("\(count)")
                .font(StorehopTypography.labelMedium)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            customContentColor?.opacity(0.22) ?? StorehopColors.primary,
            in: Capsule()
        )
        .overlay {
            if let customContentColor {
                Capsule()
                    .strokeBorder(customContentColor.opacity(0.45), lineWidth: 1)
            }
        }
        .foregroundStyle(customContentColor ?? StorehopColors.onPrimary)
    }
}

/// Collapsed by default: shows just the title + count chevron. Tap to
/// Routing-aware critical-needs banner — v0.6.10 iOS parity catch-up
/// with Android v0.5.6. Collapsed shows the total count + a "Most at
/// <store> (<N>)" hint so the user knows where to shop first. Expanded
/// shows the per-store breakdown, with each store's critical names
/// comma-joined on its own line.
///
/// On Android the same data shape is `CriticalBannerState`; on iOS it
/// lives in `StorePickerViewModel.criticalBannerState`. Single-store
/// case shows "All at <store>" instead of the "Most at" hint — the
/// store name still matters even when there's no routing decision
/// (matches Android's `critical_banner_all_at`).
private struct CriticalNeedsBanner: View {
    let state: CriticalBannerState
    let onPickStore: (String) -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(
                        format: L("critical_needs_count %lld"),
                        state.totalCount
                    ))
                        .font(StorehopTypography.titleSmall.weight(.semibold))
                        .foregroundStyle(StorehopColors.onPrimaryContainer)
                    Text(state.singleStore
                        ? String(format: L("critical_needs_all_at %@"), state.topStoreName)
                        : String(
                            format: L("critical_needs_most_at %@ %lld"),
                            state.topStoreName,
                            state.topStoreCount
                        ))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onPrimaryContainer)
                }
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                    .accessibilityHidden(true)
            }
            if expanded {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(state.byStore.enumerated()), id: \.offset) { _, entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(
                                format: L("critical_needs_store_header %@ %lld"),
                                entry.storeName,
                                entry.items.count
                            ))
                                .font(StorehopTypography.labelMedium.weight(.semibold))
                                .foregroundStyle(StorehopColors.onPrimaryContainer)
                            Text(entry.items.joined(separator: ", "))
                                .font(StorehopTypography.bodyMedium)
                                .foregroundStyle(StorehopColors.onPrimaryContainer)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        // Tapping a store row navigates to that store; the inner
                        // gesture takes precedence over the card's expand toggle.
                        .onTapGesture { onPickStore(entry.storeId) }
                    }
                }
                .padding(.top, 8)
                .padding(.leading, 28)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StorehopColors.primaryContainer, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() } }
        .accessibilityElement(children: .combine)
        .accessibilityHint(L(expanded
            ? "critical_banner_collapse_cd"
            : "critical_banner_expand_cd"))
    }
}

/// v0.9 "Buy Today!" banner — pinned above the Critical banner. Distinct
/// urgent styling (`error` brand fill) so it reads as "act today," not
/// "don't forget eventually." Collapsed by default; tap to expand a per-store
/// breakdown. Mirrors Android's `BuyTodayBanner` and this file's
/// `CriticalNeedsBanner` structure.
private struct BuyTodayBanner: View {
    let state: BuyTodayBannerState
    let onPickStore: (String) -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "calendar.badge.exclamationmark")
                    .foregroundStyle(.white)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(format: L("buy_today_count %lld"), state.totalCount))
                        .font(StorehopTypography.titleSmall.weight(.semibold))
                        .foregroundStyle(.white)
                    Text(state.singleStore
                        ? String(format: L("buy_today_all_at %@"), state.topStoreName)
                        : String(
                            format: L("buy_today_most_at %@ %lld"),
                            state.topStoreName,
                            state.topStoreCount
                        ))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(.white)
                }
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
            }
            if expanded {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(state.byStore.enumerated()), id: \.offset) { _, entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(
                                format: L("buy_today_store_header %@ %lld"),
                                entry.storeName,
                                entry.items.count
                            ))
                                .font(StorehopTypography.labelMedium.weight(.semibold))
                                .foregroundStyle(.white)
                            Text(entry.items.joined(separator: ", "))
                                .font(StorehopTypography.bodyMedium)
                                .foregroundStyle(.white)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        // Tapping a store row navigates to that store; the inner
                        // gesture takes precedence over the card's expand toggle.
                        .onTapGesture { onPickStore(entry.storeId) }
                    }
                }
                .padding(.top, 8)
                .padding(.leading, 28)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StorehopColors.error, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() } }
        .accessibilityElement(children: .combine)
        .accessibilityHint(L(expanded ? "buy_today_collapse_cd" : "buy_today_expand_cd"))
    }
}

private struct FloatingAddButton: View {
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                Text(L("action_add_store"))
            }
            .font(StorehopTypography.labelLarge)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(StorehopColors.primary, in: Capsule())
            .foregroundStyle(StorehopColors.onPrimary)
            .shadow(radius: 4, y: 2)
        }
    }
}

// MARK: - Reusable name dialog

struct StoreNameDialog: View {
    let title: String
    @State private var name: String
    let actionTitle: String
    /// v0.9.0 — when `true`, the dialog shows an extra "Mark as
    /// one-off store" toggle below the name field. Rename callers
    /// leave this `false` since renaming a store shouldn't bundle a
    /// classification flip; Add-Store callers turn it on so the user
    /// can pick the flag at creation time.
    let showOneOffToggle: Bool
    let showColorPicker: Bool
    @State private var isOneOff: Bool
    @State private var selectedColorArgb: Int64?
    let onSubmit: (String, Bool, Int64?) async -> String?
    let onDismiss: () -> Void

    @State private var error: String?
    @State private var saving = false
    @FocusState private var focused: Bool

    init(
        title: String,
        initialName: String,
        actionTitle: String,
        showOneOffToggle: Bool = false,
        showColorPicker: Bool = false,
        initialIsOneOff: Bool = false,
        initialColorArgb: Int64? = nil,
        onSubmit: @escaping (String, Bool, Int64?) async -> String?,
        onDismiss: @escaping () -> Void
    ) {
        self.title = title
        self._name = State(initialValue: initialName)
        self.actionTitle = actionTitle
        self.showOneOffToggle = showOneOffToggle
        self.showColorPicker = showColorPicker
        self._isOneOff = State(initialValue: initialIsOneOff)
        self._selectedColorArgb = State(initialValue: initialColorArgb)
        self.onSubmit = onSubmit
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(L("add_store_field_label"), text: $name)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
                if showColorPicker {
                    Section(L("store_color_label")) {
                        StoreColorSwatchPicker(
                            selectedColorArgb: selectedColorArgb,
                            onSelectColor: { selectedColorArgb = $0 }
                        )
                    }
                }
                if showOneOffToggle {
                    Section {
                        Toggle(isOn: $isOneOff) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L("store_form_one_off_toggle"))
                                    .font(StorehopTypography.bodyMedium)
                                Text(L("store_form_one_off_subtitle"))
                                    .font(StorehopTypography.bodySmall)
                                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodySmall)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel"), action: onDismiss)
                        .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        Task {
                            saving = true
                            let result = await onSubmit(name, isOneOff, selectedColorArgb)
                            saving = false
                            if result == nil { onDismiss() }
                            else { error = result }
                        }
                    }
                    .disabled(saving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Store color picker + palette (Android parity with StoreColorPalette.kt)

enum StoreColorPalette {
    /// Curated store-brand background colors as opaque 32-bit signed ARGB values
    /// stored in `Int64`. Matches Android's `STORE_COLOR_PALETTE`.
    static let palette: [Int64] = [
        Int64(Int32(bitPattern: 0xFFD32F2F)), // Brand Red
        Int64(Int32(bitPattern: 0xFFC2185B)), // Berry / Magenta
        Int64(Int32(bitPattern: 0xFF7B1FA2)), // Royal Purple
        Int64(Int32(bitPattern: 0xFF283593)), // Deep Navy / Indigo
        Int64(Int32(bitPattern: 0xFF1565C0)), // Brand Blue
        Int64(Int32(bitPattern: 0xFF0277BD)), // Ocean Blue
        Int64(Int32(bitPattern: 0xFF00695C)), // Deep Teal
        Int64(Int32(bitPattern: 0xFF2E7D32)), // Forest Green
        Int64(Int32(bitPattern: 0xFF33691E)), // Fresh Olive Green
        Int64(Int32(bitPattern: 0xFFF9A825)), // Warm Gold / Yellow
        Int64(Int32(bitPattern: 0xFFD84315)), // Deep Brand Orange
        Int64(Int32(bitPattern: 0xFF5D4037)), // Warm Brown / Espresso
    ]

    static func normalize(_ colorArgb: Int64) -> Int64 {
        let bits = UInt32(truncatingIfNeeded: colorArgb) | 0xFF000000
        return Int64(Int32(bitPattern: bits))
    }

    static func color(from colorArgb: Int64) -> Color {
        let r = Double((colorArgb >> 16) & 0xFF) / 255.0
        let g = Double((colorArgb >> 8) & 0xFF) / 255.0
        let b = Double(colorArgb & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }

    static func isLight(_ colorArgb: Int64) -> Bool {
        func linearize(_ c: Double) -> Double {
            c <= 0.03928 ? (c / 12.92) : pow((c + 0.055) / 1.055, 2.4)
        }
        let r = linearize(Double((colorArgb >> 16) & 0xFF) / 255.0)
        let g = linearize(Double((colorArgb >> 8) & 0xFF) / 255.0)
        let b = linearize(Double(colorArgb & 0xFF) / 255.0)
        let luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance >= 0.25
    }

    static func contentColor(for colorArgb: Int64) -> Color {
        isLight(colorArgb)
            ? Color(red: 0x1A / 255.0, green: 0x18 / 255.0, blue: 0x15 / 255.0)
            : .white
    }

    static func formatHex(_ colorArgb: Int64) -> String {
        let rgb = UInt32(truncatingIfNeeded: colorArgb) & 0xFFFFFF
        return String(format: "#%06X", rgb)
    }

    static func parseHex(_ input: String) -> Int64? {
        var cleaned = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("#") {
            cleaned.removeFirst()
        }
        guard cleaned.count == 6, let rgb = UInt32(cleaned, radix: 16) else {
            return nil
        }
        return Int64(Int32(bitPattern: rgb | 0xFF000000))
    }

    static func argbToHsv(_ colorArgb: Int64) -> (hue: Double, saturation: Double, value: Double) {
        let r = Double((colorArgb >> 16) & 0xFF) / 255.0
        let g = Double((colorArgb >> 8) & 0xFF) / 255.0
        let b = Double(colorArgb & 0xFF) / 255.0

        let maxC = max(r, g, b)
        let minC = min(r, g, b)
        let delta = maxC - minC

        let hue: Double
        if delta == 0 {
            hue = 0
        } else if maxC == r {
            var h = ((g - b) / delta).truncatingRemainder(dividingBy: 6.0)
            if h < 0 { h += 6.0 }
            hue = 60.0 * h
        } else if maxC == g {
            hue = 60.0 * (((b - r) / delta) + 2.0)
        } else {
            hue = 60.0 * (((r - g) / delta) + 4.0)
        }
        let saturation = maxC == 0 ? 0 : (delta / maxC)
        return (hue, saturation, maxC)
    }

    static func hsvToArgb(hue: Double, saturation: Double, value: Double) -> Int64 {
        var h = hue.truncatingRemainder(dividingBy: 360.0)
        if h < 0 { h += 360.0 }
        let s = min(max(saturation, 0.0), 1.0)
        let v = min(max(value, 0.0), 1.0)

        let c = v * s
        let x = c * (1.0 - abs((h / 60.0).truncatingRemainder(dividingBy: 2.0) - 1.0))
        let m = v - c

        let (r1, g1, b1): (Double, Double, Double)
        switch Int(h) / 60 {
        case 0: (r1, g1, b1) = (c, x, 0)
        case 1: (r1, g1, b1) = (x, c, 0)
        case 2: (r1, g1, b1) = (0, c, x)
        case 3: (r1, g1, b1) = (0, x, c)
        case 4: (r1, g1, b1) = (x, 0, c)
        default: (r1, g1, b1) = (c, 0, x)
        }

        let r = UInt32(min(max(Int(((r1 + m) * 255.0).rounded()), 0), 255))
        let g = UInt32(min(max(Int(((g1 + m) * 255.0).rounded()), 0), 255))
        let b = UInt32(min(max(Int(((b1 + m) * 255.0).rounded()), 0), 255))
        return Int64(Int32(bitPattern: (r << 16) | (g << 8) | b | 0xFF000000))
    }

    static func argb(from color: Color) -> Int64? {
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        guard UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a) else {
            return nil
        }
        let ri = UInt32(min(max(Int((r * 255.0).rounded()), 0), 255))
        let gi = UInt32(min(max(Int((g * 255.0).rounded()), 0), 255))
        let bi = UInt32(min(max(Int((b * 255.0).rounded()), 0), 255))
        return Int64(Int32(bitPattern: (ri << 16) | (gi << 8) | bi | 0xFF000000))
    }
}

private struct StoreColorSwatchPicker: View {
    let selectedColorArgb: Int64?
    var isDefaultSelected: Bool? = nil
    let onSelectColor: (Int64?) -> Void

    private let columns = [GridItem(.adaptive(minimum: 38), spacing: 10)]

    var body: some View {
        let normalizedSelected = selectedColorArgb.map { StoreColorPalette.normalize($0) }
        let defaultSelected = isDefaultSelected ?? (normalizedSelected == nil)
        LazyVGrid(columns: columns, spacing: 10) {
            Circle()
                .fill(StorehopColors.surfaceVariant)
                .frame(width: 36, height: 36)
                .overlay {
                    Circle()
                        .strokeBorder(
                            defaultSelected ? StorehopColors.primary : StorehopColors.outline,
                            lineWidth: defaultSelected ? 2 : 1
                        )
                    if defaultSelected {
                        Image(systemName: "checkmark")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
                .onTapGesture { onSelectColor(nil) }
                .accessibilityLabel(L("store_color_default"))

            ForEach(StoreColorPalette.palette, id: \.self) { paletteArgb in
                let normalizedPalette = StoreColorPalette.normalize(paletteArgb)
                let isSelected = normalizedSelected == normalizedPalette
                Circle()
                    .fill(StoreColorPalette.color(from: normalizedPalette))
                    .frame(width: 36, height: 36)
                    .overlay {
                        Circle()
                            .strokeBorder(
                                isSelected ? StorehopColors.onSurface : StorehopColors.outline.opacity(0.4),
                                lineWidth: isSelected ? 2 : 1
                            )
                        if isSelected {
                            Image(systemName: "checkmark")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(StoreColorPalette.contentColor(for: normalizedPalette))
                        }
                    }
                    .onTapGesture { onSelectColor(normalizedPalette) }
                    .accessibilityLabel(StoreColorPalette.formatHex(normalizedPalette))
            }
        }
        .padding(.vertical, 4)
    }
}

private struct StoreHsvColorPicker: View {
    let hue: Double
    let saturation: Double
    let value: Double
    let onHsvChange: (Double, Double, Double) -> Void

    private static let hueSpectrum: [Color] = [
        Color(red: 1, green: 0, blue: 0),
        Color(red: 1, green: 1, blue: 0),
        Color(red: 0, green: 1, blue: 0),
        Color(red: 0, green: 1, blue: 1),
        Color(red: 0, green: 0, blue: 1),
        Color(red: 1, green: 0, blue: 1),
        Color(red: 1, green: 0, blue: 0),
    ]

    var body: some View {
        let pureHue = StoreColorPalette.color(
            from: StoreColorPalette.hsvToArgb(hue: hue, saturation: 1.0, value: 1.0)
        )
        let activeColor = StoreColorPalette.color(
            from: StoreColorPalette.hsvToArgb(hue: hue, saturation: saturation, value: value)
        )

        VStack(spacing: 10) {
            GeometryReader { geo in
                let width = max(geo.size.width, 1)
                let height = max(geo.size.height, 1)
                let thumbX = min(max(saturation, 0), 1) * width
                let thumbY = (1.0 - min(max(value, 0), 1)) * height

                ZStack(alignment: .topLeading) {
                    LinearGradient(
                        colors: [.white, pureHue],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    LinearGradient(
                        colors: [.clear, .black],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    Circle()
                        .fill(activeColor)
                        .frame(width: 18, height: 18)
                        .overlay {
                            Circle().strokeBorder(Color.white, lineWidth: 2)
                        }
                        .shadow(color: .black.opacity(0.4), radius: 1.5)
                        .position(x: thumbX, y: thumbY)
                }
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(StorehopColors.outline.opacity(0.4), lineWidth: 1)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { gesture in
                            let newSat = min(max(gesture.location.x / width, 0), 1)
                            let newVal = 1.0 - min(max(gesture.location.y / height, 0), 1)
                            onHsvChange(hue, newSat, newVal)
                        }
                )
                .accessibilityLabel(L("store_color_saturation_value_label"))
            }
            .frame(height: 108)

            GeometryReader { geo in
                let width = max(geo.size.width, 1)
                let height = max(geo.size.height, 1)
                let radius = (height / 2.0) - 2.0
                let rawX = (hue.truncatingRemainder(dividingBy: 360.0) / 360.0) * width
                let thumbX = min(max(rawX, radius), width - radius)

                ZStack(alignment: .leading) {
                    LinearGradient(
                        colors: Self.hueSpectrum,
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    Circle()
                        .fill(pureHue)
                        .frame(width: radius * 2, height: radius * 2)
                        .overlay {
                            Circle().strokeBorder(Color.white, lineWidth: 2)
                        }
                        .shadow(color: .black.opacity(0.4), radius: 1.5)
                        .position(x: thumbX, y: height / 2.0)
                }
                .clipShape(Capsule())
                .overlay {
                    Capsule()
                        .strokeBorder(StorehopColors.outline.opacity(0.4), lineWidth: 1)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { gesture in
                            let newHue = min(max(gesture.location.x / width, 0), 1) * 360.0
                            onHsvChange(newHue, saturation, value)
                        }
                )
                .accessibilityLabel(L("store_color_hue_label"))
            }
            .frame(height: 22)
        }
        .padding(.vertical, 4)
    }
}

private struct StoreColorDialog: View {
    let storeName: String
    @State private var hexInput: String
    @State private var hsvHue: Double
    @State private var hsvSaturation: Double
    @State private var hsvValue: Double
    let onSave: (Int64?) async -> Void
    let onDismiss: () -> Void

    init(
        storeName: String,
        initialColorArgb: Int64?,
        onSave: @escaping (Int64?) async -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.storeName = storeName
        let normalized = initialColorArgb.map { StoreColorPalette.normalize($0) }
        self._hexInput = State(initialValue: normalized.map { StoreColorPalette.formatHex($0) } ?? "")
        let initialHsv = StoreColorPalette.argbToHsv(normalized ?? StoreColorPalette.palette[4])
        self._hsvHue = State(initialValue: initialHsv.hue)
        self._hsvSaturation = State(initialValue: initialHsv.saturation)
        self._hsvValue = State(initialValue: initialHsv.value)
        self.onSave = onSave
        self.onDismiss = onDismiss
    }

    private var isBlankHex: Bool {
        hexInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var parsedHex: Int64? {
        StoreColorPalette.parseHex(hexInput)
    }

    private var isHexValid: Bool {
        isBlankHex || parsedHex != nil
    }

    private var activeColorArgb: Int64? {
        isBlankHex ? nil : parsedHex
    }

    private var systemColorBinding: Binding<Color> {
        Binding(
            get: {
                activeColorArgb.map { StoreColorPalette.color(from: $0) }
                    ?? StoreColorPalette.color(
                        from: StoreColorPalette.hsvToArgb(
                            hue: hsvHue,
                            saturation: hsvSaturation,
                            value: hsvValue
                        )
                    )
            },
            set: { newColor in
                if let argb = StoreColorPalette.argb(from: newColor) {
                    hexInput = StoreColorPalette.formatHex(argb)
                    let hsv = StoreColorPalette.argbToHsv(argb)
                    hsvHue = hsv.hue
                    hsvSaturation = hsv.saturation
                    hsvValue = hsv.value
                }
            }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L("store_color_label")) {
                    StoreColorSwatchPicker(
                        selectedColorArgb: activeColorArgb,
                        isDefaultSelected: isBlankHex,
                        onSelectColor: { picked in
                            hexInput = picked.map { StoreColorPalette.formatHex($0) } ?? ""
                            if let picked {
                                let hsv = StoreColorPalette.argbToHsv(picked)
                                hsvHue = hsv.hue
                                hsvSaturation = hsv.saturation
                                hsvValue = hsv.value
                            }
                        }
                    )
                }
                Section {
                    StoreHsvColorPicker(
                        hue: hsvHue,
                        saturation: hsvSaturation,
                        value: hsvValue,
                        onHsvChange: { newH, newS, newV in
                            hsvHue = newH
                            hsvSaturation = newS
                            hsvValue = newV
                            let argb = StoreColorPalette.hsvToArgb(
                                hue: newH,
                                saturation: newS,
                                value: newV
                            )
                            hexInput = StoreColorPalette.formatHex(argb)
                        }
                    )
                    HStack(spacing: 12) {
                        Circle()
                            .fill(
                                activeColorArgb.map { StoreColorPalette.color(from: $0) }
                                    ?? StorehopColors.surfaceVariant
                            )
                            .frame(width: 24, height: 24)
                            .overlay {
                                Circle()
                                    .strokeBorder(
                                        !isHexValid
                                            ? StorehopColors.error
                                            : (activeColorArgb != nil ? StorehopColors.onSurface : StorehopColors.outline),
                                        lineWidth: 1
                                    )
                            }
                        TextField(L("store_color_custom_hex_label"), text: $hexInput)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onChange(of: hexInput) { _, newValue in
                                if let parsed = StoreColorPalette.parseHex(newValue) {
                                    let hsv = StoreColorPalette.argbToHsv(parsed)
                                    hsvHue = hsv.hue
                                    hsvSaturation = hsv.saturation
                                    hsvValue = hsv.value
                                }
                            }
                        ColorPicker(
                            L("store_color_picker_label"),
                            selection: systemColorBinding,
                            supportsOpacity: false
                        )
                        .labelsHidden()
                        .accessibilityLabel(L("store_color_picker_label"))
                    }
                    if !isHexValid {
                        Text(L("store_color_error_invalid_hex"))
                            .foregroundStyle(.red)
                            .font(StorehopTypography.bodySmall)
                    }
                }
            }
            .navigationTitle(String(format: L("store_color_dialog_title %@"), storeName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel"), action: onDismiss)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        Task { await onSave(activeColorArgb) }
                    }
                    .disabled(!isHexValid)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

