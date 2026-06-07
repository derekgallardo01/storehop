import XCTest

/// Visual design-system audit. Boots the seeded app once per appearance,
/// walks the major flows, attaches a screenshot of each step to the test
/// results, and ends. Doesn't assert behavior — its job is to leave a
/// visual artifact a human can scan.
///
/// Run via:
///   xcodebuild test -only-testing:StorehopUITests/DesignSystemTourTest \
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
            "-E2ESeedFixtures",
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
        attach("01-store-picker-\(suffix)")

        // ShopAtStore — Lidl. Tests in-store rows, the +/- checkmarks,
        // and the toolbar (sort/show-purchased/more).
        app.staticTexts["Lidl"].tap()
        _ = app.staticTexts["Milk"].waitForExistence(timeout: 5)
        attach("02-shop-at-lidl-\(suffix)")

        // Tap Milk to flip it purchased; ensures the cascade + struck-
        // through styling renders properly in this appearance.
        app.staticTexts["Milk"].tap()
        sleep(1)
        attach("03-shop-at-lidl-purchased-\(suffix)")

        // Back to StorePicker → Items tab.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.tabBars.buttons["Items"].tap()
        _ = app.staticTexts["Bread"].waitForExistence(timeout: 5)
        attach("04-items-list-\(suffix)")

        // Long-press Bread to enter bulk selection mode.
        app.staticTexts["Bread"].press(forDuration: 0.8)
        sleep(1)
        attach("05-items-selection-mode-\(suffix)")

        // Tag-to-stores sheet.
        app.buttons["Tag to stores…"].tap()
        _ = app.buttons["Add stores"].waitForExistence(timeout: 5)
        attach("06-bulk-tag-sheet-\(suffix)")

        // Cancel + exit selection mode.
        app.buttons["Cancel"].tap()
        sleep(1)
        app.buttons["Exit selection"].tap()
        sleep(1)

        // Add-item form.
        app.buttons["Add item"].tap()
        _ = app.textFields["Name"].waitForExistence(timeout: 5)
        attach("07-item-form-add-\(suffix)")

        // Cancel out of the form.
        app.buttons["Cancel"].tap()
        sleep(1)

        // Edit-item form (tap a row).
        app.staticTexts["Milk"].tap()
        _ = app.buttons["Save"].waitForExistence(timeout: 5)
        attach("08-item-form-edit-\(suffix)")
    }

    private func attach(_ name: String) {
        let shot = app.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
