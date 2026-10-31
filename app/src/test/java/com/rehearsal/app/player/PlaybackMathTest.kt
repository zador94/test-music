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
    fun committedOriginalBpmMatchesDisplayedTenth() {
        assertEquals(104.6f, PlaybackMath.committedOriginalBpm(104.63f), 0.01f)
        assertEquals("104.6", PlaybackMath.formatOriginalBpm(104.63f))
        assertEquals(102.8f, PlaybackMath.committedOriginalBpm(102.84f), 0.01f)
        assertEquals(1f, PlaybackMath.playbackSpeed(104.6f, PlaybackMath.committedOriginalBpm(104.63f)), 1e-3f)
        assertEquals(104.6f, PlaybackMath.clampMetronomeBpm(104.6f, 104.6f), 0.01f)
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
