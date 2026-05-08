import Foundation

protocol IdGenerator: Sendable {
    func newId() -> String
}

struct UuidV4Generator: IdGenerator {
    func newId() -> String { UUID().uuidString.lowercased() }
}
