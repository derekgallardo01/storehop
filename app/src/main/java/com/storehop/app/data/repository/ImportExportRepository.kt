package com.storehop.app.data.repository

/**
 * Items + categories CSV import / export. The hard contract from the user:
 * **import never erases or modifies existing local data, and never overwrites
 * an existing Firestore document.** Implementation achieves this by:
 *
 *  - Items: skip CSV rows whose name matches an alive item; otherwise
 *    insert a fresh row through [ItemRepository.addItem].
 *  - Categories / stores: reuse the existing add-with-resurrection paths
 *    ([CategoryRepository.addCategory] / [StoreRepository.addStore]) so a
 *    tombstoned row with the same name is brought back rather than
 *    duplicated. No alive row is ever modified.
 *
 * All sync-side effects fall out naturally: each import wraps in
 * `db.withTransaction` so a parse error mid-file rolls the whole batch back,
 * and the new rows carry `pendingSync = true` so the next push tick uploads
 * them to Firestore (no existing doc is touched).
 */
interface ImportExportRepository {
    /** Serialize every alive item to the documented CSV format. */
    suspend fun exportItemsCsv(): String

    /** Serialize every alive category to the documented CSV format. */
    suspend fun exportCategoriesCsv(): String

    /**
     * Parse [content] as an items CSV and import each valid row. Skips
     * rows whose name matches an alive item. Auto-creates referenced
     * categories and stores by name (resurrecting tombstones if any).
     * Returns a summary the caller surfaces in a snackbar.
     */
    suspend fun importItemsCsv(content: String): ImportResult

    /**
     * Parse [content] as a categories CSV. Each row goes through
     * [CategoryRepository.addCategory] which handles the alive-skip and
     * tombstone-resurrect cases.
     */
    suspend fun importCategoriesCsv(content: String): ImportResult

    /**
     * Soft-delete every id in [result]. Called from the Undo snackbar
     * action after a fresh import. No-op for ids that are no longer alive
     * (the user already deleted them manually before tapping Undo).
     */
    suspend fun undoImport(result: ImportResult)
}

/**
 * Summary returned by an import call. The caller renders the `imported / skipped`
 * counts in a snackbar and keeps the result around (in the ViewModel) until the
 * Undo window closes, so [ImportExportRepository.undoImport] can reverse it.
 */
data class ImportResult(
    val itemsImported: Int,
    val itemsSkipped: Int,
    val categoriesImported: Int,
    val storesImported: Int,
    val errors: List<String>,
    val importedItemIds: List<String>,
    val importedCategoryIds: List<String>,
    val importedStoreIds: List<String>,
) {
    companion object {
        val Empty = ImportResult(
            itemsImported = 0, itemsSkipped = 0,
            categoriesImported = 0, storesImported = 0,
            errors = emptyList(),
            importedItemIds = emptyList(),
            importedCategoryIds = emptyList(),
            importedStoreIds = emptyList(),
        )
    }
}
