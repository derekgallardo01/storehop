package com.storehop.app.data.csv

/**
 * CSV import / export — pure functions, no DB or DI dependency.
 *
 * Supports the documented Storehop format only (header row required).
 * Items header columns: name, category, stores, brand, notes, quantity,
 *   isStaple, isPriority. Only `name` is required; unknown columns are
 *   ignored for forward compat. `stores` is a comma-separated list,
 *   typically quoted: "Aldi,Lidl,Pingo Doce".
 * Categories header columns: name, icon. Only `name` is required.
 *
 * Repository layer ([com.storehop.app.data.repository.ImportExportRepository])
 * is responsible for turning `*CsvRow` payloads into actual entities and
 * resolving category / store names to ids. This file is intentionally
 * decoupled from Room.
 */

data class ItemCsvRow(
    val name: String,
    val category: String? = null,
    val storeNames: List<String> = emptyList(),
    val brand: String? = null,
    val notes: String? = null,
    val quantity: String? = null,
    val isStaple: Boolean = false,
    val isPriority: Boolean = false,
)

data class CategoryCsvRow(
    val name: String,
    val icon: String? = null,
)

data class ParsedItemCsv(
    val rows: List<ItemCsvRow>,
    /** Per-row error messages prefixed with the source line number, 1-based. */
    val errors: List<String>,
)

data class ParsedCategoryCsv(
    val rows: List<CategoryCsvRow>,
    val errors: List<String>,
)

private val ITEM_HEADERS = listOf(
    "name", "category", "stores", "brand", "notes", "quantity", "isStaple", "isPriority",
)
private val CATEGORY_HEADERS = listOf("name", "icon")

// ---- Parse ----------------------------------------------------------------

fun parseItemCsv(content: String): ParsedItemCsv {
    val rawRows = parseCsvLines(content)
    if (rawRows.isEmpty()) return ParsedItemCsv(emptyList(), emptyList())

    val header = rawRows.first().map { it.trim().lowercase() }
    val nameIdx = header.indexOf("name")
    if (nameIdx < 0) {
        return ParsedItemCsv(
            rows = emptyList(),
            errors = listOf("Header row must include a 'name' column."),
        )
    }
    fun idx(col: String) = header.indexOf(col).takeIf { it >= 0 }

    val catIdx = idx("category")
    val storesIdx = idx("stores")
    val brandIdx = idx("brand")
    val notesIdx = idx("notes")
    val qtyIdx = idx("quantity")
    val stapleIdx = idx("isstaple")
    val priorityIdx = idx("ispriority")

    val rows = mutableListOf<ItemCsvRow>()
    val errors = mutableListOf<String>()
    rawRows.drop(1).forEachIndexed { i, fields ->
        val lineNumber = i + 2 // header is line 1; first data row is line 2
        if (fields.all { it.isBlank() }) return@forEachIndexed // skip blank rows silently
        val name = fields.getOrNull(nameIdx)?.trim().orEmpty()
        if (name.isEmpty()) {
            errors += "Line $lineNumber: 'name' is required."
            return@forEachIndexed
        }
        rows += ItemCsvRow(
            name = name,
            category = catIdx?.let { fields.getOrNull(it)?.trim().orEmptyOrNull() },
            storeNames = storesIdx?.let { fields.getOrNull(it).orEmpty() }
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            brand = brandIdx?.let { fields.getOrNull(it)?.trim().orEmptyOrNull() },
            notes = notesIdx?.let { fields.getOrNull(it)?.trim().orEmptyOrNull() },
            quantity = qtyIdx?.let { fields.getOrNull(it)?.trim().orEmptyOrNull() },
            isStaple = stapleIdx?.let { fields.getOrNull(it)?.parseBool() } ?: false,
            isPriority = priorityIdx?.let { fields.getOrNull(it)?.parseBool() } ?: false,
        )
    }
    return ParsedItemCsv(rows, errors)
}

fun parseCategoryCsv(content: String): ParsedCategoryCsv {
    val rawRows = parseCsvLines(content)
    if (rawRows.isEmpty()) return ParsedCategoryCsv(emptyList(), emptyList())

    val header = rawRows.first().map { it.trim().lowercase() }
    val nameIdx = header.indexOf("name")
    if (nameIdx < 0) {
        return ParsedCategoryCsv(
            rows = emptyList(),
            errors = listOf("Header row must include a 'name' column."),
        )
    }
    val iconIdx = header.indexOf("icon").takeIf { it >= 0 }

    val rows = mutableListOf<CategoryCsvRow>()
    val errors = mutableListOf<String>()
    rawRows.drop(1).forEachIndexed { i, fields ->
        val lineNumber = i + 2
        if (fields.all { it.isBlank() }) return@forEachIndexed
        val name = fields.getOrNull(nameIdx)?.trim().orEmpty()
        if (name.isEmpty()) {
            errors += "Line $lineNumber: 'name' is required."
            return@forEachIndexed
        }
        rows += CategoryCsvRow(
            name = name,
            icon = iconIdx?.let { fields.getOrNull(it)?.trim().orEmptyOrNull() },
        )
    }
    return ParsedCategoryCsv(rows, errors)
}

// ---- Serialize ------------------------------------------------------------

/** Emit one CSV string with header row + one row per [rows] entry. */
fun List<ItemCsvRow>.toItemsCsv(): String = buildString {
    append(ITEM_HEADERS.joinToString(","))
    append('\n')
    this@toItemsCsv.forEach { r ->
        append(
            listOf(
                r.name,
                r.category.orEmpty(),
                r.storeNames.joinToString(","),
                r.brand.orEmpty(),
                r.notes.orEmpty(),
                r.quantity.orEmpty(),
                r.isStaple.toString(),
                r.isPriority.toString(),
            ).joinToString(",") { escapeCsvField(it) },
        )
        append('\n')
    }
}

fun List<CategoryCsvRow>.toCategoriesCsv(): String = buildString {
    append(CATEGORY_HEADERS.joinToString(","))
    append('\n')
    this@toCategoriesCsv.forEach { r ->
        append(escapeCsvField(r.name))
        append(',')
        append(escapeCsvField(r.icon.orEmpty()))
        append('\n')
    }
}

// ---- Internal RFC-4180 parser ---------------------------------------------

/**
 * Split a CSV blob into a list of records, each a list of unescaped fields.
 * Handles \r\n and \n line endings, quoted fields with embedded commas /
 * newlines, and "" as a literal quote inside a quoted field.
 */
internal fun parseCsvLines(content: String): List<List<String>> {
    if (content.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    val n = content.length

    fun pushField() { current.add(field.toString()); field.clear() }
    fun pushRow() {
        pushField()
        rows.add(current)
        current = mutableListOf()
    }

    while (i < n) {
        val c = content[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < n && content[i + 1] == '"' -> { // escaped quote
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' && field.isEmpty() -> inQuotes = true
            c == ',' -> pushField()
            c == '\r' -> {
                if (i + 1 < n && content[i + 1] == '\n') i++
                pushRow()
            }
            c == '\n' -> pushRow()
            else -> field.append(c)
        }
        i++
    }
    // Flush trailing field/row (no terminating newline)
    if (field.isNotEmpty() || current.isNotEmpty()) pushRow()
    // Drop entirely-empty trailing rows produced by a final newline
    return rows.filterNot { it.size == 1 && it[0].isEmpty() }
}

/** Quote a field if it contains a comma, double-quote, or any line break;
 *  double up embedded quotes per RFC 4180. */
internal fun escapeCsvField(s: String): String {
    val needsQuoting = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (!needsQuoting) s
    else "\"" + s.replace("\"", "\"\"") + "\""
}

private fun String?.orEmptyOrNull(): String? = this?.takeIf { it.isNotEmpty() }
private fun String.parseBool(): Boolean = trim().equals("true", ignoreCase = true)
