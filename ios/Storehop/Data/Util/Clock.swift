import Foundation

protocol Clock: Sendable {
    func nowMs() -> Int64
}

struct SystemClock: Clock {
    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// Fixed-instant clock for previews and tests. The protocol requires a
/// method named `nowMs()`, so the stored property uses a different name.
struct FixedClock: Clock {
    private let instant: Int64
    init(nowMs: Int64) { self.instant = nowMs }
    func nowMs() -> Int64 { instant }
}
