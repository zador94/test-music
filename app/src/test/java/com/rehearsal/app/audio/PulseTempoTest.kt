package com.rehearsal.app.audio

import com.rehearsal.app.player.PlaybackMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Ровный клик уезжает, если цифра темпа на десятую быстрее пульса.
 * Сотая, снятая с бочки, за полторы минуты остаётся на ударе.
 */
class PulseTempoTest {
    @Test
    fun hintATenthHighSettlesOnTheKick() {
        val trueBpm = 104.96
        val pcm = KickGridTest.lowBandKicks(
            bpm = trueBpm,
            phaseSec = 0.115,
            bars = 32,
            beatsPerBar = 4,
        )
        val found = PulseTempo.nearest(pcm, 105.2f)
        assertEquals(104.96f, found, 0.02f)
        assertEquals(found, PlaybackMath.committedOriginalBpm(found), 0.0f)
        val interval = BeatClock.intervalUs(found)
        var worst = 0L
        repeat(90) { n ->
            val kickUs = ((0.115 + n * 60.0 / trueBpm) * 1_000_000.0).roundToLong()
            val clickUs = 115_000L + n.toLong() * interval
            worst = max(worst, abs(clickUs - kickUs))
        }
        assertTrue("click walked off the kick, worst=${worst}us bpm=$found", worst < 20_000L)
    }

    @Test
    fun steadyKicksAt105StayOnTheBeatForTwoMinutes() {
        val trueBpm = 105.0
        val pcm = KickGridTest.lowBandKicks(
            bpm = trueBpm,
            phaseSec = 0.056,
            bars = 16,
            beatsPerBar = 4,
        )
        val found = PulseTempo.nearest(pcm, 105.2f)
        assertEquals(105.0f, found, 0.02f)
        val interval = BeatClock.intervalUs(found)
        var worst = 0L
        repeat(200) { n ->
            val kickUs = ((0.056 + n * 60.0 / trueBpm) * 1_000_000.0).roundToLong()
            val clickUs = 56_000L + n.toLong() * interval
            worst = max(worst, abs(clickUs - kickUs))
        }
        assertTrue("105.00 grid walked off, worst=${worst}us bpm=$found", worst < 15_000L)
    }

    @Test
    fun phaseSitsOnTheKickNotALaterHit() {
        val kickAt = 0.115
        val pcm = KickGridTest.lowBandKicks(
            bpm = 105.0,
            phaseSec = kickAt,
            bars = 16,
            beatsPerBar = 4,
        )
        val grid = requireNotNull(PulseTempo.grid(pcm, 105.2f))
        val period = 60.0 / grid.bpm
        var err = (grid.phaseSec - kickAt) % period
        if (err > period / 2.0) err -= period
        if (err < -period / 2.0) err += period
        assertTrue(
            "phase must be the kick, not +170ms, phase=${grid.phaseSec} err=${err * 1000}ms",
            abs(err) < 0.040,
        )
    }

    @Test
    fun phaseUsesTheClickBeforeTheKickBody() {
        val bodyAt = 0.30
        val lead = 0.170
        val pcm = kicksWithLeadingClick(bodyAt = bodyAt, leadSec = lead)
        val grid = requireNotNull(PulseTempo.grid(pcm, 105.2f))
        val period = 60.0 / grid.bpm
        val attack = bodyAt - lead
        var err = (grid.phaseSec - attack) % period
        if (err > period / 2.0) err -= period
        if (err < -period / 2.0) err += period
        assertTrue(
            "click should sit on the attack, phase=${grid.phaseSec} err=${err * 1000}ms",
            abs(err) < 0.040,
        )
        var bodyErr = (grid.phaseSec - bodyAt) % period
        if (bodyErr > period / 2.0) bodyErr -= period
        if (bodyErr < -period / 2.0) bodyErr += period
        assertTrue("phase still on the late body, err=${bodyErr * 1000}ms", abs(bodyErr) > 0.100)
    }

    private fun kicksWithLeadingClick(bodyAt: Double, leadSec: Double): StereoPcm {
        val pcm = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = bodyAt, bars = 16, beatsPerBar = 4)
        val sr = pcm.sampleRate
        val period = 60.0 / 105.0
        val clickLen = (0.004 * sr).toInt()
        val beats = 16 * 4
        for (n in 0 until beats) {
            val at = ((bodyAt + n * period - leadSec) * sr).toInt()
            for (i in 0 until clickLen) {
                val idx = at + i
                if (idx < 0 || idx >= pcm.samples) continue
                val s = (kotlin.math.sin(2.0 * kotlin.math.PI * 3500.0 * i / sr) * 0.9).toFloat()
                pcm.left[idx] = s
                pcm.right[idx] = s
            }
        }
        return pcm
    }

    @Test
    fun farHintDoesNotJumpToAnotherGroove() {
        val pcm = KickGridTest.lowBandKicks(bpm = 104.6, phaseSec = 0.08, bars = 16, beatsPerBar = 4)
        assertEquals(120f, PulseTempo.nearest(pcm, 120f), 0.02f)
    }
}
