import XCTest
@testable import Storehop

final class UserPreferencesDtoTests: XCTestCase {

    func testDecodeEmptyJsonUsesSafeDefaults() throws {
        let json = "{}".data(using: .utf8)!
        let dto = try JSONDecoder().decode(UserPreferencesDto.self, from: json)
        XCTAssertEqual(dto.themeMode, ThemeMode.system.rawValue)
        XCTAssertEqual(dto.localeTag, "")
        XCTAssertEqual(dto.showPurchased, true)
        XCTAssertEqual(dto.shopAtStoreSortMode, SortMode.category.rawValue)
        XCTAssertEqual(dto.itemsListSortMode, SortMode.alphabetic.rawValue)
        XCTAssertEqual(dto.itemsListShowOnlyNeeded, false)
        XCTAssertEqual(dto.startScreen, StartScreen.shop.rawValue)
        XCTAssertEqual(dto.focusSearchOnStart, true)
        XCTAssertEqual(dto.updatedAt, 0)
    }

    func testDecodeLegacyDocWithoutStartScreenFields() throws {
        let legacyJson = """
        {
            "themeMode": "DARK",
            "localeTag": "pt-PT",
            "showPurchased": false,
            "shopAtStoreSortMode": "ALPHABETIC",
            "itemsListSortMode": "CATEGORY",
            "updatedAt": 123456789
        }
        """.data(using: .utf8)!
        let dto = try JSONDecoder().decode(UserPreferencesDto.self, from: legacyJson)
        XCTAssertEqual(dto.themeMode, "DARK")
        XCTAssertEqual(dto.localeTag, "pt-PT")
        XCTAssertEqual(dto.showPurchased, false)
        XCTAssertEqual(dto.shopAtStoreSortMode, "ALPHABETIC")
        XCTAssertEqual(dto.itemsListSortMode, "CATEGORY")
        XCTAssertEqual(dto.itemsListShowOnlyNeeded, false)
        XCTAssertEqual(dto.startScreen, StartScreen.shop.rawValue)
        XCTAssertEqual(dto.focusSearchOnStart, true)
        XCTAssertEqual(dto.updatedAt, 123456789)

        let snapshot = dto.toSnapshot()
        XCTAssertEqual(snapshot.themeMode, .dark)
        XCTAssertEqual(snapshot.localeTag, "pt-PT")
        XCTAssertEqual(snapshot.showPurchased, false)
        XCTAssertEqual(snapshot.shopAtStoreSortMode, .alphabetic)
        XCTAssertEqual(snapshot.itemsListSortMode, .category)
        XCTAssertEqual(snapshot.itemsListShowOnlyNeeded, false)
        XCTAssertEqual(snapshot.startScreen, .shop)
        XCTAssertEqual(snapshot.focusSearchOnStart, true)
        XCTAssertEqual(snapshot.updatedAt, 123456789)
    }

    func testRoundTripSnapshotWithStartScreenItems() throws {
        let snap = UserPreferencesSnapshot(
            themeMode: .light,
            localeTag: "es",
            showPurchased: true,
            shopAtStoreSortMode: .category,
            itemsListSortMode: .alphabetic,
            itemsListShowOnlyNeeded: true,
            startScreen: .items,
            focusSearchOnStart: false,
            updatedAt: 999999
        )
        let dto = snap.toDto()
        XCTAssertEqual(dto.itemsListShowOnlyNeeded, true)
        XCTAssertEqual(dto.startScreen, "ITEMS")
        XCTAssertEqual(dto.focusSearchOnStart, false)

        let encoded = try JSONEncoder().encode(dto)
        let decoded = try JSONDecoder().decode(UserPreferencesDto.self, from: encoded)
        let backToSnap = decoded.toSnapshot()
        XCTAssertEqual(snap, backToSnap)
    }
}
