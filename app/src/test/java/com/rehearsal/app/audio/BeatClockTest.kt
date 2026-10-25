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

    @Test
    fun firstIndexAtOrAfterIsBinarySearchOnBeats() {
        val beats = longArrayOf(0L, 400_000L, 900_000L, 1_500_000L)
        assertEquals(0, BeatClock.firstIndexAtOrAfter(beats, 0L))
        assertEquals(1, BeatClock.firstIndexAtOrAfter(beats, 1L))
        assertEquals(1, BeatClock.firstIndexAtOrAfter(beats, 400_000L))
        assertEquals(2, BeatClock.firstIndexAtOrAfter(beats, 400_001L))
        assertEquals(4, BeatClock.firstIndexAtOrAfter(beats, 1_500_001L))
        assertEquals(
            3,
            BeatClock.firstOverlappingIndex(beats, 1_200_000L, 18_000L),
        )
    }
}
