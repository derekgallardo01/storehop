import XCTest

/// Diagnostic E2E that verifies the `-E2ESeedFixtures` flag actually
/// surfaces the canonical fixture data through the UI. If this fails,
/// every other seeded test will too — debug here first.
///
/// Mirrors no specific Android test; exists purely as a local sanity
/// check for the iOS E2E plumbing.
final class SeedSmokeE2ETest: E2EBaseTest {

    func testSeededFixturesAppearInItemsList() {
        launchAppWithSeededFixtures()
        openItemsTab()
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Seeded Milk item should appear in the Items list"
        )
        XCTAssertTrue(app.staticTexts["Eggs"].exists, "Seeded Eggs item should appear")
        XCTAssertTrue(app.staticTexts["Bread"].exists, "Seeded Bread item should appear")
    }

    func testSeededStoresAppearInShopPicker() {
        launchAppWithSeededFixtures()
        // Default tab is Shop, which renders the StorePicker.
        XCTAssertTrue(
            app.staticTexts["Lidl"].waitForExistence(timeout: 10),
            "Seeded Lidl store should appear in the StorePicker"
        )
        XCTAssertTrue(app.staticTexts["Aldi"].exists, "Seeded Aldi store should appear")
    }
}
