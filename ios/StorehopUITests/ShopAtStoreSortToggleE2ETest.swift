import XCTest

/// v0.6.0 sort toggle inside a store. Default is CATEGORY (aisle
/// headers visible). Tapping the toolbar toggle flips to ALPHABETIC
/// (flat list, no headers).
///
/// Mirrors Android's `ShopAtStoreSortToggleE2ETest`.
final class ShopAtStoreSortToggleE2ETest: E2EBaseTest {

    func testTogglingSortInStoreFlipsBetweenAisleHeadersAndFlatList() {
        launchAppWithSeededFixtures()

        // StorePicker → tap "Lidl".
        XCTAssertTrue(
            app.staticTexts["Lidl"].waitForExistence(timeout: 10),
            "Seeded Lidl store should appear in the StorePicker"
        )
        app.staticTexts["Lidl"].tap()

        // Wait until at least one in-store row renders (Milk is tagged
        // at Lidl).
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Milk should appear in Lidl"
        )

        // CATEGORY default: "Dairy" header is visible (Milk's category).
        XCTAssertTrue(
            app.staticTexts["Dairy"].exists,
            "Dairy section header should be visible in category-sort mode"
        )

        // Toggle to alphabetic.
        let toAlphabetic = app.buttons["Switch to alphabetic sort"]
        XCTAssertTrue(toAlphabetic.exists, "Toolbar should offer 'switch to alphabetic sort' in category mode")
        toAlphabetic.tap()

        // Items still visible, just no aisle section headers. Milk is
        // still in Lidl regardless of sort mode.
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 5),
            "Milk should still render after toggling to alphabetic"
        )

        // Toggle back to category — the inverse button label is now visible.
        let toCategory = app.buttons["Switch to category sort"]
        XCTAssertTrue(toCategory.exists, "Toolbar should offer 'switch to category sort' in alphabetic mode")
        toCategory.tap()

        // Dairy header is back.
        XCTAssertTrue(
            app.staticTexts["Dairy"].waitForExistence(timeout: 5),
            "Dairy header should reappear after toggling back to category"
        )
    }
}
