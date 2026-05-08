import Foundation

/// Thin abstraction over the slice of Firestore the sync engine and pull
/// coordinator actually use. Lets tests record writes and stub reads
/// without spinning up the Firestore SDK.
///
/// Path conventions:
///   - **Document path** (used by `setDocument`):
///     `users/{uid}/<collection>/<docId>`
///   - **Collection path** (used by `peekHasDocuments` and `fetchAll`):
///     `users/{uid}/<collection>`
///
/// No leading slash in either form.
protocol FirestoreClient: Sendable {
    /// Write `value` to the document at `path`, creating or overwriting.
    func setDocument<T: Encodable & Sendable>(at path: String, value: T) async throws

    /// True if the collection at `collectionPath` contains at least one
    /// document. Used for the cheap "does this uid have cloud data?"
    /// check on the pull side — implemented as a `limit(1).get()` so it's
    /// O(1) regardless of collection size.
    func peekHasDocuments(at collectionPath: String) async throws -> Bool

    /// Fetch every document in `collectionPath`, decoding each into `T`.
    /// Network or decode failures throw; the pull coordinator maps that
    /// to a `PullResult.failure`.
    func fetchAll<T: Decodable & Sendable>(
        _ type: T.Type,
        at collectionPath: String
    ) async throws -> [T]
}
