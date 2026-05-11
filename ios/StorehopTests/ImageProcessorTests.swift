import XCTest
import UIKit
@testable import Storehop

/// Verifies the image-processing pipeline used by `FirebaseImageUploader`.
/// The Firebase upload itself can't be tested without an emulator, but the
/// pure pipeline (rotation + downscale + JPEG encode) is testable here.
final class ImageProcessorTests: XCTestCase {

    // MARK: - Downscale

    func testDownscaleNoOpWhenAlreadyWithinBounds() {
        let small = makeSolidImage(width: 800, height: 600, color: .red)
        let result = ImageProcessor.downscaleIfNeeded(small, maxDimension: 1024)
        XCTAssertEqual(result.size.width, 800)
        XCTAssertEqual(result.size.height, 600)
    }

    func testDownscaleConstrainsLandscapeToMaxDimensionLongEdge() {
        let big = makeSolidImage(width: 4000, height: 3000, color: .blue)
        let result = ImageProcessor.downscaleIfNeeded(big, maxDimension: 1024)
        // Long edge clamped to 1024; aspect ratio preserved (4:3 → 1024:768).
        XCTAssertEqual(result.size.width, 1024)
        XCTAssertEqual(result.size.height, 768)
    }

    func testDownscaleConstrainsPortraitToMaxDimensionLongEdge() {
        let big = makeSolidImage(width: 1500, height: 3000, color: .green)
        let result = ImageProcessor.downscaleIfNeeded(big, maxDimension: 1024)
        // Portrait — long edge is height. 1:2 → 512:1024.
        XCTAssertEqual(result.size.height, 1024)
        XCTAssertEqual(result.size.width, 512)
    }

    func testDownscalePreservesAspectRatioWithRoundedDimensions() {
        let odd = makeSolidImage(width: 1333, height: 999, color: .orange)
        let result = ImageProcessor.downscaleIfNeeded(odd, maxDimension: 800)
        XCTAssertEqual(result.size.width, 800)
        // 999 * 800 / 1333 ≈ 599.49 → rounds to 599
        XCTAssertLessThanOrEqual(abs(result.size.height - 599), 1)
    }

    // MARK: - Orientation

    func testBurnInOrientationUpReturnsImageUnchanged() {
        let upright = makeSolidImage(width: 100, height: 100, color: .gray, orientation: .up)
        let result = ImageProcessor.burnInOrientation(upright)
        XCTAssertEqual(result.imageOrientation, .up)
        XCTAssertEqual(result.size, upright.size)
    }

    func testBurnInOrientationProducesUpOrientationForRotatedInput() {
        // A 100×200 image marked with .right orientation displays as 200×100.
        // After burning in, the resulting image has its pixels rotated and
        // the orientation flag flips to .up — the new pixel-size matches
        // the previously-displayed size.
        let rotated = makeSolidImage(width: 100, height: 200, color: .purple, orientation: .right)
        let result = ImageProcessor.burnInOrientation(rotated)
        XCTAssertEqual(result.imageOrientation, .up)
        // .right means the underlying pixels are 100×200 but display as
        // 200×100; UIImage.size returns the displayed size, so we expect
        // 200×100 here. (Prior version of this test had the width/height
        // asserts swapped vs the comment — pre-existing copy-paste bug
        // surfaced once iOS CI started actually running the test phase.)
        XCTAssertEqual(result.size.width, 200)
        XCTAssertEqual(result.size.height, 100)
    }

    // MARK: - Full pipeline

    func testProcessForUploadReturnsValidJpegData() throws {
        let image = makeSolidImage(width: 500, height: 500, color: .magenta)
        let data = try XCTUnwrap(ImageProcessor.processForUpload(image))
        // JPEG SOI marker.
        XCTAssertEqual(data[0], 0xFF)
        XCTAssertEqual(data[1], 0xD8)
        // Round-trip should produce a UIImage with the same dimensions.
        let decoded = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(Int(decoded.size.width), 500)
        XCTAssertEqual(Int(decoded.size.height), 500)
    }

    func testProcessForUploadDownscalesLargeImage() throws {
        let big = makeSolidImage(width: 3000, height: 2000, color: .yellow)
        let data = try XCTUnwrap(ImageProcessor.processForUpload(big))
        let decoded = try XCTUnwrap(UIImage(data: data))
        // After downscale, long edge ≤ 1024 (with a 1px tolerance for
        // rounding).
        let longEdge = max(decoded.size.width, decoded.size.height)
        XCTAssertLessThanOrEqual(longEdge, 1024 + 1)
        XCTAssertGreaterThanOrEqual(longEdge, 1024 - 2)
    }

    // MARK: - Helper

    /// Builds a solid-color UIImage at the requested size + orientation.
    /// Uses Core Graphics directly so tests don't depend on a renderer's
    /// device-specific scale.
    private func makeSolidImage(
        width: Int,
        height: Int,
        color: UIColor,
        orientation: UIImage.Orientation = .up
    ) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        let baseImage = renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        if orientation == .up { return baseImage }
        guard let cg = baseImage.cgImage else { return baseImage }
        return UIImage(cgImage: cg, scale: 1, orientation: orientation)
    }
}
