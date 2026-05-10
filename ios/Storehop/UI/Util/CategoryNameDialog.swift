import SwiftUI

/// Reusable name-prompt sheet for category add / rename. Used by:
///   - `ManageCategoriesView` for the add (FAB) and rename flows.
///   - `ItemFormView` for the inline "+ New category" affordance under
///     the category picker (v0.6.1).
///
/// `onSubmit` returns nil on success, or a localized error string the
/// dialog renders inline (empty / duplicate / generic). The dialog
/// dismisses itself on a successful submit; the host is responsible for
/// any post-success effects (e.g., auto-selecting the new id on the form).
struct CategoryNameDialog: View {
    let title: String
    @State private var name: String
    let actionTitle: String
    let onSubmit: (String) async -> String?
    let onDismiss: () -> Void

    @State private var error: String?
    @State private var saving = false
    @FocusState private var focused: Bool

    init(
        title: String,
        initialName: String,
        actionTitle: String,
        onSubmit: @escaping (String) async -> String?,
        onDismiss: @escaping () -> Void
    ) {
        self.title = title
        self._name = State(initialValue: initialName)
        self.actionTitle = actionTitle
        self.onSubmit = onSubmit
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(String(localized: "add_category_field_label"), text: $name)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
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
                    Button(String(localized: "action_cancel"), action: onDismiss).disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        Task {
                            saving = true
                            let result = await onSubmit(name)
                            saving = false
                            if result == nil { onDismiss() } else { error = result }
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
