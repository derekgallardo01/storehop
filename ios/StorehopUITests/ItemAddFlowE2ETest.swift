import XCTest

/// E2E for the basic Add Item flow: Items tab → + FAB → name → Save →
/// the new item appears in the list. Mirrors Android's
/// `ItemAddFlowE2ETest`. Exercises the form's bare-minimum happy path.
final class ItemAddFlowE2ETest: E2EBaseTest {

    func testAddItemViaFormAppearsInList() {
        launchAppWithSeededFixtures()

        openItemsTab()

        // Tap the + FAB. Its accessibility label is "Add item"
        // (`action_add_item` key, English value).
        app.buttons["Add item"].tap()

        // The form's name field is a TextField with placeholder "Name"
        // (`item_name_label`). XCUITest exposes TextFields via their
        // placeholder string.
        let nameField = app.textFields["Name"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5), "Add-item form should open with a Name field")
        nameField.tap()
        nameField.typeText("Yogurt")

        // Save the form. The Save button lives in the navigation
        // toolbar (`action_save` key). Save runs an async Task to write
        // to GRDB, then sets `viewModel.saved = true`, which the form
        // observes to call onDismiss → nav pop back to the Items list.
        app.buttons["Save"].tap()

        // Back on the list, the new row is visible.
        let newRow = app.staticTexts["Yogurt"]
        XCTAssertTrue(
            newRow.waitForExistence(timeout: 10),
            "Newly-added item should appear in the list after save"
        )
    }
}
