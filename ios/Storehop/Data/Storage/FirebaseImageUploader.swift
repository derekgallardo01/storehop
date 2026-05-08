import Foundation
import UIKit
// Firebase types aren't formally Sendable yet (Auth/Storage/Firestore are
// thread-safe in practice — they do their own internal serialization).
// @preconcurrency suppresses the strict-concurrency warnings until
// Firebase SDK adopts Swift 6 sendability annotations.
@preconcurrency import FirebaseStorage

/// Production `ImageUploader` backed by Firebase Storage.
///
/// Pipeline: `ImageProcessor.processForUpload` → `putDataAsync` to
/// `users/{uid}/items/{itemId}.jpg` → return the download URL.
///
/// Throws `ImageUploaderError.notSignedIn` if no uid is available;
/// `ImageUploaderError.encodingFailed` if the input image can't be encoded
/// (rare, only for invalid CGImage); Firebase errors propagate.
struct FirebaseImageUploader: ImageUploader {
    let storage: Storage
    let session: any UserSessionProvider

    init(storage: Storage = Storage.storage(), session: any UserSessionProvider) {
        self.storage = storage
        self.session = session
    }

    func upload(image: UIImage, itemId: String) async throws -> String {
        guard let uid = await session.currentUserId else {
            throw ImageUploaderError.notSignedIn
        }
        guard let data = ImageProcessor.processForUpload(image) else {
            throw ImageUploaderError.encodingFailed
        }
        let ref = storage.reference().child("users/\(uid)/items/\(itemId).jpg")
        let metadata = StorageMetadata()
        metadata.contentType = "image/jpeg"
        _ = try await ref.putDataAsync(data, metadata: metadata)
        let url = try await ref.downloadURL()
        return url.absoluteString
    }
}

enum ImageUploaderError: Error, Equatable {
    case notSignedIn
    case encodingFailed
}
