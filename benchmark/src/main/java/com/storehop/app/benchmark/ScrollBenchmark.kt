package com.storehop.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scroll-FPS benchmark on the Items list. Pre-condition: the device has
 * the app installed with enough items to scroll meaningfully — for a real
 * baseline run, seed ~200 items + xrefs via CSV import before running
 * (Settings → Data → Import items).
 *
 * Reports `FrameTimingMetric`: median + 95th-percentile frame durations in
 * ms. 60fps target = 16.6 ms; 90fps = 11.1 ms. Anything above 16.6 at the
 * 95th percentile is a jank flag.
 *
 * Run with:
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *     --tests "com.storehop.app.benchmark.ScrollBenchmark"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun scrollItemsListNone() = scrollItemsList(CompilationMode.None())

    @Test fun scrollItemsListPartial() = scrollItemsList(CompilationMode.Partial())

    private fun scrollItemsList(compilation: CompilationMode) {
        rule.measureRepeated(
            packageName = StartupBenchmark.TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilation,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                // Wait for the bottom-nav Items tab to be visible, then
                // tap it. We use a descriptive content-desc match so the
                // benchmark survives string changes.
                device.wait(Until.hasObject(By.descContains("Items")), 5_000)
                device.findObject(By.descContains("Items"))?.click()
                // Settle a frame so the list is rendered before scrolling.
                device.waitForIdle()
            },
        ) {
            val list = device.findObject(By.scrollable(true))
            if (list != null) {
                repeat(3) { scroll(list, Direction.DOWN) }
                repeat(3) { scroll(list, Direction.UP) }
            }
        }
    }

    private fun scroll(target: UiObject2, direction: Direction) {
        target.setGestureMargin(target.visibleBounds.height() / 5)
        target.fling(direction)
    }
}
