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
    fun clickLeadIsCorrectedLaterNotEarlier() {
        val interval = BeatClock.intervalUs(120f)
        assertEquals(500_000L, interval)
        val origin = 0L
        val click = 0L
        val kick = 80_000L
        val corrected = BeatClock.alignOriginLater(origin, click, kick, interval)
        assertEquals("gallop click-then-kick must delay origin to the kick", kick, corrected)
        assertTrue("must move later", corrected > origin)
        val earlierWrong = interval - kick
        assertTrue(
            "must not wrap earlier (would be $earlierWrong)",
            kotlin.math.abs(corrected - kick) < kotlin.math.abs(corrected - earlierWrong),
        )
        assertEquals(
            "already on/after the kick must not move earlier",
            80_000L,
            BeatClock.alignOriginLater(80_000L, 80_000L, 80_000L, interval),
        )
        assertEquals(
            "late-sounding click must not pull origin earlier (worsens gallop)",
            80_000L,
            BeatClock.alignOriginLater(80_000L, clickUs = 90_000L, kickUs = 80_000L, interval),
        )
    }

    @Test
    fun gridOriginUsesKickPhaseNotBlindPad() {
        val interval = BeatClock.intervalUs(120f)
        val origin = BeatClock.gridOriginUs(80_000L, 0L, interval)
        assertEquals(80_000L, origin)
        assertEquals(80_000L, BeatClock.beatAtOrAfter(0L, interval, origin))
        assertEquals(80_000L + interval, BeatClock.beatAtOrAfter(origin + 1L, interval, origin))
        assertEquals(0L, BeatClock.kickPhaseUs(longArrayOf(), interval))
        val mean = BeatClock.kickPhaseUs(longArrayOf(80_000L, 580_000L, 1_080_000L), interval)
        assertEquals(80_000L, mean)
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
