import XCTest

/// Visual design-system audit + App Store screenshot tour. Boots the app once
/// per appearance with the curated marketing dataset seeded (via the
/// `-E2ESeedDemoData` launch argument, handled by `AppContainer` in DEBUG),
/// walks every major screen, attaches a screenshot of each step to the test
/// results, and ends. Doesn't assert behavior — its job is to leave a visual
/// artifact a human (or App Store Connect) can scan.
///
/// Screen set captured in BOTH light and dark:
///   01 Store Picker · 02 Shop-at-Store · 03 Edit Aisle Order (best-effort)
///   04 Items list · 05 Item form · 06 Manage Categories (best-effort)
///   07 Settings · 08 Statistics · 09 Household (best-effort)
///
/// Run (per device size — see docs/app-store-screenshots.md):
///   xcodebuild test -only-testing:StorehopUITests/DesignSystemTourTest \
///     -destination "platform=iOS Simulator,name=iPhone 17 Pro Max" \
///     -resultBundlePath /tmp/storehop-tour.xcresult ...
///
/// Then extract the attachments:
///   xcrun xcresulttool get --path /tmp/storehop-tour.xcresult --format json \
///     | <find attachment ids; xcrun xcresulttool export --id ...>
///
/// The two tests below differ only by the appearance launch argument.
final class DesignSystemTourTest: E2EBaseTest {

    func testLightAppearanceTour() {
        run(appearance: .light, suffix: "light")
    }

    func testDarkAppearanceTour() {
        run(appearance: .dark, suffix: "dark")
    }

    private enum Appearance { case light, dark }

    private func run(appearance: Appearance, suffix: String) {
        app.launchArguments = [
            "-UITestE2E",
            // Seed the curated marketing dataset (stores, ~28 items, 8 weeks
            // of backdated purchase history) instead of the tiny canonical
            // fixtures. Handled by `AppContainer.seedDemoDataIfRequested()`
            // in the DEBUG host build.
            "-E2ESeedDemoData",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
            // Force the in-app theme override via the prefs repo. The
            // `-AppleInterfaceStyle` launch arg alone is unreliable on
            // iOS 26+ simulators when the global appearance is set the
            // other way; this path always wins because RootView applies
            // `.preferredColorScheme(themeMode.preferredColorScheme)`.
            appearance == .dark ? "-E2EForceDarkTheme" : "-E2EForceLightTheme",
        ]
        app.launch()

        XCTAssertTrue(
            app.tabBars.buttons["Shop"].waitForExistence(timeout: 15),
            "App didn't render TabView at launch"
        )

        // 01 — Store Picker. Wait on a demo store to confirm the async seed
        // has committed before capturing (seeding runs in RootView's .task).
        XCTAssertTrue(
            app.staticTexts["Continente"].waitForExistence(timeout: 20),
            "Demo data didn't seed — 'Continente' never appeared"
        )
        attach("01-store-picker-\(suffix)")

        // 02 — Shop at a store. Continente carries the most tagged items, so
        // it shows the cross-category grouping, +/- checkmarks, and toolbar.
        app.staticTexts["Continente"].tap()
        _ = app.navigationBars.firstMatch.waitForExistence(timeout: 5)
        sleep(1)
        attach("02-shop-at-store-\(suffix)")
        backToRoot()

        // 03 — Edit Aisle Order (best-effort). Reached from the store row's
        // context menu. Guarded so a selector miss doesn't fail the tour.
        app.staticTexts["Continente"].press(forDuration: 0.9)
        if tapIfExists(app.buttons["Edit aisles"], timeout: 3) {
            sleep(1)
            attach("03-edit-aisles-\(suffix)")
            backToRoot()
        } else {
            dismissTransientUI()
        }

        // 04 — Items list (master library).
        app.tabBars.buttons["Items"].tap()
        _ = app.staticTexts["Whole Milk"].waitForExistence(timeout: 5)
        sleep(1)
        attach("04-items-list-\(suffix)")

        // 05 — Item form. Tapping a row opens the edit form (category picker,
        // store chips, staple/critical/buy-today toggles).
        app.staticTexts["Whole Milk"].tap()
        if app.buttons["Save"].waitForExistence(timeout: 5) {
            sleep(1)
            attach("05-item-form-\(suffix)")
            _ = tapIfExists(app.buttons["Cancel"], timeout: 3)
            sleep(1)
        }

        // 06 — Manage Categories (best-effort). In the Items "more options"
        // menu. The menu button carries an unlocalized key as its label, so
        // this whole branch is guarded.
        if tapIfExists(app.buttons["action_more_options"], timeout: 3) {
            if tapIfExists(app.buttons["Manage categories"], timeout: 3) {
                sleep(1)
                attach("06-manage-categories-\(suffix)")
                backToRoot()
            } else {
                dismissTransientUI()
            }
        }

        // 07 — Settings. Opened via the gear button (accessibility label
        // "Settings"). Presented as a sheet with the Form.
        guard tapIfExists(app.buttons["Settings"], timeout: 5) else { return }
        _ = app.buttons["Done"].waitForExistence(timeout: 5)
        sleep(1)
        attach("07-settings-\(suffix)")

        // 08 — Statistics. NavigationLink inside Settings; the demo purchase
        // history drives the trend line, top items, and per-store/category
        // bars. Scroll the Form into view first in case it's below the fold.
        app.swipeUp()
        sleep(1)
        if tapIfExists(app.staticTexts["Statistics"], timeout: 5) {
            sleep(1)
            attach("08-statistics-\(suffix)")
            backToRoot()
        }

        // 09 — Household (best-effort). NavigationLink under the Account
        // section in Settings.
        if tapIfExists(app.staticTexts["Household"], timeout: 5) {
            sleep(1)
            attach("09-household-\(suffix)")
            backToRoot()
        }
    }

    // MARK: - Helpers

    private func attach(_ name: String) {
        let shot = app.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Tap the leading navigation-bar button (Back / Done) if present, to pop
    /// back toward the root. Safe to call when no nav bar is showing.
    private func backToRoot() {
        let backButton = app.navigationBars.buttons.element(boundBy: 0)
        if backButton.waitForExistence(timeout: 3) {
            backButton.tap()
            sleep(1)
        }
    }

    /// Tap an element only if it materializes within `timeout`. Returns
    /// whether the tap happened, so best-effort branches can bail cleanly.
    @discardableResult
    private func tapIfExists(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        guard element.waitForExistence(timeout: timeout) else { return false }
        element.tap()
        return true
    }

    /// Dismiss a context menu / transient overlay by tapping a neutral point
    /// near the top of the screen (iOS has no Escape key in XCUITest).
    private func dismissTransientUI() {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).tap()
        sleep(1)
    }
}
