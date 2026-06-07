import XCTest

/// v0.5.1 cross-store cascade. Marking Milk purchased at Lidl should
/// clear it from Aldi too — one trip, list cleared everywhere.
///
/// XCUITest can't directly read the host-app database (each test runs
/// in a separate process), so Android's "verify via xrefDao" approach
/// doesn't translate. Instead we verify via the UI surface that
/// reflects the same invariant: tap Milk at Lidl to mark purchased,
/// navigate over to Aldi, and confirm Milk's row there is in the
/// purchased state (the `checkmark.circle.fill` indicator, not the
/// `circle` outline).
///
/// Mirrors Android's `CrossStoreCascadeE2ETest`.
final class CrossStoreCascadeE2ETest: E2EBaseTest {

    func testCheckOffAtOneStoreCascadesToOthers() {
        launchAppWithSeededFixtures()

        // StorePicker → tap "Lidl".
        XCTAssertTrue(
            app.staticTexts["Lidl"].waitForExistence(timeout: 10),
            "Seeded Lidl store should appear"
        )
        app.staticTexts["Lidl"].tap()

        // Wait for Milk to render in Lidl, then tap to mark purchased.
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 10),
            "Milk should render in Lidl"
        )
        app.staticTexts["Milk"].tap()

        // The cross-store cascade runs in `markPurchasedAtStore` — every
        // alive xref for the item flips isNeeded=false. After the tap,
        // Milk's row at Lidl shows the purchased checkmark and is struck
        // through. Wait briefly for the write to land.
        let purchasedAtLidl = app.images["checkmark.circle.fill"]
        XCTAssertTrue(
            purchasedAtLidl.waitForExistence(timeout: 5),
            "Milk should appear purchased (filled checkmark) at Lidl after tap"
        )

        // Navigate back to the StorePicker.
        app.navigationBars.buttons.element(boundBy: 0).tap() // Back button

        // Then into Aldi.
        XCTAssertTrue(
            app.staticTexts["Aldi"].waitForExistence(timeout: 5),
            "Should return to the StorePicker showing Aldi"
        )
        app.staticTexts["Aldi"].tap()

        // Milk should still appear (showPurchased defaults to true), but
        // in purchased state. The same `checkmark.circle.fill` indicator
        // proves the cascade — without it, Milk would render as an
        // outline `circle` (still-needed).
        XCTAssertTrue(
            app.staticTexts["Milk"].waitForExistence(timeout: 5),
            "Milk should still render in Aldi after the cascade"
        )
        XCTAssertTrue(
            app.images["checkmark.circle.fill"].exists,
            "Milk at Aldi should reflect the cross-store cascade — its checkmark should be filled, same as at Lidl"
        )
    }
}
