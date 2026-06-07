import XCTest

/// v0.6.1 inline "New category…" affordance from the item edit form.
/// Verifies the sheet opens, accepts a name, and the new category gets
/// auto-selected on the form.
///
/// Uses "Bread" because it has no category yet — its picker reads
/// "(none)" before the action. Mirrors Android's `InlineNewCategoryE2ETest`.
final class InlineNewCategoryE2ETest: E2EBaseTest {

    func testCreatingNewCategoryFromItemFormAutoSelectsIt() {
        launchAppWithSeededFixtures()
        openItemsTab()

        // Items tab → tap Bread row → form opens in edit mode.
        XCTAssertTrue(
            app.staticTexts["Bread"].waitForExistence(timeout: 10),
            "Seeded Bread row should render"
        )
        app.staticTexts["Bread"].tap()

        // Confirm the form opened.
        XCTAssertTrue(
            app.buttons["Save"].waitForExistence(timeout: 5),
            "Edit form should open after tapping Bread"
        )

        // The "New category…" button lives right below the category
        // picker in the same Section. iOS uses the `action_new_category`
        // key → English value "New category…".
        let newCategoryAction = app.buttons["New category…"]
        XCTAssertTrue(
            newCategoryAction.waitForExistence(timeout: 5),
            "'New category…' inline action should be visible under the picker"
        )
        newCategoryAction.tap()

        // The CategoryNameDialog sheet opens with a TextField placeholder
        // "Category name" (`add_category_field_label`).
        let nameField = app.textFields["Category name"]
        XCTAssertTrue(
            nameField.waitForExistence(timeout: 5),
            "Add-category sheet should open with a Category name field"
        )
        nameField.tap()
        nameField.typeText("Cleaning")

        // The dialog's primary action is "Add" (`action_add`).
        app.buttons["Add"].tap()

        // Back on the form, the picker now shows "Cleaning" as the
        // selected category. Picker presents the selected label inline.
        XCTAssertTrue(
            app.staticTexts["Cleaning"].waitForExistence(timeout: 5),
            "Newly-created category should be auto-selected on the form"
        )
    }
}
