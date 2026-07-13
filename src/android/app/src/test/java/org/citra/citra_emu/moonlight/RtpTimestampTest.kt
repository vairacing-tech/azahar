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
