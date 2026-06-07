import Foundation
import UIKit

/// Uploads item photos to Firebase Storage and returns the download URL.
///
/// Phase 6 ships the protocol and a `NoOpImageUploader` so the form flow
/// works end-to-end. Phase 8 swaps in `FirebaseImageUploader` with EXIF
/// rotation, downscale to 1024px max, JPEG q=0.85, and the actual
/// Firebase Storage upload.
protocol ImageUploader: Sendable {
    /// Upload `image` for `itemId`. Returns the download URL string the
    /// form persists to `items.imageUrl`.
    func upload(image: UIImage, itemId: String) async throws -> String
}

/// Phase 6 placeholder — pretends the upload succeeded and returns a fake
/// URL. Ensures the form can save without crashing while photo upload is
/// not yet wired. Phase 8 replaces this with `FirebaseImageUploader`.
struct NoOpImageUploader: ImageUploader {
    func upload(image: UIImage, itemId: String) async throws -> String {
        // Mimic a tiny delay so the UI's "uploading…" state is visible
        // during local testing. Real upload is many seconds; fake is fast.
        try await Task.sleep(nanoseconds: 200_000_000)
        return "https://example.invalid/storehop/items/\(itemId).jpg"
    }
}
