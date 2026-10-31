package com.rehearsal.app.audio

import com.rehearsal.app.player.PlaybackMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class KickGridTest {
    @Test
    fun syntheticKicksAt1046RecoverBpmAndPhase() {
        val bpm = 104.6
        val phaseSec = 0.08
        val pcm = lowBandKicks(bpm = bpm, phaseSec = phaseSec, bars = 8, beatsPerBar = 4)
        val result = KickGrid.analyze(pcm)
        val hopSec = KickGrid.hopSec(pcm.sampleRate)
        assertEquals(
            "104.6 kicks must not round to integer 105, was ${result.bpm}",
            104.6,
            result.bpm.toDouble(),
            0.2,
        )
        val period = 60.0 / bpm
        val dist = wrapped(result.kickPhaseSec - phaseSec, period)
        assertTrue(
            "phase ${result.kickPhaseSec} vs $phaseSec, hop=$hopSec",
            dist <= hopSec + 0.002,
        )
        assertTrue("need kick onsets, was ${result.onsetsSec.size}", result.onsetsSec.size >= 16)
    }

    @Test
    fun slowAttackLocksToOnsetStartNotFluxPeak() {
        val bpm = 105.0
        val phaseSec = 0.08
        val pcm = KickOffsetDiagnosisTest.rampedKicks(bpm, phaseSec, 0.15)
        val result = KickGrid.analyze(pcm)
        val hopSec = KickGrid.hopSec(pcm.sampleRate)
        val period = 60.0 / bpm
        val dist = wrapped(result.kickPhaseSec - phaseSec, period)
        assertTrue(
            "150ms attack must not park phase on the body peak, phase=${result.kickPhaseSec} dist=$dist",
            dist <= hopSec + 0.012,
        )
    }

    @Test
    fun integerGridDriftsAgainstFractionalTempo() {
        val trueBpm = 104.6f
        val integerBpm = 105f
        val trueInterval = BeatClock.intervalUs(trueBpm)
        val intInterval = BeatClock.intervalUs(integerBpm)
        val beats = 40L
        val driftUs = abs(beats * trueInterval - beats * intInterval)
        val hopUs = (KickGrid.HOP * 1_000_000L) / 44100L
        assertTrue("integer 105 must drift vs 104.6, drift=$driftUs hop=$hopUs", driftUs > hopUs)
        assertTrue(trueInterval > intInterval)
        assertEquals(104.6f, PlaybackMath.committedOriginalBpm(104.63f), 0.01f)
        assertTrue(
            "toInt 105 would be the drifting grid",
            104.63f.toInt() == 104 || 104.63f.toInt() == 105,
        )
    }

    @Test
    fun jsonRoundtripKeepsKickPhaseAndTenthBpm() {
        val analysis = TrackAnalysis(
            bpm = 104.6f,
            beats = doubleArrayOf(0.08, 0.6536),
            downbeatOffset = 0.08,
            duration = 8.0,
            kickPhaseSec = 0.08,
            kickGridVersion = KickGrid.VERSION,
        )
        val parsed = TrackAnalysis.fromJson(analysis.toJson())!!
        assertEquals(104.6f, parsed.bpm, 1e-3f)
        assertEquals(0.08, parsed.kickPhaseSec, 1e-6)
        assertEquals(KickGrid.VERSION, parsed.kickGridVersion)
        assertTrue(!KickGrid.needsRefresh(parsed))
    }

    @Test
    fun missingKickGridVersionIsStale() {
        val json = """
            {
              "bpm": 105,
              "kickPhaseSec": 0.23,
              "beats": [0.23],
              "downbeatOffset": 0.23,
              "duration": 8,
              "sampleRate": 44100
            }
        """.trimIndent()
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(0, parsed.kickGridVersion)
        assertEquals(0.23, parsed.kickPhaseSec, 1e-6)
        assertTrue(KickGrid.needsRefresh(parsed))
        assertTrue(KickGrid.needsRefresh(null))
        val stamped = parsed.copy(kickGridVersion = KickGrid.VERSION)
        assertTrue(!KickGrid.needsRefresh(stamped))
    }

    @Test
    fun refineStampsCurrentKickGridVersion() {
        val pcm = lowBandKicks(bpm = 104.6, phaseSec = 0.08, bars = 4, beatsPerBar = 4)
        val period = 60.0 / 104.6
        val stale = TrackAnalysis(
            bpm = 120f,
            beats = DoubleArray(0),
            downbeatOffset = 0.0,
            duration = pcm.samples.toDouble() / pcm.sampleRate,
            sampleRate = pcm.sampleRate,
            kickPhaseSec = 0.4,
            kickGridVersion = 0,
            periodSec = period,
            phaseSec = 0.4,
        )
        val refined = KickGrid.refine(stale, pcm)
        assertEquals(KickGrid.VERSION, refined.kickGridVersion)
        assertTrue(!KickGrid.needsRefresh(refined))
        assertEquals("drums must not pick BPM", 120f, refined.bpm, 1e-3f)
        assertEquals(period, refined.periodSec, 1e-12)
        assertTrue(
            "refine must rewrite late phase, was ${refined.kickPhaseSec}",
            kotlin.math.abs(refined.kickPhaseSec - 0.08) < 0.03,
        )
        assertEquals(refined.phaseSec, refined.kickPhaseSec, 1e-12)
    }

    companion object {
        fun lowBandKicks(
            bpm: Double,
            phaseSec: Double,
            bars: Int,
            beatsPerBar: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val beats = bars * beatsPerBar
            val period = 60.0 / bpm
            val durationSec = phaseSec + beats * period + 0.2
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (sampleRate * 0.04).toInt().coerceAtLeast(64)
            val tau = 0.012 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx < 0 || idx >= samples) continue
                    val env = exp(-i / tau)
                    val s = (env * 0.95 * sin(2.0 * PI * 70.0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s * 0.96f
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
