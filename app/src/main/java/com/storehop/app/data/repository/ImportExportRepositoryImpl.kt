package com.storehop.app.data.repository

import androidx.room.withTransaction
import com.storehop.app.data.csv.CategoryCsvRow
import com.storehop.app.data.csv.ItemCsvRow
import com.storehop.app.data.csv.parseCategoryCsv
import com.storehop.app.data.csv.parseItemCsv
import com.storehop.app.data.csv.toCategoriesCsv
import com.storehop.app.data.csv.toItemsCsv
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ImportExportRepositoryImpl @Inject constructor(
    private val db: StorehopDatabase,
    private val categoryDao: CategoryDao,
    private val storeDao: StoreDao,
    private val itemDao: ItemDao,
    private val categoryRepository: CategoryRepository,
    private val storeRepository: StoreRepository,
    private val itemRepository: ItemRepository,
    private val session: UserSessionProvider,
) : ImportExportRepository {

    override suspend fun exportItemsCsv(): String {
        val userId = requireSignedIn()
        val rows = itemDao.observeAll(userId).first().map { row ->
            ItemCsvRow(
                name = row.item.name,
                category = row.category?.name,
                storeNames = row.stores.map { it.name },
                brand = row.item.brand,
                notes = row.item.notes,
                quantity = row.item.quantity,
                isStaple = row.item.isStaple,
                isPriority = row.item.isPriority,
            )
        }
        return rows.toItemsCsv()
    }

    override suspend fun exportCategoriesCsv(): String {
        val userId = requireSignedIn()
        val rows = categoryDao.observeAll(userId, includeArchived = false).first().map {
            CategoryCsvRow(name = it.name, icon = it.icon)
        }
        return rows.toCategoriesCsv()
    }

    override suspend fun importItemsCsv(content: String): ImportResult = db.withTransaction {
        val userId = requireSignedIn()
        val parsed = parseItemCsv(content)
        if (parsed.rows.isEmpty()) {
            return@withTransaction ImportResult.Empty.copy(errors = parsed.errors)
        }

        val importedItemIds = mutableListOf<String>()
        val importedCategoryIds = mutableListOf<String>()
        val importedStoreIds = mutableListOf<String>()
        val errors = parsed.errors.toMutableList()
        var duplicatesSkipped = 0

        for (row in parsed.rows) {
            // Hard constraint: never modify an existing alive item. Skip the row.
            if (itemDao.findByName(userId, row.name) != null) {
                duplicatesSkipped++
                continue
            }

            val categoryId = row.category?.let { name ->
                resolveCategory(userId, name, importedCategoryIds)
            }
            val storeIds = row.storeNames
                .mapNotNull { name -> resolveStore(userId, name, importedStoreIds) }
                .toSet()

            try {
                val newId = itemRepository.addItem(
                    name = row.name,
                    categoryId = categoryId,
                    storeIds = storeIds,
                    quantity = row.quantity,
                    notes = row.notes,
                    brand = row.brand,
                    isStaple = row.isStaple,
                    isPriority = row.isPriority,
                )
                importedItemIds += newId
            } catch (e: Exception) {
                errors += "Could not import \"${row.name}\": ${e.message ?: e::class.simpleName}"
            }
        }

        ImportResult(
            itemsImported = importedItemIds.size,
            duplicatesSkipped = duplicatesSkipped,
            categoriesImported = importedCategoryIds.size,
            storesImported = importedStoreIds.size,
            errors = errors,
            importedItemIds = importedItemIds,
            importedCategoryIds = importedCategoryIds,
            importedStoreIds = importedStoreIds,
        )
    }

    override suspend fun importCategoriesCsv(content: String): ImportResult = db.withTransaction {
        val userId = requireSignedIn()
        val parsed = parseCategoryCsv(content)
        val importedCategoryIds = mutableListOf<String>()
        val errors = parsed.errors.toMutableList()
        var duplicatesSkipped = 0

        for (row in parsed.rows) {
            // Reuse existing alive row by name; otherwise call addCategory which
            // resurrects a tombstone or inserts new.
            if (categoryDao.findByName(userId, row.name) != null) {
                duplicatesSkipped++
                continue
            }
            try {
                val id = categoryRepository.addCategory(name = row.name, icon = row.icon)
                importedCategoryIds += id
            } catch (e: IllegalArgumentException) {
                errors += "Could not import \"${row.name}\": ${e.message}"
            }
        }

        ImportResult.Empty.copy(
            categoriesImported = importedCategoryIds.size,
            duplicatesSkipped = duplicatesSkipped,
            errors = errors,
            importedCategoryIds = importedCategoryIds,
        )
    }

    override suspend fun undoImport(result: ImportResult) {
        db.withTransaction {
            // Soft-delete in dependency order: items first (so xrefs cascade
            // before categories / stores get tombstoned), then the rest.
            result.importedItemIds.forEach { id -> itemRepository.softDelete(id) }
            result.importedCategoryIds.forEach { id -> categoryRepository.softDelete(id) }
            result.importedStoreIds.forEach { id -> storeRepository.softDelete(id) }
        }
    }

    /**
     * Find an alive category by name; if none, call addCategory (which itself
     * handles tombstone resurrection or fresh insert) and remember the new id
     * for the import-result summary.
     */
    private suspend fun resolveCategory(
        userId: String,
        name: String,
        importedIds: MutableList<String>,
    ): String? {
        categoryDao.findByName(userId, name)?.let { return it.id }
        return try {
            val id = categoryRepository.addCategory(name = name, icon = null)
            importedIds += id
            id
        } catch (e: IllegalArgumentException) {
            // addCategory rejects empty/whitespace; for a CSV row that has
            // empty strings after trim, just skip the category link.
            null
        }
    }

    private suspend fun resolveStore(
        userId: String,
        name: String,
        importedIds: MutableList<String>,
    ): String? {
        storeDao.findByName(userId, name)?.let { return it.id }
        return try {
            val id = storeRepository.addStore(name = name)
            importedIds += id
            id
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun requireSignedIn(): String =
        session.currentUserId() ?: throw IllegalStateException("Not signed in")
}
