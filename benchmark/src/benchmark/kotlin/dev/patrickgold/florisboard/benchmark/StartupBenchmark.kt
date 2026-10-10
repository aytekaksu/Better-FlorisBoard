/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TargetPackage = "dev.patrickgold.florisboard.bench"
private const val TargetImeService = "$TargetPackage/dev.patrickgold.florisboard.FlorisImeService"

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a cold state.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark : AbstractStartupBenchmark(StartupMode.COLD)

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a warm state.
 */
@RunWith(AndroidJUnit4::class)
class WarmStartupBenchmark : AbstractStartupBenchmark(StartupMode.WARM)

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a hot state.
 */
@RunWith(AndroidJUnit4::class)
class HotStartupBenchmark : AbstractStartupBenchmark(StartupMode.HOT)

/**
 * Base class for benchmarks with different startup modes.
 * Enables app startups from various states of baseline profile or [CompilationMode]s.
 *
 * Original source of this test: https://github.com/android/nowinandroid/blob/b4a2f35ed23b2cf40fe90311bdac2688d9cb69e2/benchmark/src/main/java/com/google/samples/apps/nowinandroid/startup/StartupBenchmark.kt
 */
abstract class AbstractStartupBenchmark(private val startupMode: StartupMode) {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfileDisabled() = startup(
        CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Disable,
            warmupIterations = 1,
        ),
    )

    @Test
    fun startupBaselineProfile() = startup(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun startupFullCompilation() = startup(CompilationMode.Full())

    private fun startup(compilationMode: CompilationMode) {
        // Reselecting the active IME every iteration can wake it after a cold-start stop.
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        uiDevice.executeShellCommand("ime enable $TargetImeService")
        uiDevice.executeShellCommand("ime set $TargetImeService")

        benchmarkRule.measureRepeated(
            packageName = TargetPackage,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            iterations = 10,
            startupMode = startupMode,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle(5000)
        }
    }
}
