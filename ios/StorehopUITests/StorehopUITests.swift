import XCTest

/// Smoke E2E: the host app launches under the E2E test graph (in-memory
/// DB, mocked Firebase, LocalOnlyUserSessionProvider) and the root
/// TabView is reachable with both expected tabs. Fastest possible test
/// that proves the UI-test infrastructure is wired correctly.
///
/// If THIS fails, every other E2E test will fail too — treat it as the
/// canary. Mirrors Android's `AppLaunchTest`.
final class AppLaunchE2ETest: E2EBaseTest {

    func testMainViewLaunchesAndShowsShopAndItemsTabs() {
        launchAppEmpty()
        XCTAssertTrue(
            app.tabBars.buttons["Shop"].exists,
            "Shop tab should appear on the root TabView at launch"
        )
        XCTAssertTrue(
            app.tabBars.buttons["Items"].exists,
            "Items tab should appear next to Shop"
        )
    }
}
