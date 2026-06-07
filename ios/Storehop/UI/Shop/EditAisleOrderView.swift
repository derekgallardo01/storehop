import SwiftUI

struct EditAisleOrderView: View {
    let storeId: String
    @Environment(AppContainer.self) private var container
    @State private var viewModel: EditAisleOrderViewModel?

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
                viewModel = EditAisleOrderViewModel(
                    storeId: storeId,
                    scoRepository: container.storeCategoryOrderRepository,
                    storeRepository: container.storeRepository,
                    categoryRepository: container.categoryRepository,
                    session: container.session
                )
            }
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: EditAisleOrderViewModel) -> some View {
        let storeName = viewModel.store?.name ?? ""
        return List {
            Section {
                Text(L("edit_aisles_helper"))
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                    .listRowBackground(Color.clear)
            }
            if viewModel.orderedCategories.isEmpty {
                Section {
                    Text(L("edit_aisles_empty"))
                        .font(StorehopTypography.bodyMedium)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            } else {
                Section {
                    ForEach(viewModel.orderedCategories) { category in
                        CategoryRowView(category: category)
                    }
                    .onMove { source, destination in
                        var current = viewModel.orderedCategories
                        current.move(fromOffsets: source, toOffset: destination)
                        viewModel.commitOrder(current.map(\.id))
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(
            String(format: L("edit_aisles_title %@"), storeName)
        )
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                EditButton()
            }
        }
        .environment(\.editMode, .constant(.active))  // always-on drag handles
    }
}

private struct CategoryRowView: View {
    let category: Category

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "line.3.horizontal")
                .foregroundStyle(StorehopColors.onSurfaceVariant)
            Text(localizedName)
                .font(StorehopTypography.bodyLarge)
        }
    }

    private var localizedName: String {
        if let key = category.nameKey {
            return L(key)
        }
        return category.name
    }
}
