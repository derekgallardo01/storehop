import XCTest
@testable import Storehop

final class SmokeTests: XCTestCase {

    func testFixedClockReturnsSeedTimestamp() {
        let clock = FixedClock(nowMs: 1_730_000_000_000)
        XCTAssertEqual(clock.nowMs(), 1_730_000_000_000)
    }

    func testUuidGeneratorProducesUniqueLowercaseIds() {
        let gen = UuidV4Generator()
        let a = gen.newId()
        let b = gen.newId()
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(a, a.lowercased())
        XCTAssertTrue(a.contains("-"))
    }

    func testSeedJsonsAreBundled() throws {
        for resource in ["stores", "categories", "store_categories"] {
            let url = Bundle(for: type(of: self)).url(forResource: resource, withExtension: "json", subdirectory: "seed")
                ?? Bundle.main.url(forResource: resource, withExtension: "json", subdirectory: "seed")
            XCTAssertNotNil(url, "Missing seed/\(resource).json in app bundle")
        }
    }

    func testAppContainerLiveBuilds() {
        let container = AppContainer.live()
        XCTAssertNotNil(container.clock)
    }
}
