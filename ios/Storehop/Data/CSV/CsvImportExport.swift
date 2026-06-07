import Foundation

/// CSV import / export — pure functions, no DB or DI dependency. Mirrors
/// Android's `data/csv/CsvImportExport.kt`.
///
/// Items header columns: name, category, stores, brand, notes, quantity,
///   isStaple, isPriority. Only `name` is required; unknown columns are
///   ignored for forward compat. `stores` is a comma-separated list,
///   typically quoted: "Aldi,Lidl,Pingo Doce".
/// Categories header columns: name, icon. Only `name` is required.

struct ItemCsvRow: Equatable {
    let name: String
    let category: String?
    let storeNames: [String]
    let brand: String?
    let notes: String?
    let quantity: String?
    let isStaple: Bool
    let isPriority: Bool

    init(
        name: String,
        category: String? = nil,
        storeNames: [String] = [],
        brand: String? = nil,
        notes: String? = nil,
        quantity: String? = nil,
        isStaple: Bool = false,
        isPriority: Bool = false
    ) {
        self.name = name
        self.category = category
        self.storeNames = storeNames
        self.brand = brand
        self.notes = notes
        self.quantity = quantity
        self.isStaple = isStaple
        self.isPriority = isPriority
    }
}

struct CategoryCsvRow: Equatable {
    let name: String
    let icon: String?
}

struct ParsedItemCsv {
    let rows: [ItemCsvRow]
    /// Per-row error messages prefixed with the source line number, 1-based.
    let errors: [String]
}

struct ParsedCategoryCsv {
    let rows: [CategoryCsvRow]
    let errors: [String]
}

private let ITEM_HEADERS = [
    "name", "category", "stores", "brand", "notes", "quantity", "isStaple", "isPriority",
]
private let CATEGORY_HEADERS = ["name", "icon"]

// MARK: - Parse

func parseItemCsv(_ content: String) -> ParsedItemCsv {
    let rawRows = parseCsvLines(content)
    if rawRows.isEmpty { return ParsedItemCsv(rows: [], errors: []) }

    let header = rawRows[0].map { $0.trimmed.lowercased() }
    guard let nameIdx = header.firstIndex(of: "name") else {
        return ParsedItemCsv(rows: [], errors: ["Header row must include a 'name' column."])
    }
    let catIdx = header.firstIndex(of: "category")
    let storesIdx = header.firstIndex(of: "stores")
    let brandIdx = header.firstIndex(of: "brand")
    let notesIdx = header.firstIndex(of: "notes")
    let qtyIdx = header.firstIndex(of: "quantity")
    let stapleIdx = header.firstIndex(of: "isstaple")
    let priorityIdx = header.firstIndex(of: "ispriority")

    var rows: [ItemCsvRow] = []
    var errors: [String] = []
    for (i, fields) in rawRows.dropFirst().enumerated() {
        let lineNumber = i + 2  // header = line 1; first data row = line 2
        if fields.allSatisfy({ $0.isBlank }) { continue }
        let name = fields.indexed(nameIdx)?.trimmed ?? ""
        if name.isEmpty {
            errors.append("Line \(lineNumber): 'name' is required.")
            continue
        }
        rows.append(ItemCsvRow(
            name: name,
            category: catIdx.flatMap { fields.indexed($0)?.trimmed.nilIfEmpty },
            storeNames: storesIdx.flatMap { fields.indexed($0) }
                .map { $0.split(separator: ",").map { String($0).trimmed }.filter { !$0.isEmpty } }
                ?? [],
            brand: brandIdx.flatMap { fields.indexed($0)?.trimmed.nilIfEmpty },
            notes: notesIdx.flatMap { fields.indexed($0)?.trimmed.nilIfEmpty },
            quantity: qtyIdx.flatMap { fields.indexed($0)?.trimmed.nilIfEmpty },
            isStaple: stapleIdx.flatMap { fields.indexed($0)?.parsedBool } ?? false,
            isPriority: priorityIdx.flatMap { fields.indexed($0)?.parsedBool } ?? false
        ))
    }
    return ParsedItemCsv(rows: rows, errors: errors)
}

func parseCategoryCsv(_ content: String) -> ParsedCategoryCsv {
    let rawRows = parseCsvLines(content)
    if rawRows.isEmpty { return ParsedCategoryCsv(rows: [], errors: []) }

    let header = rawRows[0].map { $0.trimmed.lowercased() }
    guard let nameIdx = header.firstIndex(of: "name") else {
        return ParsedCategoryCsv(rows: [], errors: ["Header row must include a 'name' column."])
    }
    let iconIdx = header.firstIndex(of: "icon")

    var rows: [CategoryCsvRow] = []
    var errors: [String] = []
    for (i, fields) in rawRows.dropFirst().enumerated() {
        let lineNumber = i + 2
        if fields.allSatisfy({ $0.isBlank }) { continue }
        let name = fields.indexed(nameIdx)?.trimmed ?? ""
        if name.isEmpty {
            errors.append("Line \(lineNumber): 'name' is required.")
            continue
        }
        rows.append(CategoryCsvRow(
            name: name,
            icon: iconIdx.flatMap { fields.indexed($0)?.trimmed.nilIfEmpty }
        ))
    }
    return ParsedCategoryCsv(rows: rows, errors: errors)
}

// MARK: - Serialize

extension Array where Element == ItemCsvRow {
    func toItemsCsv() -> String {
        var out = ITEM_HEADERS.joined(separator: ",") + "\n"
        for r in self {
            let cols = [
                r.name,
                r.category ?? "",
                r.storeNames.joined(separator: ","),
                r.brand ?? "",
                r.notes ?? "",
                r.quantity ?? "",
                r.isStaple ? "true" : "false",
                r.isPriority ? "true" : "false",
            ]
            out += cols.map(escapeCsvField).joined(separator: ",") + "\n"
        }
        return out
    }
}

extension Array where Element == CategoryCsvRow {
    func toCategoriesCsv() -> String {
        var out = CATEGORY_HEADERS.joined(separator: ",") + "\n"
        for r in self {
            out += escapeCsvField(r.name) + "," + escapeCsvField(r.icon ?? "") + "\n"
        }
        return out
    }
}

// MARK: - Internal RFC-4180 parser

/// Split a CSV blob into a list of records, each a list of unescaped fields.
/// Handles \r\n and \n line endings, quoted fields with embedded commas /
/// newlines, and "" as a literal quote inside a quoted field.
func parseCsvLines(_ content: String) -> [[String]] {
    if content.isEmpty { return [] }
    var rows: [[String]] = []
    var current: [String] = []
    var field = ""
    var inQuotes = false
    let chars = Array(content)
    var i = 0
    let n = chars.count

    func pushField() {
        current.append(field)
        field = ""
    }
    func pushRow() {
        pushField()
        rows.append(current)
        current = []
    }

    while i < n {
        let c = chars[i]
        if inQuotes {
            if c == "\"" && i + 1 < n && chars[i + 1] == "\"" {
                field.append("\"")
                i += 1
            } else if c == "\"" {
                inQuotes = false
            } else {
                field.append(c)
            }
        } else {
            if c == "\"" && field.isEmpty {
                inQuotes = true
            } else if c == "," {
                pushField()
            } else if c == "\r" {
                if i + 1 < n && chars[i + 1] == "\n" { i += 1 }
                pushRow()
            } else if c == "\n" {
                pushRow()
            } else {
                field.append(c)
            }
        }
        i += 1
    }
    if !field.isEmpty || !current.isEmpty { pushRow() }
    // Drop entirely-empty trailing rows produced by a final newline.
    return rows.filter { !($0.count == 1 && $0[0].isEmpty) }
}

/// Quote a field if it contains a comma, double-quote, or any line break;
/// double up embedded quotes per RFC 4180.
func escapeCsvField(_ s: String) -> String {
    let needsQuoting = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
    if !needsQuoting { return s }
    return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
}

// MARK: - Helpers

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var isBlank: Bool { trimmed.isEmpty }
    var nilIfEmpty: String? { isEmpty ? nil : self }
    var parsedBool: Bool { trimmed.lowercased() == "true" }
}

private extension Array where Element == String {
    /// Bounds-safe index access mirroring Kotlin's `getOrNull(index)`.
    func indexed(_ index: Int) -> String? {
        guard index >= 0 && index < count else { return nil }
        return self[index]
    }
}
