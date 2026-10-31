package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Phone 0.3.26 «На Титанике»: LF onset = peak at +52 ms, slider still −170.
 * Quarters log repeated the same start/peak (wrappedDistance). Look for a
 * short HF burst 80–220 ms before each strong low onset.
 */
class KickGridEarlyClickTest {
    @Test
    fun quartersLogUsesAbsoluteOnsetNotWrappedPhase() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val hopSec = KickGrid.hopSec(44100)
        val infos = (0 until 12).map { n ->
            KickGrid.OnsetInfo(
                timeSec = 0.052 + n * period,
                lowEnergy = 1f,
                sharpness = 1f,
                attackHops = 1,
                midSustain = 0f,
                kickWeight = 1f,
            )
        }
        val snaps = KickGrid.quarterSnapshots(0.0497, period, infos, hopSec, count = 8)
        val starts = snaps.map { it.startSec }
        assertEquals("each quarter must have its own onset, was $starts", 8, starts.distinct().size)
        assertTrue("q0 start ${starts[0]} want ~52ms", abs(starts[0] - 0.052) < 0.01)
        assertTrue(
            "q1 start ${starts[1]} want ~${0.052 + period}, not the same as q0",
            abs(starts[1] - (0.052 + period)) < 0.01,
        )
        val formatted = KickGrid.formatQuarters(snaps)
        assertTrue("log must mention q0 and q7, was $formatted", formatted.contains("q0 ") && formatted.contains("q7 "))
        val q0Start = Regex("q0 t=[^;]*start=([+-]?\\d+)ms").find(formatted)!!.groupValues[1].toInt()
        val q1Start = Regex("q1 t=[^;]*start=([+-]?\\d+)ms").find(formatted)!!.groupValues[1].toInt()
        assertTrue("q0 start ${q0Start}ms vs q1 ${q1Start}ms must differ", abs(q0Start - q1Start) > 400)
    }

    @Test
    fun hfClick170msBeforeLfBodyLocksPhaseToClick() {
        val bpm = 105.0
        val clickSec = 0.08
        val bodyDelay = 0.170
        val pcm = lfBodyWithEarlyClick(bpm = bpm, clickSec = clickSec, bodyDelaySec = bodyDelay, bars = 8)
        val result = KickGrid.analyze(pcm)
        val period = 60.0 / bpm
        val hop = KickGrid.hopSec(pcm.sampleRate)
        val errClick = wrapped(result.kickPhaseSec - clickSec, period)
        val errBody = wrapped(result.kickPhaseSec - (clickSec + bodyDelay), period)
        println(
            "earlyClick 170ms-before-body used=${result.earlyClickUsed} " +
                "lead=${"%.1f".format(result.earlyClickLeadSec * 1000.0)}ms " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined} " +
                "phase=${result.kickPhaseSec} errClick=${"%.1f".format(errClick * 1000)}ms " +
                "errBody=${"%.1f".format(errBody * 1000)}ms",
        )
        assertTrue(
            "precursor must be used when a click sits ~170ms before the LF body, used=${result.earlyClickUsed} " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined}",
            result.earlyClickUsed,
        )
        assertTrue(
            "median lead ${result.earlyClickLeadSec * 1000.0}ms want ~170ms before LF onset",
            abs(result.earlyClickLeadSec - bodyDelay) <= hop + 0.020,
        )
        assertTrue(
            "phase must sit on the early click, not the LF body. phase=${result.kickPhaseSec} " +
                "errClick=${errClick * 1000}ms errBody=${errBody * 1000}ms",
            errClick <= hop + 0.020 && errClick < errBody,
        )
        assertEquals(12, KickGrid.VERSION)
    }

    @Test
    fun syntheticLowBandKickHasNoEarlyClick() {
        val pcm = KickGridTest.lowBandKicks(bpm = 104.6, phaseSec = 0.08, bars = 8, beatsPerBar = 4)
        val result = KickGrid.analyze(pcm)
        println(
            "earlyClick synthetic used=${result.earlyClickUsed} lead=${result.earlyClickLeadSec * 1000.0}ms " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined} phase=${result.kickPhaseSec}",
        )
        assertFalse(
            "70 Hz kick has no HF burst 80–220ms earlier, used=${result.earlyClickUsed} " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined}",
            result.earlyClickUsed,
        )
        val hop = KickGrid.hopSec(pcm.sampleRate)
        val dist = wrapped(result.kickPhaseSec - 0.08, 60.0 / 104.6)
        assertTrue("phase stays on the LF onset, dist=$dist", dist <= hop + 0.012)
    }

    @Test
    fun realisticGlissandoHasNoEarlyClick() {
        val bpm = 104.6
        val phaseSec = 0.08
        val pcm = KickGridTimebaseTest.realisticGlissandoKicks(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val result = KickGrid.analyze(pcm)
        println(
            "earlyClick glissando used=${result.earlyClickUsed} lead=${result.earlyClickLeadSec * 1000.0}ms " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined} phase=${result.kickPhaseSec}",
        )
        assertFalse(
            "beater sits on the body, not 80–220ms earlier, used=${result.earlyClickUsed} " +
                "hits=${result.earlyClickHits}/${result.earlyClickExamined}",
            result.earlyClickUsed,
        )
        val err = wrapped(result.kickPhaseSec - phaseSec, 60.0 / bpm)
        assertTrue("glissando phase stays on the hit, err=${err * 1000}ms", err <= 0.015)
    }

    @Test
    fun sixteenthHatsDoNotStealPhase() {
        val bpm = 105.0
        val phaseSec = 0.08
        val pcm = kicksWithSixteenthHats(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val result = KickGrid.analyze(pcm)
        val period = 60.0 / bpm
        val sixteenth = period / 4.0
        val hop = KickGrid.hopSec(pcm.sampleRate)
        val errQ = wrapped(result.kickPhaseSec - phaseSec, period)
        val err16 = wrapped(result.kickPhaseSec - (phaseSec - sixteenth), period)
        println(
            "earlyClick 16th-hats used=${result.earlyClickUsed} lead=${result.earlyClickLeadSec * 1000.0}ms " +
                "phase=${result.kickPhaseSec} errQ=${errQ * 1000}ms err16=${err16 * 1000}ms",
        )
        assertFalse(
            "hat on the previous 16th must not become the grid, used=${result.earlyClickUsed} " +
                "lead=${result.earlyClickLeadSec * 1000.0}ms",
            result.earlyClickUsed,
        )
        assertTrue(
            "phase stays on the kick, not −1 16th. errQ=${errQ * 1000}ms err16=${err16 * 1000}ms",
            errQ <= hop + 0.015 && errQ < err16,
        )
    }

    @Test
    fun versionThreeIsStaleAfterEarlyClick() {
        val stale = TrackAnalysis(
            bpm = 105f,
            beats = DoubleArray(0),
            downbeatOffset = 0.05,
            duration = 8.0,
            kickPhaseSec = 0.05,
            kickGridVersion = 3,
        )
        assertTrue(KickGrid.needsRefresh(stale))
        assertEquals(12, KickGrid.VERSION)
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 4)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 5)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 6)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 7)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 8)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 9)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 10)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 11)))
        assertTrue(!KickGrid.needsRefresh(stale.copy(kickGridVersion = 12)))
    }

    companion object {
        fun lfBodyWithEarlyClick(
            bpm: Double,
            clickSec: Double,
            bodyDelaySec: Double,
            bars: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = clickSec + bodyDelaySec + beats * period + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (0.004 * sampleRate).toInt().coerceAtLeast(8)
            val clickTau = 0.0010 * sampleRate
            val bodyLen = (0.05 * sampleRate).toInt()
            val bodyTau = 0.014 * sampleRate
            for (b in 0 until beats) {
                val clickAt = ((clickSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = clickAt + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / clickTau)
                    val s = (env * 0.70 * sin(2.0 * PI * 3200.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
                val bodyAt = ((clickSec + bodyDelaySec + b * period) * sampleRate).toInt()
                for (i in 0 until bodyLen) {
                    val idx = bodyAt + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / bodyTau)
                    val s = (env * 0.95 * sin(2.0 * PI * 70.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun kicksWithSixteenthHats(
            bpm: Double,
            phaseSec: Double,
            bars: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val sixteenth = period / 4.0
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val kickLen = (0.04 * sampleRate).toInt()
            val kickTau = 0.012 * sampleRate
            val hatLen = (0.018 * sampleRate).toInt()
            val hatTau = 0.006 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until kickLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / kickTau)
                    val s = (env * 0.95 * sin(2.0 * PI * 70.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
                for (s16 in 0..3) {
                    val hatAt = ((phaseSec + b * period + s16 * sixteenth) * sampleRate).toInt()
                    for (i in 0 until hatLen) {
                        val idx = hatAt + i
                        if (idx !in 0 until samples) continue
                        val env = exp(-i / hatTau)
                        val s = (env * 0.45 * sin(2.0 * PI * 9000.0 * i / sampleRate)).toFloat()
                        left[idx] += s
                        right[idx] += s * 0.7f
                    }
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        private fun wrapped(delta: Double, period: Double): Double {
            val p = period.coerceAtLeast(1e-9)
            var m = delta % p
            if (m < 0.0) m += p
            return min(m, p - m)
        }
    }
}
