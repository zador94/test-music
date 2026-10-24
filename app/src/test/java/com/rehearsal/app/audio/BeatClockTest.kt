package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatClockTest {
    @Test
    fun intervalIsSixtySecondsOverOriginalBpm() {
        assertEquals(500_000L, BeatClock.intervalUs(120f))
        assertEquals(1_000_000L, BeatClock.intervalUs(60f))
        assertEquals(333_333L, BeatClock.intervalUs(180f))
    }

    @Test
    fun beatsAreIntegerGridFromZero() {
        val interval = BeatClock.intervalUs(120f)
        assertEquals(0L, BeatClock.beatAtOrAfter(0L, interval))
        assertEquals(500_000L, BeatClock.beatAtOrAfter(1L, interval))
        assertEquals(500_000L, BeatClock.beatAtOrAfter(500_000L, interval))
        assertEquals(1_000_000L, BeatClock.beatAtOrAfter(500_001L, interval))
        assertEquals(2_000_000L, BeatClock.beatAtOrAfter(1_500_001L, interval))
    }

    @Test
    fun timeStretchDoesNotMoveMediaBeatGrid() {
        val originalBpm = 120f
        val metronomeBpm = 180f
        val speed = com.rehearsal.app.player.PlaybackMath.playbackSpeed(metronomeBpm, originalBpm)
        assertEquals(1.5f, speed, 1e-5f)
        assertEquals(
            "клики сидят на originalBpm в media time, Sonic растягивает потом",
            500_000L,
            BeatClock.intervalUs(originalBpm),
        )
        assertEquals(333_333L, BeatClock.intervalUs(metronomeBpm))
    }
}
