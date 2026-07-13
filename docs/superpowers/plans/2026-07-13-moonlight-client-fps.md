# Moonlight Client FPS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit the frame rate requested by the Moonlight client whenever the selected hardware encoder supports that rate.

**Architecture:** Configure MediaCodec's surface-input FPS limiter before encoding so predictive frames remain valid. Derive RTP timestamps from each encoded frame's MediaCodec presentation timestamp so network timing follows the actual limited cadence.

**Tech Stack:** Kotlin, Android MediaCodec/MediaFormat, GameStream RTP, JUnit 4, Gradle Android plugin.

## Global Constraints

- Accept any positive client FPS that `EncoderSelector` reports as supported, including 30 and 60 FPS.
- Keep codec, bitrate, resolution, audio, and the bounded two-frame sender queue behavior unchanged.
- Reject unsupported size/rate combinations through the existing stream-configuration error path.
- Validate emitted cadence over at least 10 seconds after startup with a maximum 10 percent deviation from the requested rate.
- Do not commit the local `artifacts/` or `logs/` evidence directories.

## File Structure

- Create `src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderFrameRateConfig.kt`: pure requested-rate values plus the MediaFormat application boundary.
- Create `src/android/app/src/test/java/org/citra/citra_emu/moonlight/EncoderFrameRateConfigTest.kt`: unit coverage for arbitrary requested rates.
- Modify `src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderSession.kt`: apply the surface-input rate limit.
- Modify `src/android/app/src/main/java/org/citra/citra_emu/moonlight/server/VideoRtpTransport.kt`: use presentation timestamps for RTP timing.
- Modify `src/android/app/src/test/java/org/citra/citra_emu/moonlight/RtpTimestampTest.kt`: cover PTS normalization and monotonic behavior.

---

### Task 1: Limit MediaCodec Surface Input To The Requested FPS

**Files:**
- Create: `src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderFrameRateConfig.kt`
- Create: `src/android/app/src/test/java/org/citra/citra_emu/moonlight/EncoderFrameRateConfigTest.kt`
- Modify: `src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderSession.kt:33`

**Interfaces:**
- Consumes: `StreamConfig.fps: Int` after `EncoderSelector` has accepted the size/rate combination.
- Produces: `EncoderFrameRateConfig.forFps(fps: Int): EncoderFrameRateValues` and `EncoderFrameRateConfig.applyTo(format: MediaFormat, fps: Int)`.

- [ ] **Step 1: Write the failing unit test**

Create `EncoderFrameRateConfigTest.kt`:

```kotlin
package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.encoder.EncoderFrameRateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderFrameRateConfigTest {
    @Test
    fun preservesThirtyFpsRequest() {
        val values = EncoderFrameRateConfig.forFps(30)

        assertEquals(30, values.frameRate)
        assertEquals(30, values.operatingRate)
        assertEquals(30f, values.maxFpsToEncoder)
    }

    @Test
    fun preservesArbitrarySupportedFpsRequest() {
        val values = EncoderFrameRateConfig.forFps(45)

        assertEquals(45, values.frameRate)
        assertEquals(45, values.operatingRate)
        assertEquals(45f, values.maxFpsToEncoder)
    }

    @Test
    fun doesNotRepeatFramesBeyondRequestedLimit() {
        val values = EncoderFrameRateConfig.forFps(60)

        assertNull(values.repeatPreviousFrameAfterUs)
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat -PazaharAbiFilters=arm64-v8a testVanillaDebugUnitTest --tests org.citra.citra_emu.moonlight.EncoderFrameRateConfigTest --console=plain
```

Expected: compilation fails because `EncoderFrameRateConfig` does not exist.

- [ ] **Step 3: Implement the rate configuration**

Create `EncoderFrameRateConfig.kt`:

```kotlin
package org.citra.citra_emu.moonlight.encoder

import android.media.MediaFormat

internal data class EncoderFrameRateValues(
    val frameRate: Int,
    val operatingRate: Int,
    val maxFpsToEncoder: Float,
    val repeatPreviousFrameAfterUs: Long?,
)

internal object EncoderFrameRateConfig {
    fun forFps(fps: Int): EncoderFrameRateValues {
        require(fps > 0) { "FPS must be positive" }
        return EncoderFrameRateValues(
            frameRate = fps,
            operatingRate = fps,
            maxFpsToEncoder = fps.toFloat(),
            repeatPreviousFrameAfterUs = null,
        )
    }

    fun applyTo(format: MediaFormat, fps: Int) {
        val values = forFps(fps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, values.frameRate)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, values.operatingRate)
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, values.maxFpsToEncoder)
        values.repeatPreviousFrameAfterUs?.let { repeatAfterUs ->
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatAfterUs)
        }
    }
}
```

In `EncoderSession.start()`, replace the direct frame-rate assignments with:

```kotlin
EncoderFrameRateConfig.applyTo(this, config.fps)
```

Delete the now-unused `repeatFrameAfterUs()` method.

- [ ] **Step 4: Run the targeted test and verify GREEN**

Run the Step 2 command again.

Expected: `BUILD SUCCESSFUL` and both tests pass.

- [ ] **Step 5: Commit the independently testable encoder change**

```powershell
git add -- 'src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderFrameRateConfig.kt' 'src/android/app/src/main/java/org/citra/citra_emu/moonlight/encoder/EncoderSession.kt' 'src/android/app/src/test/java/org/citra/citra_emu/moonlight/EncoderFrameRateConfigTest.kt'
git commit -m "Limit Moonlight encoder to client FPS"
```

### Task 2: Base RTP Timestamps On Encoded Presentation Time

**Files:**
- Modify: `src/android/app/src/main/java/org/citra/citra_emu/moonlight/server/VideoRtpTransport.kt:37`
- Modify: `src/android/app/src/test/java/org/citra/citra_emu/moonlight/RtpTimestampTest.kt:7`

**Interfaces:**
- Consumes: `EncodedFrame.presentationTimeUs: Long` supplied by MediaCodec.
- Produces: `RtpTimestampTracker.timestampFor(presentationTimeUs: Long): Int` and `reset()`.

- [ ] **Step 1: Replace the existing frame-index test with failing PTS tests**

Use this body for `RtpTimestampTest`:

```kotlin
package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.server.RtpTimestampTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class RtpTimestampTest {
    @Test
    fun normalizesThirtyFpsPresentationTimeToNinetyKhzClock() {
        val tracker = RtpTimestampTracker()

        assertEquals(0, tracker.timestampFor(5_000_000L))
        assertEquals(3_000, tracker.timestampFor(5_033_333L))
        assertEquals(6_000, tracker.timestampFor(5_066_667L))
    }

    @Test
    fun preservesSixtyFpsPresentationCadence() {
        val tracker = RtpTimestampTracker()

        assertEquals(0, tracker.timestampFor(1_000_000L))
        assertEquals(1_500, tracker.timestampFor(1_016_667L))
        assertEquals(3_000, tracker.timestampFor(1_033_333L))
    }

    @Test
    fun keepsTimestampsMonotonicForDuplicateOrRegressingPts() {
        val tracker = RtpTimestampTracker()

        assertEquals(0, tracker.timestampFor(2_000_000L))
        assertEquals(1, tracker.timestampFor(2_000_000L))
        assertEquals(2, tracker.timestampFor(1_999_000L))
    }

    @Test
    fun resetStartsANewTimestampTimeline() {
        val tracker = RtpTimestampTracker()
        tracker.timestampFor(3_000_000L)
        tracker.timestampFor(3_033_333L)

        tracker.reset()

        assertEquals(0, tracker.timestampFor(8_000_000L))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat -PazaharAbiFilters=arm64-v8a testVanillaDebugUnitTest --tests org.citra.citra_emu.moonlight.RtpTimestampTest --console=plain
```

Expected: compilation fails because `RtpTimestampTracker` does not exist.

- [ ] **Step 3: Implement the PTS timestamp tracker**

Replace the stateless `RtpTimestamp` object in `VideoRtpTransport.kt` with:

```kotlin
internal class RtpTimestampTracker {
    private var basePresentationTimeUs: Long? = null
    private var lastTimestamp: Long = -1L

    fun timestampFor(presentationTimeUs: Long): Int {
        val base = basePresentationTimeUs ?: presentationTimeUs.also {
            basePresentationTimeUs = it
        }
        val elapsedUs = (presentationTimeUs - base).coerceAtLeast(0L)
        val ptsTimestamp = (elapsedUs * RTP_CLOCK_RATE + HALF_MICROSECOND) / MICROSECONDS_PER_SECOND
        val nextTimestamp = ptsTimestamp.coerceAtLeast(lastTimestamp + 1L)
        lastTimestamp = nextTimestamp
        return nextTimestamp.toInt()
    }

    fun reset() {
        basePresentationTimeUs = null
        lastTimestamp = -1L
    }

    private companion object {
        const val RTP_CLOCK_RATE = 90_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val HALF_MICROSECOND = MICROSECONDS_PER_SECOND / 2L
    }
}
```

Add one transport field:

```kotlin
private val timestampTracker = RtpTimestampTracker()
```

Call `timestampTracker.reset()` from both `start()` and `stop()`. In
`sendFrameNow()`, replace the frame-index-based timestamp expression with:

```kotlin
val timestamp = timestampTracker.timestampFor(frame.presentationTimeUs)
```

Keep `frameIndex` for the NVIDIA frame header. Keep `setFrameRate()` and its log
as the negotiated-rate diagnostic, but stop using `frameRate` for RTP timing.

- [ ] **Step 4: Run the targeted test and verify GREEN**

Run the Step 2 command again.

Expected: `BUILD SUCCESSFUL` and all four timestamp tests pass.

- [ ] **Step 5: Commit the independently testable RTP change**

```powershell
git add -- 'src/android/app/src/main/java/org/citra/citra_emu/moonlight/server/VideoRtpTransport.kt' 'src/android/app/src/test/java/org/citra/citra_emu/moonlight/RtpTimestampTest.kt'
git commit -m "Align Moonlight RTP timing with encoded frames"
```

### Task 3: Regression, Release, And Real Cadence Validation

**Files:**
- Inspect only: `artifacts/` and `logs/` for local evidence.
- No additional production files unless real-device evidence identifies a vendor issue.

**Interfaces:**
- Consumes: the encoder limiter and PTS timestamp tracker from Tasks 1 and 2.
- Produces: passing Android tests, an installable release, and measured 30/60 FPS evidence.

- [ ] **Step 1: Run all Android JVM tests**

```powershell
.\gradlew.bat -PazaharAbiFilters=arm64-v8a testVanillaDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: Build and install the ARM64 release on Odin**

```powershell
.\gradlew.bat -PazaharAbiFilters=arm64-v8a installVanillaRelease --console=plain
```

Expected: `BUILD SUCCESSFUL` and installation on the connected Odin device.

- [ ] **Step 3: Validate a 30 FPS real-client session**

Start Moonlight/Artemis with 1080p, 30 FPS, and a supported codec. Capture logcat
for at least 10 seconds after `Sent video frame 1`. Compare timestamps around two
frame-counter log entries. A 300-frame interval must take 9.0 to 11.0 seconds,
the sender queue must not grow, and the client overlay must report about 30 FPS.

- [ ] **Step 4: Validate a 60 FPS real-client session**

Repeat with 1080p, 60 FPS. A 300-frame interval must take 4.5 to 5.5 seconds,
the sender queue must not grow, and the client overlay must report about 60 FPS.

- [ ] **Step 5: Review and publish**

```powershell
git diff --check
git status -sb
git log -5 --oneline
git push origin azahar-dual-device
```

Expected: only `artifacts/` and `logs/` remain untracked, the implementation
commits are present, and the push updates `origin/azahar-dual-device`.
