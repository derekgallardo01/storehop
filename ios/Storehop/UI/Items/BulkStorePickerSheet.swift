import SwiftUI

/// v0.8.1 bulk store-tag picker. Invoked from the Items list's selection-
/// mode toolbar; lets the user pick one or more stores to UNION into every
/// selected item's existing store set (add-only semantics — no stores are
/// removed). Add is enabled only after at least one store is picked;
/// Cancel and swipe-down dismiss are equivalent.
///
/// The host passes the selection count for the body copy and an
/// `onApply` callback that forwards the picked set into
/// `ItemsListViewModel.applyBulkStores`. The host owns dismissal — the
/// sheet calls `onApply` then `onDismiss`, and `onDismiss` again from the
/// Cancel button.
struct BulkStorePickerSheet: View {
    let stores: [Store]
    let selectedItemCount: Int
    let onApply: (Set<String>) -> Void
    let onDismiss: () -> Void

    @State private var picked: Set<String> = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(bodyText)
                        .font(StorehopTypography.bodyMedium)
                }
                Section(header: Text(L("item_stores_label"))) {
                    if stores.isEmpty {
                        Text(L("item_no_stores_yet"))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    } else {
                        // Render one Toggle row per store — Form handles
                        // these natively and they receive taps reliably
                        // (the chip-style row's custom `Layout` doesn't
                        // play nicely with Form's hit-testing inside a
                        // sheet, so we use the more idiomatic Form-row
                        // multi-select pattern instead).
                        ForEach(stores) { store in
                            Button {
                                if picked.contains(store.id) {
                                    picked.remove(store.id)
                                } else {
                                    picked.insert(store.id)
                                }
                            } label: {
                                HStack {
                                    Image(systemName: picked.contains(store.id)
                                        ? "checkmark.circle.fill"
                                        : "circle")
                                        .foregroundStyle(picked.contains(store.id)
                                            ? StorehopColors.primary
                                            : StorehopColors.onSurfaceVariant)
                                    Text(store.name + (store.isOneOff ? L("store_chip_one_off_suffix") : ""))
                                        .foregroundStyle(StorehopColors.onSurface)
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                            }
                        }
                    }
                }
            }
            .navigationTitle(L("items_bulk_tag_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel"), action: onDismiss)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("items_bulk_tag_apply")) {
                        onApply(picked)
                        onDismiss()
                    }
                    .disabled(picked.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var bodyText: String {
        // `String(localized:)` with interpolation respects the .xcstrings
        // plural variations (variations.plural.{one,other}) — passing a
        // separate `String(format:)` step would lose that, because the
        // localized lookup runs before the format substitution.
        String(localized: "items_bulk_tag_body \(selectedItemCount)", bundle: currentLanguageBundle())
    }

    private func toggle(_ id: String) {
        if picked.contains(id) {
            picked.remove(id)
        } else {
            picked.insert(id)
        }
    }
}
