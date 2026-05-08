import Foundation

protocol Clock: Sendable {
    func nowMs() -> Int64
}

struct SystemClock: Clock {
    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

struct FixedClock: Clock {
    let nowMs: Int64
    func nowMs() -> Int64 { nowMs }
}
