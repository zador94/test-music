package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Фаза бочки в готовом periodSec микса. Ударные не выбирают BPM и не складывают 150→75.
 */
class KickGridPhaseLockTest {
    @Test
    fun phaseInLockedPeriodHitsKickWithoutPickingBpm() {
        val bpm = 104.6
        val period = 60.0 / bpm
        val kickAt = 0.08
        val drums = KickGridTest.lowBandKicks(bpm = bpm, phaseSec = kickAt, bars = 8, beatsPerBar = 4)
        val mix = TrackAnalysis(
            bpm = 153.2f,
            beats = DoubleArray(0),
            downbeatOffset = 0.0,
            duration = drums.samples.toDouble() / drums.sampleRate,
            sampleRate = drums.sampleRate,
            kickPhaseSec = 0.0,
            periodSec = period,
            phaseSec = 0.0,
        )
        val aligned = KickGrid.alignPhase(mix, drums)
        assertEquals("drums must not rewrite mix BPM", 153.2f, aligned.bpm, 1e-3f)
        assertEquals(period, aligned.periodSec, 1e-12)
        assertEquals(
            "empty beats[]: align must not rewrite the grid with one drums phaseSec",
            0.0,
            aligned.phaseSec,
            0.0,
        )
        assertEquals(0, aligned.beats.size)
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
        val json = aligned.toJson()
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(153.2f, parsed.bpm, 1e-3f)
        assertEquals(period, parsed.periodSec, 1e-12)
        assertEquals(aligned.phaseSec, parsed.phaseSec, 1e-12)
    }

    @Test
    fun oneFiftyPeriodDoesNotFoldToSeventyFive() {
        val period = 60.0 / 153.2
        val drums = TempoDetectorTest.stereoClicks(bpm = 76.6, bars = 8, beatsPerBar = 4)
        val mix = TrackAnalysis(
            bpm = 153.2f,
            beats = DoubleArray(0),
            downbeatOffset = 0.0,
            duration = drums.samples.toDouble() / drums.sampleRate,
            sampleRate = drums.sampleRate,
            kickPhaseSec = 0.3,
            periodSec = period,
            phaseSec = 0.3,
        )
        val aligned = KickGrid.alignPhase(mix, drums)
        assertEquals(153.2f, aligned.bpm, 1e-3f)
        assertEquals(period, aligned.periodSec, 1e-12)
        assertEquals(
            BeatClock.intervalFromPeriodSec(period),
            BeatClock.intervalFromPeriodSec(aligned.periodSec),
        )
        assertTrue(
            "must not become 75-fold period",
            abs(aligned.periodSec - 60.0 / 76.6) > 0.05,
        )
    }

    private fun wrapped(delta: Double, period: Double): Double {
        val p = period.coerceAtLeast(1e-9)
        var m = delta % p
        if (m < 0.0) m += p
        return if (m <= p - m) m else m - p
    }
}
