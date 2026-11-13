package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatClockTest {
    @Test
    fun intervalIsSixtySecondsOverOriginalBpm() {
        assertEquals(500_000L, BeatClock.intervalUs(120f))
        assertEquals(1_000_000L, BeatClock.intervalUs(60f))
        assertEquals(333_333L, BeatClock.intervalUs(180f))
        assertEquals(500_000L, BeatClock.intervalFromPeriodSec(0.5))
        val exact = 60.0 / 104.64
        assertEquals(
            kotlin.math.round(exact * 1_000_000.0).toLong(),
            BeatClock.intervalFromPeriodSec(exact),
        )
        assertTrue(BeatClock.intervalFromPeriodSec(exact) != BeatClock.intervalUs(104.6f))
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
    fun gridOriginUsesKickPhaseNotBlindPad() {
        val interval = BeatClock.intervalUs(120f)
        val origin = BeatClock.gridOriginUs(80_000L, 0L, interval)
        assertEquals(80_000L, origin)
        assertEquals(80_000L, BeatClock.beatAtOrAfter(0L, interval, origin))
        assertEquals(80_000L + interval, BeatClock.beatAtOrAfter(origin + 1L, interval, origin))
    }

    @Test
    fun gridHonorsDownbeatOrigin() {
        val interval = BeatClock.intervalUs(120f)
        val origin = 230_000L
        assertEquals(origin, BeatClock.beatAtOrAfter(0L, interval, origin))
        assertEquals(origin, BeatClock.beatAtOrAfter(origin, interval, origin))
        assertEquals(origin + interval, BeatClock.beatAtOrAfter(origin + 1L, interval, origin))
        assertEquals(
            origin,
            BeatClock.firstOverlappingBeatUs(0L, interval, 6_000L, origin),
        )
    }

    @Test
    fun sampleToMediaUsRoundTripsWithMediaUsToSampleAt44100() {
        val sr = 44100
        for (chunk in 0..40) {
            val start = chunk * 1024
            val pts = BeatClock.sampleToMediaUs(start.toLong(), sr)
            assertEquals(
                "chunk start $start pts=$pts",
                start.toLong(),
                BeatClock.mediaUsToSample(pts, sr),
            )
        }
        val kick = (0.08 * sr).toInt()
        val kickUs = TempoMath.secondsToMediaUs(0.08)
        assertEquals(kick.toLong(), BeatClock.mediaUsToSample(kickUs, sr))
        val pts = BeatClock.sampleToMediaUs(3L * 1024, sr)
        val musicLocal = kick - BeatClock.mediaUsToSample(pts, sr).toInt()
        val clickLocal = BeatClock.frameOffset(kickUs, pts, sr)
        assertEquals("live-remix music vs click in chunk 3", musicLocal, clickLocal)
    }

    @Test
    fun beatTimesUsConvertsSecondsNotLeavingThemAsMicros() {
        val us = BeatClock.beatTimesUs(doubleArrayOf(0.0, 0.5, 1.0, 1.5))
        assertEquals(4, us.size)
        assertEquals(0L, us[0])
        assertEquals(500_000L, us[1])
        assertEquals(1_000_000L, us[2])
        val integerSeconds = BeatClock.beatTimesUs(doubleArrayOf(0.0, 1.0, 2.0, 3.0))
        assertEquals(1_000_000L, integerSeconds[1])
        assertEquals(2_000_000L, integerSeconds[2])
        val alreadyUs = BeatClock.beatTimesUs(doubleArrayOf(0.0, 500_000.0, 1_000_000.0, 1_500_000.0))
        assertEquals(500_000L, alreadyUs[1])
        assertEquals(1_000_000L, alreadyUs[2])
        val truncatedUs = BeatClock.beatTimesUs(doubleArrayOf(0.0, 1e-6, 2e-6, 3e-6))
        assertEquals(0L, truncatedUs[0])
        assertEquals(1L, truncatedUs[1])
    }
}
