import XCTest
@testable import Storehop

/// Verifies record ↔ DTO mappers are lossless on the round-trip and that
/// pull mappers force `pendingSync = false` (so a re-pull doesn't trigger
/// re-push). Wire-format compatibility with Android is implicit: same
/// field names + types means the JSON Firestore writes is identical.
final class EntityMappersTests: XCTestCase {

    func testItemRoundTripPreservesAllFields() {
        let item = TestFixtures.item(id: "i1", name: "Mozzarella", categoryId: "c_dairy", userId: "u1")
        var withExtras = item
        withExtras.brand = "Galbani"
        withExtras.imageUrl = "https://example.com/m.jpg"
        withExtras.isStaple = true
        withExtras.isPriority = true
        withExtras.notes = "fresh"
        withExtras.quantity = "2"
        withExtras.lastPurchasedAt = 1_500
        withExtras.deletedAt = nil

        let dto = withExtras.toDto()
        let back = dto.toEntity()

        XCTAssertEqual(back.id, withExtras.id)
        XCTAssertEqual(back.name, withExtras.name)
        XCTAssertEqual(back.brand, withExtras.brand)
        XCTAssertEqual(back.imageUrl, withExtras.imageUrl)
        XCTAssertEqual(back.isStaple, withExtras.isStaple)
        XCTAssertEqual(back.isPriority, withExtras.isPriority)
        XCTAssertEqual(back.notes, withExtras.notes)
        XCTAssertEqual(back.quantity, withExtras.quantity)
        XCTAssertEqual(back.lastPurchasedAt, withExtras.lastPurchasedAt)
        XCTAssertEqual(back.userId, withExtras.userId)
        XCTAssertFalse(back.pendingSync, "Pull mapper must always set pendingSync=false")
    }

    func testStoreRoundTripPreservesDisplayOrderAndColor() {
        let store = TestFixtures.store(id: "s1", name: "Lidl", userId: "u1", displayOrder: 7)
        var withColor = store
        withColor.colorArgb = 0xFFFFAABB
        let dto = withColor.toDto()
        let back = dto.toEntity()
        XCTAssertEqual(back.colorArgb, 0xFFFFAABB)
        XCTAssertEqual(back.displayOrder, 7)
        XCTAssertFalse(back.pendingSync)
    }

    func testXrefRoundTripPreservesPerStoreState() {
        let xref = TestFixtures.xref(itemId: "i1", storeId: "s_lidl", isNeeded: false, lastPurchasedAt: 9_999)
        let dto = xref.toDto()
        let back = dto.toEntity()
        XCTAssertEqual(back.itemId, "i1")
        XCTAssertEqual(back.storeId, "s_lidl")
        XCTAssertFalse(back.isNeeded)
        XCTAssertEqual(back.lastPurchasedAt, 9_999)
        XCTAssertFalse(back.pendingSync)
    }

    func testXrefDocIdMatchesAndroidPattern() {
        let xref = TestFixtures.xref(itemId: "i1", storeId: "s_lidl")
        XCTAssertEqual(xref.docId, "i1__s_lidl")
    }

    func testStoreCategoryOrderDocIdMatchesAndroidPattern() {
        let sco = TestFixtures.sco(storeId: "s_lidl", categoryId: "c_dairy")
        XCTAssertEqual(sco.docId, "s_lidl__c_dairy")
    }

    func testItemDtoEncodesToJsonWithExpectedFieldNames() throws {
        let item = TestFixtures.item(id: "i1", name: "Mozzarella", userId: "u1")
        let dto = item.toDto()
        let data = try JSONEncoder().encode(dto)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertNotNil(json?["id"])
        XCTAssertNotNil(json?["name"])
        XCTAssertNotNil(json?["userId"])
        XCTAssertNotNil(json?["isNeeded"])
        XCTAssertNotNil(json?["createdAt"])
        // pendingSync must NOT appear in the wire format.
        XCTAssertNil(json?["pendingSync"], "DTO must not expose pendingSync — local-only flag")
    }
}
