package com.hawky.nascraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimestampCodecTest {
    @Test
    fun parsesUtcFractionsAndExplicitOffsets() {
        assertEquals(1_796_083_200L, TimestampCodec.parseEpochSeconds("2026-12-01T00:00:00Z"))
        assertEquals(1_796_083_200L, TimestampCodec.parseEpochSeconds("2026-11-30T18:00:00-06:00"))
        assertEquals(1_796_083_200L, TimestampCodec.parseEpochSeconds("2026-12-01T08:00:00.250+08:00"))
        assertEquals(1_796_083_200L, TimestampCodec.parseEpochSeconds("1796083200"))
    }

    @Test
    fun rejectsMalformedDatesAndOffsets() {
        assertThrows(IllegalArgumentException::class.java) {
            TimestampCodec.parseEpochSeconds("2026-02-30T00:00:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimestampCodec.parseEpochSeconds("2026-12-01T00:00:00+25:00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimestampCodec.parseEpochSeconds("2026-12-01T00:00:00")
        }
    }
}
