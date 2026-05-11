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

    func testSeedJsonsAreAvailableViaBundleOrEmbeddedFallback() throws {
        // The seeder accepts EITHER an on-disk bundled file OR the
        // Swift-string mirror in BundledSeedJson. We assert at least one
        // succeeds for each resource — that's what production cares
        // about. On macos-15 CI, xcodebuild silently drops the bundle
        // resources, so the embedded fallback is the only path that
        // works there.
        for resource in ["stores", "categories", "store_categories"] {
            let testBundle = Bundle(for: type(of: self))
            let mainBundle = Bundle.main
            let bundleUrl = testBundle.url(forResource: resource, withExtension: "json", subdirectory: "seed")
                ?? mainBundle.url(forResource: resource, withExtension: "json", subdirectory: "seed")
                ?? testBundle.url(forResource: resource, withExtension: "json")
                ?? mainBundle.url(forResource: resource, withExtension: "json")
            let embedded = BundledSeedJson.text(forName: resource)
            XCTAssertTrue(
                bundleUrl != nil || embedded != nil,
                "Missing \(resource).json in test bundle, app bundle, AND embedded fallback"
            )
        }
    }

    func testAppContainerLiveBuilds() {
        let container = AppContainer.live()
        XCTAssertNotNil(container.clock)
    }
}
