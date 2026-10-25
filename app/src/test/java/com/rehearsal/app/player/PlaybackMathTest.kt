package com.rehearsal.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMathTest {
    @Test
    fun speedIsMetronomeOverOriginal() {
        assertEquals(1.0f, PlaybackMath.playbackSpeed(120f, 120f), 1e-5f)
        assertEquals(0.5f, PlaybackMath.playbackSpeed(60f, 120f), 1e-5f)
        assertEquals(1.5f, PlaybackMath.playbackSpeed(180f, 120f), 1e-5f)
        assertEquals(2.0f, PlaybackMath.playbackSpeed(240f, 120f), 1e-5f)
    }

    @Test
    fun speedClampsToHalfAndDouble() {
        assertEquals(0.5f, PlaybackMath.playbackSpeed(30f, 120f), 1e-5f)
        assertEquals(2.0f, PlaybackMath.playbackSpeed(400f, 120f), 1e-5f)
    }

    @Test
    fun committedOriginalBpmMatchesDisplayedInteger() {
        assertEquals(102f, PlaybackMath.committedOriginalBpm(102.8f), 0f)
        assertEquals(102, PlaybackMath.displayedBpm(102.8f))
        assertEquals(1f, PlaybackMath.playbackSpeed(102f, PlaybackMath.committedOriginalBpm(102.8f)), 1e-5f)
        assertEquals(102f, PlaybackMath.clampMetronomeBpm(102.8f, 102f), 0f)
    }

    @Test
    fun zeroOriginalBpmFallsBackToOne() {
        assertEquals(1.0f, PlaybackMath.playbackSpeed(120f, 0f), 1e-5f)
        assertEquals(1.0f, PlaybackMath.playbackSpeed(120f, -10f), 1e-5f)
        assertEquals(1.0f, PlaybackMath.playbackSpeed(120f, Float.NaN), 1e-5f)
        assertEquals(1.0f, PlaybackMath.playbackSpeed(Float.POSITIVE_INFINITY, 120f), 1e-5f)
    }

    @Test
    fun metronomeRangeFollowsOriginal() {
        assertEquals(60f, PlaybackMath.minMetronomeBpm(120f), 1e-4f)
        assertEquals(240f, PlaybackMath.maxMetronomeBpm(120f), 1e-4f)
        assertEquals(30f, PlaybackMath.minMetronomeBpm(40f), 1e-4f)
        assertEquals(240f, PlaybackMath.maxMetronomeBpm(200f), 1e-4f)
    }
}
