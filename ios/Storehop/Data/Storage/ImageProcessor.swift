import Foundation
import UIKit

/// Pure image-processing pipeline for item photos. Pulled out of the
/// uploader so tests can verify rotation + downscale + JPEG encode without
/// spinning up Firebase.
///
/// Pipeline:
///   1. **Burn in EXIF orientation.** `UIImage(data:)` keeps EXIF as
///      metadata; redrawing through a `UIGraphicsImageRenderer` produces
///      `.up`-oriented pixels so consumers (web, Android) see a correctly
///      oriented JPEG without needing to honor EXIF themselves.
///   2. **Downscale** so the long edge is at most `maxDimension` px. Caps
///      upload bandwidth and Firebase Storage cost.
///   3. **Encode** as JPEG at `jpegQuality`.
///
/// Mirrors Android `ImageUploader.loadAndCompress`. Same dimension cap
/// (1024px) and quality (0.85) so a photo uploaded from either platform
/// looks identical on the other.
enum ImageProcessor {
    static let maxDimension: CGFloat = 1024
    static let jpegQuality: CGFloat = 0.85

    /// Process `image` for upload. Returns nil if JPEG encoding fails
    /// (extremely rare — typically only for invalid CGImage).
    static func processForUpload(_ image: UIImage) -> Data? {
        let normalized = burnInOrientation(image)
        let bounded = downscaleIfNeeded(normalized, maxDimension: maxDimension)
        return bounded.jpegData(compressionQuality: jpegQuality)
    }

    /// Redraw the image so its pixels match the displayed orientation.
    /// `UIGraphicsImageRenderer` handles this naturally — drawing at
    /// `.zero` honors `imageOrientation`. The output `UIImage` is `.up`.
    static func burnInOrientation(_ image: UIImage) -> UIImage {
        guard image.imageOrientation != .up else { return image }
        let format = UIGraphicsImageRendererFormat.default()
        // Match the source image's scale so we don't accidentally double
        // pixel density on a Retina device.
        format.scale = image.scale
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    /// Scale the image so its long edge is at most `maxDimension` points.
    /// Aspect ratio preserved. No-op if already within bounds.
    static func downscaleIfNeeded(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let longEdge = max(image.size.width, image.size.height)
        guard longEdge > maxDimension else { return image }
        let ratio = maxDimension / longEdge
        let targetSize = CGSize(
            width: (image.size.width * ratio).rounded(),
            height: (image.size.height * ratio).rounded()
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1  // Output a 1x image — we control pixel size directly.
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }
}
