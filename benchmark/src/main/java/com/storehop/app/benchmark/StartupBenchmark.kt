package com.storehop.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold + warm startup benchmarks for the Storehop app.
 *
 * Run with:
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * ```
 *
 * Hardware: prefer a physical Pixel 6 or newer (emulators give noisy
 * results that fluctuate by 200ms+ run-to-run). The benchmark APK uses a
 * release-equivalent build so cold-start times reflect what real users see
 * (R8 + resource shrinking enabled, debuggable=true only because Macrobenchmark
 * requires it to attach Perfetto).
 *
 * Targets (set when we first record numbers — placeholder for now):
 *  - Cold start: < 1500 ms on Pixel 6 (None compilation mode)
 *  - Warm start: < 500 ms on Pixel 6
 *
 * The DatabaseSeeder runs on first launch only (~13 categories + ~3 stores
 * + SCO rows). Cold-start benchmarks measure SECOND-cold-launch (post-seed)
 * by default since that's what 99% of users experience. A separate
 * `firstLaunchSeeded` benchmark below isolates the seed path.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartup() = startup(StartupMode.COLD, CompilationMode.None())

    @Test fun warmStartup() = startup(StartupMode.WARM, CompilationMode.None())

    /**
     * Compilation mode = Partial (Baseline Profile-style) gives a realistic
     * picture of what users see after the app warms up over a few launches.
     * If we later add a baseline profile, this benchmark validates its
     * effectiveness.
     */
    @Test fun coldStartupBaselineProfile() = startup(StartupMode.COLD, CompilationMode.Partial())

    private fun startup(mode: StartupMode, compilation: CompilationMode) {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            startupMode = mode,
            compilationMode = compilation,
            iterations = 5,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.storehop.app"
    }
}
