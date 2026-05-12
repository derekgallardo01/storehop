import SwiftUI
import UniformTypeIdentifiers

/// Settings → Data section: CSV import / export for items + categories.
/// Mirrors Android's Data card at the bottom of the Settings screen.
///
/// All four actions go through `ImportExportRepository`. Exports use
/// SwiftUI's `.fileExporter` to save the generated CSV; imports use
/// `.fileImporter` to read a file the user picks. After an import, an
/// `UndoBar` overlay surfaces the result (counts + skipped) and lets the
/// user reverse it.
struct DataSettingsSection: View {
    @Environment(AppContainer.self) private var container

    @State private var pendingExport: PendingExport?
    @State private var importMode: ImportMode?
    @State private var importResult: ImportResult?
    @State private var importInFlight = false
    @State private var errorMessage: String?
    @State private var entitlement: Entitlement = .notEntitled
    @State private var localizedPrice: String?

    var body: some View {
        Section {
            // v0.8: Export gated behind Premium. Locked buttons show the
            // App-Store-localized price; tapping launches the purchase
            // sheet. Import stays unconditionally free — onboarding hook
            // for users migrating from another app.
            actionRow(
                title: exportItemsLabel,
                systemImage: "square.and.arrow.up"
            ) {
                if entitlement.isUnlocked { exportItems() }
                else { Task { await launchPurchase() } }
            }
            actionRow(
                title: exportCategoriesLabel,
                systemImage: "square.and.arrow.up"
            ) {
                if entitlement.isUnlocked { exportCategories() }
                else { Task { await launchPurchase() } }
            }
            actionRow(
                title: String(localized: "action_import_items"),
                systemImage: "square.and.arrow.down"
            ) { importMode = .items }
            actionRow(
                title: String(localized: "action_import_categories"),
                systemImage: "square.and.arrow.down"
            ) { importMode = .categories }

            if let errorMessage {
                Text(errorMessage)
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(.red)
            }
        } header: {
            Text(String(localized: "settings_data_section_title"))
        } footer: {
            Text(String(localized: "settings_data_section_subtitle"))
        }
        .fileExporter(
            isPresented: Binding(
                get: { pendingExport != nil },
                set: { if !$0 { pendingExport = nil } }
            ),
            document: pendingExport.map { CsvDocument(content: $0.content) },
            contentType: .commaSeparatedText,
            defaultFilename: pendingExport?.filename ?? "export.csv"
        ) { result in
            if case .failure(let err) = result {
                errorMessage = err.localizedDescription
            }
            pendingExport = nil
        }
        .fileImporter(
            isPresented: Binding(
                get: { importMode != nil },
                set: { if !$0 { importMode = nil } }
            ),
            allowedContentTypes: [.commaSeparatedText, .text]
        ) { result in
            switch result {
            case .success(let url):
                runImport(url: url, mode: importMode ?? .items)
            case .failure(let err):
                errorMessage = err.localizedDescription
            }
            importMode = nil
        }
        .overlay(alignment: .bottom) {
            if let result = importResult {
                UndoBar(
                    message: importResultMessage(result),
                    onUndo: { undo(result) },
                    onDismiss: { importResult = nil }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
        }
        .task { await observeEntitlement() }
        .task { await observePrice() }
    }

    private func observeEntitlement() async {
        guard let repo = container.entitlementRepository else { return }
        for await ent in repo.entitlementStream {
            await MainActor.run { entitlement = ent }
        }
    }

    private func observePrice() async {
        guard let storeKit = container.storeKitManager else { return }
        for await product in storeKit.productStream {
            await MainActor.run { localizedPrice = product?.displayPrice }
        }
    }

    private var exportItemsLabel: String {
        if entitlement.isUnlocked { return String(localized: "action_export_items") }
        if let price = localizedPrice {
            return String(format: String(localized: "premium_locked_export_label %@"), price)
        }
        return String(localized: "action_export_items")
    }

    private var exportCategoriesLabel: String {
        if entitlement.isUnlocked { return String(localized: "action_export_categories") }
        if let price = localizedPrice {
            return String(format: String(localized: "premium_locked_export_categories_label %@"), price)
        }
        return String(localized: "action_export_categories")
    }

    @MainActor
    private func launchPurchase() async {
        guard let storeKit = container.storeKitManager else { return }
        _ = await storeKit.purchase()
    }

    // MARK: - Actions

    private func exportItems() {
        Task {
            errorMessage = nil
            do {
                let csv = try await container.importExportRepository.exportItemsCsv()
                pendingExport = PendingExport(content: csv, filename: "storehop-items.csv")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func exportCategories() {
        Task {
            errorMessage = nil
            do {
                let csv = try await container.importExportRepository.exportCategoriesCsv()
                pendingExport = PendingExport(content: csv, filename: "storehop-categories.csv")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func runImport(url: URL, mode: ImportMode) {
        Task {
            errorMessage = nil
            importInFlight = true
            defer { importInFlight = false }
            // .fileImporter returns a security-scoped URL on iOS. We must
            // bracket access with start/stop calls or the read fails.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                guard let content = String(data: data, encoding: .utf8) else {
                    errorMessage = "Could not read file as UTF-8."
                    return
                }
                let result: ImportResult
                switch mode {
                case .items:
                    result = try await container.importExportRepository.importItemsCsv(content)
                case .categories:
                    result = try await container.importExportRepository.importCategoriesCsv(content)
                }
                importResult = result
                if !result.errors.isEmpty {
                    errorMessage = result.errors.joined(separator: "\n")
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func undo(_ result: ImportResult) {
        Task {
            await container.importExportRepository.undoImport(result)
            importResult = nil
        }
    }

    private func importResultMessage(_ result: ImportResult) -> String {
        String(
            format: String(localized: "import_result_summary %lld %lld %lld %lld"),
            result.itemsImported,
            result.categoriesImported,
            result.storesImported,
            result.duplicatesSkipped
        )
    }

    @ViewBuilder
    private func actionRow(title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Label(title, systemImage: systemImage)
                Spacer()
                if importInFlight {
                    ProgressView().scaleEffect(0.7)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Helpers

private struct PendingExport: Equatable {
    let content: String
    let filename: String
}

private enum ImportMode { case items, categories }

/// FileDocument wrapping a CSV string. `.fileExporter` uses this to write
/// the CSV to whatever location the user picks.
private struct CsvDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    static var writableContentTypes: [UTType] { [.commaSeparatedText] }

    let content: String

    init(content: String) {
        self.content = content
    }

    init(configuration: ReadConfiguration) throws {
        guard
            let data = configuration.file.regularFileContents,
            let s = String(data: data, encoding: .utf8)
        else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.content = s
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(content.utf8))
    }
}
