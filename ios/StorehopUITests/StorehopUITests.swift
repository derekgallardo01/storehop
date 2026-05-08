import XCTest

final class StorehopUITests: XCTestCase {

    func testAppLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Storehop"].waitForExistence(timeout: 5))
    }
}
