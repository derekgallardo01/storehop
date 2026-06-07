import XCTest

/// v0.6.0 sort toggle on the master Items list. Verifies:
///  - Default mode is ALPHABETIC; the toolbar offers
///    "Switch to category sort".
///  - Tapping flips to CATEGORY mode; the localized "(uncategorised)"
///    section header appears (Eggs + Bread fall under it).
///  - Tapping the now-"Switch to alphabetic sort" button flips back.
///
/// Mirrors Android's `ItemsListSortToggleE2ETest`.
final class ItemsListSortToggleE2ETest: E2EBaseTest {

    func testTogglingSortModeShowsCategoryHeadersAndPersistsAcrossTaps() {
        launchAppWithSeededFixtures()
        openItemsTab()

        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Seeded rows should render before we can toggle sort"
        )

        // Default = ALPHABETIC. The toolbar shows "Switch to category sort".
        let toCategory = app.buttons["Switch to category sort"]
        XCTAssertTrue(toCategory.exists, "Toolbar should show the 'switch to category sort' button by default")
        toCategory.tap()

        // CATEGORY mode — "(uncategorised)" header is unique to that mode
        // (Eggs + Bread fall under it). "Dairy" appears both as a section
        // header AND as Milk's subtitle, so it's not a clean sentinel.
        XCTAssertTrue(
            app.staticTexts["(uncategorised)"].waitForExistence(timeout: 5),
            "Expected '(uncategorised)' section header after switching to category sort"
        )

        // Toolbar icon now offers the inverse.
        let toAlphabetic = app.buttons["Switch to alphabetic sort"]
        XCTAssertTrue(toAlphabetic.exists, "Toolbar should now show 'switch to alphabetic sort'")
        toAlphabetic.tap()

        // Back to ALPHABETIC — Bread is alphabetically first among the
        // three seeded items, so it's a stable sentinel for the flat list.
        XCTAssertTrue(
            app.staticTexts["Bread"].waitForExistence(timeout: 5),
            "Bread should still render after toggling back to alphabetic"
        )
        XCTAssertFalse(
            app.staticTexts["(uncategorised)"].exists,
            "Section headers should disappear once we're back in alphabetic mode"
        )
    }
}
