/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.benchmark

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Lazy-initialized test-suite global state for warnings around measurement inaccuracy.
 */
internal object WarningState {
    private const val TAG = "Benchmark"

    val WARNING_PREFIX: String
    private var warningString: String? = null

    /**
     * Battery percentage required to avoid low battery warning.
     *
     * This number is supposed to be a conservative cutoff for when low-battery-triggered power
     * savings modes (such as disabling cores) may be enabled. It's possible that
     * [BatteryManager.EXTRA_BATTERY_LOW] is a better source of truth for this, but we want to be
     * conservative in case the device loses power slowly while benchmarks run.
     */
    private const val MINIMUM_BATTERY_PERCENT = 25

    fun acquireWarningStringForLogging(): String? {
        val ret = warningString
        warningString = null
        return ret
    }

    val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT

    private val isDeviceRooted =
        arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        ).any { File(it).exists() }

    init {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appInfo = context.applicationInfo
        var warningPrefix = ""
        var warningString = ""
        if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            warningPrefix += "DEBUGGABLE_"
            warningString += """
                |WARNING: Debuggable Benchmark
                |    Benchmark is running with debuggable=true, which drastically reduces
                |    runtime performance in order to support debugging features. Run
                |    benchmarks with debuggable=false. Debuggable affects execution speed
                |    in ways that mean benchmark improvements might not carry over to a
                |    real user's experience (or even regress release performance).
            """.trimMarginWrapNewlines()
        }
        if (isEmulator) {
            warningPrefix += "EMULATOR_"
            warningString += """
                |WARNING: Running on Emulator
                |    Benchmark is running on an emulator, which is not representative of
                |    real user devices. Use a physical device to benchmark. Emulator
                |    benchmark improvements might not carry over to a real user's
                |    experience (or even regress real device performance).
            """.trimMarginWrapNewlines()
        }
        if (Build.FINGERPRINT.contains(":eng/")) {
            warningPrefix += "ENG-BUILD_"
            warningString += """
                |WARNING: Running on Eng Build
                |    Benchmark is running on device flashed with a '-eng' build. Eng builds
                |    of the platform drastically reduce performance to enable testing
                |    changes quickly. For this reason they should not be used for
                |    benchmarking. Use a '-user' or '-userdebug' system image.
            """.trimMarginWrapNewlines()
        }

        if (isDeviceRooted && !Clocks.areLocked) {
            warningPrefix += "UNLOCKED_"
            warningString += """
                |WARNING: Unlocked CPU clocks
                |    Benchmark appears to be running on a rooted device with unlocked CPU
                |    clocks. Unlocked CPU clocks can lead to inconsistent results due to
                |    dynamic frequency scaling, and thermal throttling. On a rooted device,
                |    lock your device clocks to a stable frequency with `./gradlew lockClocks`
            """.trimMarginWrapNewlines()
        } else if (
            AndroidBenchmarkRunner.isSustainedPerformanceModeSupported() &&
            !AndroidBenchmarkRunner.sustainedPerformanceModeInUse
        ) {
            warningPrefix += "UNSUSTAINED-RUNNER-MISSING_"
            warningString += """
                |WARNING: Cannot use SustainedPerformanceMode without AndroidBenchmarkRunner
                |    Benchmark running on device that supports Window.setSustainedPerformanceMode,
                |    but not using the AndroidBenchmarkRunner. This runner is required to limit
                |    CPU clock max frequency, to prevent thermal throttling. To fix this, add the
                |    following to your benchmark module-level build.gradle:
                |        android.defaultConfig.testInstrumentationRunner
                |            = "androidx.benchmark.AndroidBenchmarkRunner"
            """.trimMarginWrapNewlines()
        } else if (!AndroidBenchmarkRunner.runnerInUse) {
            warningPrefix += "RUNNER-MISSING_"
            warningString += """
                |WARNING: Not using AndroidBenchmarkRunner
                |    AndroidBenchmarkRunner should be used to isolate benchmarks from interference
                |    from other visible apps. To fix this, add the following to your module-level
                |    build.gradle:
                |        android.defaultConfig.testInstrumentationRunner
                |            = "androidx.benchmark.AndroidBenchmarkRunner"
            """.trimMarginWrapNewlines()
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryPercent = context.registerReceiver(null, filter)?.run {
            val level = getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            level * 100 / scale
        } ?: 100
        if (batteryPercent < MINIMUM_BATTERY_PERCENT) {
            warningPrefix += "LOW-BATTERY_"
            warningString += """
                |WARNING: Device has low battery ($batteryPercent%)
                |    When battery is low, devices will often reduce performance (e.g. disabling big
                |    cores) to save remaining battery. This occurs even when they are plugged in.
                |    Wait for your battery to charge to at least $MINIMUM_BATTERY_PERCENT%.
                |    Currently at $batteryPercent%.
            """.trimMarginWrapNewlines()
        }

        WARNING_PREFIX = warningPrefix
        if (!warningString.isEmpty()) {
            this.warningString = warningString
            warningString.split("\n").map { Log.w(TAG, it) }
        }
    }

    /**
     * Same as trimMargins, but add newlines on either side.
     */
    private fun String.trimMarginWrapNewlines(): String {
        return "\n" + trimMargin() + " \n"
    }
}