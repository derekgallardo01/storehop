import XCTest

/// v0.6.1 +/- toggle on the Items list. Verifies:
///  - "Bread" (no tagged stores) renders a "+" (Add to shopping list)
///    that is NOT enabled.
///  - "Eggs" (Lidl only, currently needed) renders "−" (Remove from
///    shopping list) that IS enabled.
///  - "Milk" (both stores, currently needed) also renders "−".
///
/// Mirrors Android's `PlusMinusToggleE2ETest`. The Android test
/// additionally taps Milk's "−" and asserts the cascade clears it
/// everywhere — that beat lives in CrossStoreCascadeE2ETest on iOS to
/// keep this one focused on the render-state contract.
final class PlusMinusToggleE2ETest: E2EBaseTest {

    func testRowsRenderPlusMinusBasedOnNeededState() {
        launchAppWithSeededFixtures()
        openItemsTab()

        // Wait for the seeded rows to render.
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Seeded Milk row should render"
        )
        XCTAssertTrue(app.staticTexts["Eggs"].exists, "Seeded Eggs row should render")
        XCTAssertTrue(app.staticTexts["Bread"].exists, "Seeded Bread row should render")

        // Two rows are "Remove from shopping list" (Milk + Eggs). Their
        // accessibilityLabel keys are `action_remove_from_list`. XCUITest
        // uses the resolved en label.
        let removeButtons = app.buttons.matching(identifier: "Remove from shopping list")
        XCTAssertEqual(
            removeButtons.count, 2,
            "Expected 2 'Remove from shopping list' buttons (Milk + Eggs), got \(removeButtons.count)"
        )

        // One row is "Add to shopping list" — Bread, with no tagged stores.
        // Its button is disabled (`.disabled(row.stores.isEmpty)`).
        let addButtons = app.buttons.matching(identifier: "Add to shopping list")
        XCTAssertEqual(addButtons.count, 1, "Expected exactly 1 '+' button (Bread)")
        XCTAssertFalse(
            addButtons.element(boundBy: 0).isEnabled,
            "Bread's '+' should be disabled because it has no tagged stores"
        )
    }
}
