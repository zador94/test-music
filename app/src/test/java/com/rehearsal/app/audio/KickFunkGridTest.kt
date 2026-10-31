package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Device 0.3.22: Drunk_n_Funk still needs −160 ms ≈ one 16th + 17 ms.
 * Hypothesis: phase locked to a syncopated kick / snare body / bass bleed,
 * not the downbeat. Funk groove: kicks on 1, a-of-2, 3; snare on 2 and 4.
 */
class KickFunkGridTest {
    @Test
    fun funkGrooveLocksToDownbeatNotSixteenth() {
        val bpm = 104.6
        val phaseSec = 0.08
        val pcm = funkGroove(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val result = KickGrid.analyze(pcm)
        val strong = KickGrid.strongKickOnsets(result.onsetInfos)
        val period = 60.0 / bpm
        val sixteenth = period / 4.0
        val hop = KickGrid.hopSec(pcm.sampleRate)
        val errDownbeat = wrapped(result.kickPhaseSec - phaseSec, period)
        val errSixteenth = wrapped(result.kickPhaseSec - (phaseSec + sixteenth), period)
        assertEquals(
            "funk BPM drifted, was ${result.bpm} phase=${result.kickPhaseSec} " +
                "top=${result.phaseCandidates.take(5)} strong=${strong.size}",
            104.6,
            result.bpm.toDouble(),
            0.3,
        )
        assertTrue(
            "funk phase must sit on the downbeat kick, not +1 sixteenth " +
                "(${sixteenth * 1000.0}ms). phase=${result.kickPhaseSec} " +
                "errQ=${errDownbeat * 1000.0}ms err16=${errSixteenth * 1000.0}ms " +
                "top=${result.phaseCandidates.take(5)}",
            errDownbeat <= hop + 0.015,
        )
        assertTrue(
            "downbeat must beat the +16th candidate, errQ=${errDownbeat * 1000.0}ms " +
                "err16=${errSixteenth * 1000.0}ms",
            errDownbeat < errSixteenth,
        )
    }

    @Test
    fun sixteenthLatePhaseScoresLowerThanDownbeat() {
        val bpm = 104.6
        val phaseSec = 0.08
        val pcm = funkGroove(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val result = KickGrid.analyze(pcm)
        val period = 60.0 / bpm
        val sixteenth = period / 4.0
        val hop = KickGrid.hopSec(pcm.sampleRate)
        val strong = KickGrid.strongKickOnsets(result.onsetInfos)
        assertTrue("need strong kicks, was ${strong.size}", strong.size >= 8)
        val downbeat = KickGrid.scoreQuarterPhase(strong, bpm, phaseSec, hop)
        val late16 = KickGrid.scoreQuarterPhase(strong, bpm, phaseSec + sixteenth, hop)
        val syncopa = KickGrid.scoreQuarterPhase(strong, bpm, phaseSec + 3 * sixteenth, hop)
        assertTrue(
            "downbeat score=$downbeat must beat +16th=$late16 and a-of-2=$syncopa",
            downbeat > late16 && downbeat > syncopa,
        )
        val top = result.phaseCandidates.first()
        val topErr = wrapped(top.phaseSec - phaseSec, period)
        assertTrue(
            "top comb candidate must be the downbeat, was ${top.phaseSec} score=${top.score}",
            topErr <= hop + 0.015,
        )
    }

    @Test
    fun versionOneIsStaleAfterSixteenthFix() {
        val stale = TrackAnalysis(
            bpm = 104.6f,
            beats = DoubleArray(0),
            downbeatOffset = 0.08,
            duration = 8.0,
            kickPhaseSec = 0.22,
            kickGridVersion = 1,
        )
        assertTrue(KickGrid.needsRefresh(stale))
        assertEquals(12, KickGrid.VERSION)
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 2)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 3)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 4)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 5)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 6)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 7)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 8)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 9)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 10)))
        assertTrue(KickGrid.needsRefresh(stale.copy(kickGridVersion = 11)))
        assertTrue(!KickGrid.needsRefresh(stale.copy(kickGridVersion = KickGrid.VERSION)))
    }

    companion object {
        fun funkGroove(
            bpm: Double,
            phaseSec: Double,
            bars: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val quarter = 60.0 / bpm
            val sixteenth = quarter / 4.0
            val durationSec = phaseSec + bars * 4 * quarter + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            for (bar in 0 until bars) {
                val t0 = phaseSec + bar * 4 * quarter
                // Kicks: 1, a of 2, 3
                addHit(left, right, t0, samples, sampleRate, amp = 0.95f, f0 = 55.0, attack = 0.003, decay = 0.12)
                addHit(left, right, t0 + 7 * sixteenth, samples, sampleRate, amp = 0.88f, f0 = 58.0, attack = 0.003, decay = 0.10)
                addHit(left, right, t0 + 8 * sixteenth, samples, sampleRate, amp = 0.92f, f0 = 55.0, attack = 0.003, decay = 0.12)
                // Ghost on e of 3
                addHit(left, right, t0 + 9 * sixteenth, samples, sampleRate, amp = 0.22f, f0 = 60.0, attack = 0.004, decay = 0.05)
                // Snares on 2 and 4 with a low body
                addSnare(left, right, t0 + 4 * sixteenth, samples, sampleRate)
                addSnare(left, right, t0 + 12 * sixteenth, samples, sampleRate)
                // Bass bleed on 8ths
                for (e in 0 until 8) {
                    addHit(
                        left,
                        right,
                        t0 + e * 2 * sixteenth,
                        samples,
                        sampleRate,
                        amp = 0.28f,
                        f0 = 48.0,
                        attack = 0.08,
                        decay = 0.22,
                    )
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        private fun addSnare(
            left: FloatArray,
            right: FloatArray,
            timeSec: Double,
            samples: Int,
            sampleRate: Int,
        ) {
            addHit(left, right, timeSec, samples, sampleRate, amp = 0.55f, f0 = 180.0, attack = 0.002, decay = 0.08)
            addHit(left, right, timeSec, samples, sampleRate, amp = 0.40f, f0 = 90.0, attack = 0.012, decay = 0.16)
        }

        private fun addHit(
            left: FloatArray,
            right: FloatArray,
            timeSec: Double,
            samples: Int,
            sampleRate: Int,
            amp: Float,
            f0: Double,
            attack: Double,
            decay: Double,
        ) {
            val start = (timeSec * sampleRate).toInt()
            val attackN = (attack * sampleRate).toInt().coerceAtLeast(1)
            val n = attackN + (decay * sampleRate).toInt()
            val tau = decay * 0.35 * sampleRate
            for (i in 0 until n) {
                val idx = start + i
                if (idx < 0 || idx >= samples) continue
                val env = if (i < attackN) {
                    i.toDouble() / attackN
                } else {
                    exp(-(i - attackN) / tau)
                }
                val s = (env * amp * sin(2.0 * PI * f0 * i / sampleRate)).toFloat()
                left[idx] += s
                right[idx] += s
            }
        }

        private fun wrapped(delta: Double, period: Double): Double {
            val p = period.coerceAtLeast(1e-9)
            var m = delta % p
            if (m < 0.0) m += p
            return min(m, p - m)
        }
    }
}
