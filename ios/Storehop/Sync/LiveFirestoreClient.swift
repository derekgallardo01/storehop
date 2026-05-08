import Foundation
// See FirebaseImageUploader for the @preconcurrency rationale.
@preconcurrency import FirebaseFirestore

/// Production `FirestoreClient` backed by the Firebase Firestore iOS SDK.
///
/// Encodes values via `Firestore`'s Codable integration (`setData(from:)`
/// for writes, `data(as:)` for reads) so Swift `Codable` DTOs serialize
/// directly to Firestore documents without manual `[String: Any]`
/// plumbing. Field names are the Codable property names; nested optionals
/// serialize as `null` (matches Android's reflective serialization, since
/// Firebase's Kotlin client uses the same JSON-shape under the hood).
final class LiveFirestoreClient: FirestoreClient, @unchecked Sendable {
    private let firestore: Firestore

    init(firestore: Firestore = Firestore.firestore()) {
        self.firestore = firestore
    }

    func setDocument<T: Encodable & Sendable>(at path: String, value: T) async throws {
        let ref = firestore.document(path)
        try await ref.setData(from: value)
    }

    func peekHasDocuments(at collectionPath: String) async throws -> Bool {
        let snapshot = try await firestore
            .collection(collectionPath)
            .limit(to: 1)
            .getDocuments()
        return !snapshot.isEmpty
    }

    func fetchAll<T: Decodable & Sendable>(
        _ type: T.Type,
        at collectionPath: String
    ) async throws -> [T] {
        let snapshot = try await firestore
            .collection(collectionPath)
            .getDocuments()
        return try snapshot.documents.map { try $0.data(as: T.self) }
    }
}
