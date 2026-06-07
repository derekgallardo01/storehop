import XCTest

/// v0.6.0 long-press-edit on a Shop-at-Store row. iOS uses the HIG-
/// idiomatic context menu (`.contextMenu`) in place of Android's
/// long-press, with the same "Edit" entry point. The form opens in edit
/// mode pre-filled with the item's data.
///
/// Mirrors Android's `LongPressEditFromStoreE2ETest`. XCUITest exposes
/// `.contextMenu` via `press(forDuration:)` — a sustained press opens
/// the menu, then we tap the "Edit" action.
final class LongPressEditFromStoreE2ETest: E2EBaseTest {

    func testContextMenuEditOpensItemEditForm() {
        launchAppWithSeededFixtures()

        // StorePicker → Lidl.
        XCTAssertTrue(
            app.staticTexts["Lidl"].waitForExistence(timeout: 10),
            "Seeded Lidl store should appear"
        )
        app.staticTexts["Lidl"].tap()

        // Wait for Milk to render in Lidl, then press-and-hold to open
        // its context menu.
        let milkRow = app.staticTexts["Milk"]
        XCTAssertTrue(
            milkRow.waitForExistence(timeout: 10),
            "Milk should render in Lidl"
        )
        milkRow.press(forDuration: 1.0)

        // Context menu opens with an "Edit" action (`action_edit_item`).
        let editAction = app.buttons["Edit"]
        XCTAssertTrue(
            editAction.waitForExistence(timeout: 5),
            "Context menu should offer 'Edit' action on row long-press"
        )
        editAction.tap()

        // Edit form opens — Save button confirms we're on the form, and
        // the title is "Edit item" (`edit_item_title`).
        XCTAssertTrue(
            app.buttons["Save"].waitForExistence(timeout: 5),
            "Item edit form should open after tapping Edit in the context menu"
        )
        XCTAssertTrue(
            app.staticTexts["Edit item"].exists || app.navigationBars["Edit item"].exists,
            "Form should be in edit mode (navigation title 'Edit item')"
        )
    }
}
