import XCTest
@testable import Storehop

final class CsvImportExportTests: XCTestCase {

    func testEmptyInputReturnsEmptyResult() {
        let r = parseItemCsv("")
        XCTAssertTrue(r.rows.isEmpty)
        XCTAssertTrue(r.errors.isEmpty)
    }

    func testHeaderWithoutNameColumnReportsError() {
        let r = parseItemCsv("foo,bar\nx,y\n")
        XCTAssertTrue(r.rows.isEmpty)
        XCTAssertEqual(r.errors.count, 1)
        XCTAssertTrue(r.errors.first?.contains("name") == true)
    }

    func testParseHandlesAllDocumentedColumnsAndHeaderIsCaseInsensitive() {
        let csv = """
        NAME,Category,Stores,Brand,Notes,Quantity,IsStaple,IsPriority
        Milk,Dairy & Eggs,"Aldi,Lidl",Mimosa,Skim,1 L,true,false
        """
        let r = parseItemCsv(csv)
        XCTAssertTrue(r.errors.isEmpty)
        XCTAssertEqual(r.rows.count, 1)
        let row = r.rows[0]
        XCTAssertEqual(row.name, "Milk")
        XCTAssertEqual(row.category, "Dairy & Eggs")
        XCTAssertEqual(row.storeNames, ["Aldi", "Lidl"])
        XCTAssertEqual(row.brand, "Mimosa")
        XCTAssertEqual(row.notes, "Skim")
        XCTAssertEqual(row.quantity, "1 L")
        XCTAssertTrue(row.isStaple)
        XCTAssertFalse(row.isPriority)
    }

    func testQuotedFieldWithEmbeddedComma() {
        let csv = "name,notes\nMilk,\"low-fat, organic\"\n"
        let r = parseItemCsv(csv)
        XCTAssertEqual(r.rows.first?.notes, "low-fat, organic")
    }

    func testEscapedDoubleQuoteIsUnescaped() {
        let csv = "name,brand\nMilk,\"Mike\"\"s Dairy\"\n"
        let r = parseItemCsv(csv)
        XCTAssertEqual(r.rows.first?.brand, "Mike\"s Dairy")
    }

    func testCrlfAndLfLineEndingsSupported() {
        let crlf = parseItemCsv("name\r\nMilk\r\nBread\r\n").rows.map(\.name)
        let lf = parseItemCsv("name\nMilk\nBread\n").rows.map(\.name)
        XCTAssertEqual(crlf, ["Milk", "Bread"])
        XCTAssertEqual(lf, ["Milk", "Bread"])
    }

    func testUtf8BomIsStrippedFromHeader() {
        let bomCsv = "\u{FEFF}name,stores\nApple,Target\n"
        let r = parseItemCsv(bomCsv)
        XCTAssertTrue(r.errors.isEmpty, "BOM should be stripped, but got errors: \(r.errors)")
        XCTAssertEqual(r.rows.count, 1)
        XCTAssertEqual(r.rows.first?.name, "Apple")
        XCTAssertEqual(r.rows.first?.storeNames, ["Target"])
    }

    func testHeaderAliasesAccepted() {
        let csv = "product,cat,store\nCoffee,Pantry,Costco\n"
        let r = parseItemCsv(csv)
        XCTAssertTrue(r.errors.isEmpty)
        XCTAssertEqual(r.rows.count, 1)
        XCTAssertEqual(r.rows.first?.name, "Coffee")
        XCTAssertEqual(r.rows.first?.category, "Pantry")
        XCTAssertEqual(r.rows.first?.storeNames, ["Costco"])
    }

    func testSamSchultzSampleWithQuotesAndBom() {
        let input = """
        \u{FEFF}name,stores
        acetaminophen Equate Arthritis 650mg extended release,"newstore"
        Afta Pre-Electric Shave,"newstore"
        Air Filter 16 X 20 X 1 apartment micro particle,"newstore"
        Alcohol Rubbing 91%,"newstore"
        Almonds Dry Roasted or smoked,"newstore"
        Aluminum foil,"newstore"
        Applesause home style chunk,"newstore"
        Bacon,"newstore"
        Bagels,"newstore"
        Baking cups cupcakes,"newstore"
        Baking powder,"newstore"
        """
        let r = parseItemCsv(input)
        XCTAssertTrue(r.errors.isEmpty, "Errors: \(r.errors)")
        XCTAssertEqual(r.rows.count, 11)
        XCTAssertEqual(r.rows[0].name, "acetaminophen Equate Arthritis 650mg extended release")
        XCTAssertEqual(r.rows[0].storeNames, ["newstore"])
        XCTAssertEqual(r.rows[10].name, "Baking powder")
        XCTAssertEqual(r.rows[10].storeNames, ["newstore"])
    }

    func testCategoryCsvParseAndSerialize() {
        let input = "name,icon\nProduce,apple.logo\nBakery,\n"
        let parsed = parseCategoryCsv(input)
        XCTAssertTrue(parsed.errors.isEmpty)
        XCTAssertEqual(parsed.rows.count, 2)
        XCTAssertEqual(parsed.rows[0].name, "Produce")
        XCTAssertEqual(parsed.rows[0].icon, "apple.logo")
        XCTAssertEqual(parsed.rows[1].name, "Bakery")
        XCTAssertNil(parsed.rows[1].icon)

        let serialized = parsed.rows.toCategoriesCsv()
        let roundtrip = parseCategoryCsv(serialized)
        XCTAssertEqual(parsed.rows, roundtrip.rows)
    }
}
