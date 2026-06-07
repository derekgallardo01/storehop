import XCTest

/// v0.6.0 search clear-button (×). Verifies the trailing clear affordance
/// only appears once the user types, and tapping it wipes the field.
///
/// Mirrors Android's `SearchClearButtonE2ETest`. iOS uses SwiftUI's
/// `.searchable` modifier, which provides a system-rendered Clear button
/// inside the search field once it has text. XCUITest exposes that
/// button under the search field.
final class SearchClearButtonE2ETest: E2EBaseTest {

    func testClearButtonAppearsAfterTypingAndWipesTheField() {
        launchAppWithSeededFixtures()
        openItemsTab()

        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Seeded rows should render"
        )

        // iOS hides the search field below the navigation bar until the
        // user scrolls down OR taps the magnifying glass / starts typing.
        // The `.searchable` modifier exposes a search field with the
        // localized placeholder "Search anything" once visible.
        //
        // Activate the search bar by tapping into its placeholder text.
        // Tests run on a freshly-launched simulator so the search bar
        // may be hidden; we use `swipeDown` on the table to reveal it.
        let searchField = app.searchFields["Search anything"]
        if !searchField.exists {
            // SwiftUI's inset-grouped list hides the search bar until pulled.
            app.swipeDown()
        }
        XCTAssertTrue(
            searchField.waitForExistence(timeout: 5),
            "Search field with 'Search anything' placeholder should be reachable"
        )

        searchField.tap()
        searchField.typeText("milk")

        // After typing, the system clear button appears inside the
        // search field. XCUIElement exposes it as a child button.
        let clearButton = searchField.buttons.element(boundBy: 0)
        XCTAssertTrue(
            clearButton.exists,
            "Clear button (×) should appear inside the search field after typing"
        )
        clearButton.tap()

        // After tap: the search field's value is empty (or shows the
        // placeholder). XCUIElement.value for an empty searchField
        // reads as the placeholder string.
        let valueAfter = (searchField.value as? String) ?? ""
        XCTAssertTrue(
            valueAfter.isEmpty || valueAfter == "Search anything",
            "Search field should be empty after tapping clear; got '\(valueAfter)'"
        )
    }
}
