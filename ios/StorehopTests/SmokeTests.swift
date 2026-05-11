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
            // Try the `seed/` subdirectory first (local builds where the
            // folder reference preserves the directory). Fall back to the
            // bundle root (CI builds where xcodegen flat-lists the files
            // because `type: folder` doesn't reliably land in the simulator
            // bundle on macos-15). Either location is fine — the seeder
            // applies the same fallback at runtime.
            let testBundle = Bundle(for: type(of: self))
            let mainBundle = Bundle.main
            let url = testBundle.url(forResource: resource, withExtension: "json", subdirectory: "seed")
                ?? mainBundle.url(forResource: resource, withExtension: "json", subdirectory: "seed")
                ?? testBundle.url(forResource: resource, withExtension: "json")
                ?? mainBundle.url(forResource: resource, withExtension: "json")
            XCTAssertNotNil(url, "Missing \(resource).json in test or app bundle")
        }
    }

    func testAppContainerLiveBuilds() {
        let container = AppContainer.live()
        XCTAssertNotNil(container.clock)
    }
}
