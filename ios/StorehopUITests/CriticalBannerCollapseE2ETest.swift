import XCTest

/// v0.6.0 in-store critical-items banner collapse/expand. Default state
/// is collapsed (chevron-down + count headline only). Tap to expand —
/// the comma-list of priority item names appears + chevron flips up.
///
/// Mirrors Android's `CriticalBannerCollapseE2ETest`. The seed includes
/// an extra priority-flagged "Coffee" item tagged at Lidl so the banner
/// has content to render.
final class CriticalBannerCollapseE2ETest: E2EBaseTest {

    func testBannerStartsCollapsedAndExpandsOnTap() {
        launchAppWithSeededFixturesAndCriticalItem()

        // StorePicker → Lidl.
        XCTAssertTrue(
            app.staticTexts["Lidl"].waitForExistence(timeout: 10),
            "Seeded Lidl store should appear"
        )
        app.staticTexts["Lidl"].tap()

        // Wait for the banner to render in collapsed state. The view
        // assigns one of two stable identifiers based on expanded state.
        // `.accessibilityElement(children: .combine)` keeps the element
        // under `.otherElements` in XCUITest's element tree.
        let collapsed = app.descendants(matching: .any)["critical_banner_collapsed"]
        XCTAssertTrue(
            collapsed.waitForExistence(timeout: 10),
            "Critical banner should render in collapsed state by default. Tree:\n\(app.debugDescription)"
        )

        // Tap the banner to expand.
        collapsed.tap()

        // The identifier flips when expanded toggles.
        let expanded = app.descendants(matching: .any)["critical_banner_expanded"]
        XCTAssertTrue(
            expanded.waitForExistence(timeout: 5),
            "Critical banner should switch to expanded state after tap"
        )
    }
}
