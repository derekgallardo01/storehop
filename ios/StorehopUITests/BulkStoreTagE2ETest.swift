import XCTest

/// v0.8.1 bulk store-tag from the Items list. Long-press a row to enter
/// selection mode; the toolbar swaps to a contextual variant with
/// "[N] selected" + a "Tag to stores…" action. Tapping it opens
/// `BulkStorePickerSheet`; picking stores + Apply unions them with each
/// selected item's existing store set (add-only semantics).
///
/// Uses "Bread" because it has no tagged stores in the seed (Milk and
/// Eggs are already tagged at one or both stores). After applying Lidl,
/// Bread should newly appear at Lidl.
///
/// iOS-only — Android equivalent ships as inline assertions in the
/// `ItemsListViewModelTest` suite at the unit level.
final class BulkStoreTagE2ETest: E2EBaseTest {

    func testBulkTagAddsSelectedStoresToEverySelectedItem() {
        launchAppWithSeededFixtures()
        openItemsTab()

        // Wait for the seeded rows.
        XCTAssertTrue(
            app.staticTexts["Bread"].waitForExistence(timeout: 10),
            "Seeded Bread row should render"
        )

        // Long-press Bread to enter selection mode.
        app.staticTexts["Bread"].press(forDuration: 0.8)

        // The contextual toolbar now shows "1 selected" + a Tag-to-stores
        // action (accessibilityLabel "Tag to stores…", key
        // `items_action_tag_to_stores`).
        let tagAction = app.buttons["Tag to stores…"]
        XCTAssertTrue(
            tagAction.waitForExistence(timeout: 5),
            "'Tag to stores…' action should appear in selection-mode toolbar"
        )
        tagAction.tap()

        // Bulk picker sheet opens. Tap the Lidl chip to pick it.
        let lidlChip = app.buttons["Lidl"]
        XCTAssertTrue(
            lidlChip.waitForExistence(timeout: 5),
            "Bulk picker sheet should show Lidl as a chip"
        )
        lidlChip.tap()

        // Apply with "Add stores" (key `items_bulk_tag_apply`).
        app.buttons["Add stores"].tap()

        // Sheet dismisses, then the async bulk-tag completes and the VM
        // clears `selectedItemIds`. The Items list leaves selection mode,
        // FAB + +/- toggles reappear. Wait for the FAB to confirm
        // selection mode has fully exited.
        let fab = app.buttons["Add item"]
        let exited = fab.waitForExistence(timeout: 10)
        if !exited {
            XCTFail("Add-item FAB should re-render after selection mode exits. Tree:\n\(app.debugDescription)")
        }

        // After bulk-tag, Bread now has Lidl. The button count flips
        // from {-, -, + disabled} to {-, -, -} — all three items are
        // needed somewhere and have at least one tagged store. The
        // Android equivalent of this assertion checks the repo xref
        // directly; on iOS we use the visible toggle state instead.
        let removeButtons = app.buttons.matching(identifier: "Remove from shopping list")
        XCTAssertEqual(
            removeButtons.count, 3,
            "After bulk-tagging Bread to Lidl, all three items should show '−' (they're all needed somewhere now)"
        )
    }
}
