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
        assertEquals(
            1_500_000L,
            BeatClock.firstOverlappingBeat(beats, 1_200_000L, 18_000L),
        )
        assertEquals(Long.MAX_VALUE, BeatClock.firstOverlappingBeat(beats, 2_000_000L, 1_000L))
    }

    @Test
    fun beatTimesUsConvertsSecondsNotLeavingThemAsMicros() {
        val us = BeatClock.beatTimesUs(doubleArrayOf(0.0, 0.5, 1.0, 1.5))
        assertEquals(4, us.size)
        assertEquals(0L, us[0])
        assertEquals(500_000L, us[1])
        assertEquals(1_000_000L, us[2])
        assertTrue(BeatClock.isPlausibleClickGrid(us, 500_000L))
        val secondsAsLong = longArrayOf(0L, 1L, 1L, 2L, 2L)
        assertTrue(
            "truncated seconds must not look like a µs click grid",
            !BeatClock.isPlausibleClickGrid(secondsAsLong, 500_000L),
        )
        assertEquals(Long.MAX_VALUE, BeatClock.firstOverlappingBeat(secondsAsLong, 1_200_000L, 6_000L))
        val integerSeconds = BeatClock.beatTimesUs(doubleArrayOf(0.0, 1.0, 2.0, 3.0))
        assertEquals(1_000_000L, integerSeconds[1])
        assertEquals(2_000_000L, integerSeconds[2])
        val alreadyUs = BeatClock.beatTimesUs(doubleArrayOf(0.0, 500_000.0, 1_000_000.0, 1_500_000.0))
        assertEquals(500_000L, alreadyUs[1])
        assertEquals(1_000_000L, alreadyUs[2])
        val truncatedUs = BeatClock.beatTimesUs(doubleArrayOf(0.0, 1e-6, 2e-6, 3e-6))
        assertEquals(0L, truncatedUs[0])
        assertEquals(1L, truncatedUs[1])
        assertTrue(!BeatClock.isPlausibleClickGrid(truncatedUs, 500_000L))
    }

    @Test
    fun straightenDropsAStuckEarlyPhase() {
        val period = 500_000L
        val raw = LongArray(16) { it * period }
        for (i in 5 until raw.size) raw[i] -= 150_000L
        val out = BeatClock.straightenQuarters(raw)
        val late = out[10]
        assertTrue(
            "click must not stay 150ms early after one short step, was $late",
            kotlin.math.abs(late - 10 * period) < 20_000L,
        )
    }

    @Test
    fun straightenKeepsSlowTempoDrift() {
        val beats = LongArray(32)
        var t = 0L
        var step = 500_000.0
        for (i in beats.indices) {
            beats[i] = t
            step += 2_000.0
            t += step.toLong()
        }
        val out = BeatClock.straightenQuarters(beats)
        val lastStep = out[out.lastIndex] - out[out.lastIndex - 1]
        val rawLast = beats[beats.lastIndex] - beats[beats.lastIndex - 1]
        assertTrue(
            "drifted step $rawLast must survive, got $lastStep",
            kotlin.math.abs(lastStep - rawLast) < 15_000L,
        )
    }

    @Test
    fun straightenLeavesEvenQuartersUntouched() {
        val period = 568_889L
        val raw = LongArray(24) { it * period }
        val out = BeatClock.straightenQuarters(raw)
        for (i in raw.indices) assertEquals(raw[i], out[i])
    }

    @Test
    fun extendClickBeatsKeepsBodyAndFillsTheEndsFromMeanStep() {
        val step = 588_235L
        val first = 12_724_535L
        val inside = LongArray(5) { i -> first + i * step }
        val durationUs = 20_000_000L
        val extended = BeatClock.extendClickBeats(inside, durationUs)
        assertTrue(extended.size > inside.size)
        for (t in inside) {
            assertTrue("body beat $t dropped", extended.any { kotlin.math.abs(it - t) <= 1L })
        }
        val intro = extended.first()
        assertTrue("intro should continue the 588ms step, was $intro", kotlin.math.abs(intro - (first % step)) < 2_000L)
        assertTrue(extended.last() <= durationUs)
        assertTrue(durationUs - extended.last() < step)
    }

    @Test
    fun extendClickBeatsIgnoresMarksOutsideTheSong() {
        val hours = longArrayOf(3_600_000_000L, 3_600_500_000L, 3_601_000_000L)
        val same = BeatClock.extendClickBeats(hours, 8_000_000L)
        assertEquals(hours.size, same.size)
        assertEquals(hours[0], same[0])
    }

    @Test
    fun hasBeatNearIsFalseWhenGridLivesInAnotherTimebase() {
        val songBeats = longArrayOf(0L, 500_000L, 1_000_000L)
        assertTrue(BeatClock.hasBeatNear(songBeats, 0L, 23_219L, 500_000L))
        assertTrue(BeatClock.hasBeatNear(songBeats, 488_000L, 511_219L, 500_000L))
        val hoursAway = longArrayOf(3_600_000_000L, 3_600_500_000L, 3_601_000_000L)
        assertTrue(BeatClock.isPlausibleClickGrid(hoursAway, 500_000L))
        assertTrue(
            "plausible µs grid that never meets stem PTS must not keep mixInto silent",
            !BeatClock.hasBeatNear(hoursAway, 0L, 23_219L, 500_000L),
        )
    }
}
