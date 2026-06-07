import XCTest

/// Base class for the iOS E2E suite. Mirrors Android's `E2EBaseTest`:
/// each test runs against a freshly-launched host app, configured via
/// launch arguments to use the in-memory `AppContainer.e2e(...)` graph
/// (no Firebase, no network, deterministic UID `local-only`).
///
/// Subclasses call either `launchAppWithSeededFixtures()` for the
/// canonical 3-items/2-stores/1-category dataset or `launchAppEmpty()`
/// for a clean DB.
///
/// English is forced via the standard `-AppleLanguages` launch arg so
/// the en-only assertion strings ("Shop", "Add item", "Switch to
/// category sort", etc.) match the user-facing text regardless of the
/// simulator's host language.
class E2EBaseTest: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        try super.setUpWithError()
        continueAfterFailure = false
        app = XCUIApplication()
    }

    /// Launch the app in E2E mode with no seeded data (empty DB).
    func launchAppEmpty() {
        app.launchArguments = [
            "-UITestE2E",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        app.launch()
        XCTAssertTrue(
            app.tabBars.buttons["Shop"].waitForExistence(timeout: 15),
            "Root TabView should have rendered after launch"
        )
    }

    /// Launch the app in E2E mode with the canonical fixtures pre-seeded:
    /// stores Lidl + Aldi, category Dairy, items Milk / Eggs / Bread, and
    /// the xrefs (Milk @ both, Eggs @ Lidl, Bread @ none).
    func launchAppWithSeededFixtures() {
        app.launchArguments = [
            "-UITestE2E",
            "-E2ESeedFixtures",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        app.launch()
        XCTAssertTrue(
            app.tabBars.buttons["Shop"].waitForExistence(timeout: 15),
            "Root TabView should have rendered after launch"
        )
    }

    /// Like `launchAppWithSeededFixtures()` but also adds a priority-flagged
    /// "Coffee" item tagged at Lidl, so the in-store critical banner has
    /// content to render. Used by `CriticalBannerCollapseE2ETest`.
    func launchAppWithSeededFixturesAndCriticalItem() {
        app.launchArguments = [
            "-UITestE2E",
            "-E2ESeedFixtures",
            "-E2ESeedCriticalFixture",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        app.launch()
        XCTAssertTrue(
            app.tabBars.buttons["Shop"].waitForExistence(timeout: 15),
            "Root TabView should have rendered after launch"
        )
    }

    // MARK: - Navigation helpers

    /// Switch to the Shop tab. The Shop tab is selected by default at
    /// launch; calling this after another tab keeps tests explicit.
    func openShopTab() {
        app.tabBars.buttons["Shop"].tap()
    }

    /// Switch to the Items tab.
    func openItemsTab() {
        app.tabBars.buttons["Items"].tap()
    }
}
