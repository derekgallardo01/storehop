package com.storehop.app.ui.shop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShoppingSessionTrackerTest {

    @Test fun `the anchor is captured on first call and reused on every later call`() {
        val tracker = ShoppingSessionTracker()
        val first = tracker.sessionStartMs()
        // Sleep is the only way to confirm we're not silently re-anchoring;
        // a few millis is enough to detect a fresh System.currentTimeMillis().
        Thread.sleep(5)
        val second = tracker.sessionStartMs()
        assertThat(second).isEqualTo(first)
    }

    @Test fun `reset clears the anchor so the next call captures a fresh timestamp`() {
        val tracker = ShoppingSessionTracker()
        val first = tracker.sessionStartMs()
        Thread.sleep(5)
        tracker.reset()
        val second = tracker.sessionStartMs()
        assertThat(second).isGreaterThan(first)
    }

    @Test fun `concurrent first-callers all observe the same anchor`() {
        // Two Shop-at-Store ViewModels could plausibly construct in parallel
        // (saved + restored Shop back stack on tab switch). The double-checked
        // lock should give them both the same anchor.
        val tracker = ShoppingSessionTracker()
        val barrier = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val threads = (1..16).map {
            Thread {
                barrier.await()
                results.add(tracker.sessionStartMs())
            }.also { it.start() }
        }
        barrier.countDown()
        threads.forEach { it.join() }
        assertThat(results.toSet()).hasSize(1)
    }
}
