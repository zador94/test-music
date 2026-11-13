package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Совместный поиск: темп и одна фаза.
 * Восьмая и половинный темп не подменяют четверть бочки.
 * Громкий затакт проигрывает первой доле, если бас и other стоят на раз.
 * Ранний бас и верхний щелчок бочку не переносят.
 */
class ClickGridTest {
    @Test
    fun quarterKicksKeepTempoAndLandOnTheAttack() {
        for (bpm in doubleArrayOf(75.0, 105.0, 120.0)) {
            val phase = 0.08
            val pcm = kicks(bpm, phase)
            val grid = ClickGrid.fromPcm(pcm)
            assertEquals("$bpm was ${grid.bpm}", bpm, grid.bpm.toDouble(), 0.5)
            assertTrue("$bpm must not double, was ${grid.bpm}", grid.bpm < bpm.toFloat() * 1.4f)
            val period = grid.periodSec
            assertEquals(60.0 / grid.bpm, period, 2e-3)
            assertTrue(abs(60.0 / period - grid.bpm) <= 0.015)
            val err = wrapped(grid.phaseSec - phase, period)
            assertTrue(
                "$bpm phase err=${err * 1000}ms, phase=${grid.phaseSec}",
                abs(err) < 0.025,
            )
            assertEquals(grid.phaseSec, grid.kickPhaseSec, 0.0)
            assertEquals(0.0, grid.downbeatOffset, 0.0)
            assertTrue(grid.beats.size >= 8)
            for (i in 1 until grid.beats.size) {
                assertEquals(period, grid.beats[i] - grid.beats[i - 1], 1e-4)
            }
            assertEquals(ClickGrid.VERSION, grid.kickGridVersion)
            assertTrue(ClickGrid.isCurrent(grid))
        }
    }

    @Test
    fun hintKeepsTheSearchNearTheKnownTempo() {
        val pcm = kicks(105.0, 0.11)
        val grid = ClickGrid.fromPcm(pcm, hintBpm = 104.5f)
        assertEquals(105.0, grid.bpm.toDouble(), 1.0)
        val err = wrapped(grid.phaseSec - 0.11, 60.0 / grid.bpm)
        assertTrue("hinted phase err=${err * 1000}ms", abs(err) < 0.025)
    }

    @Test
    fun silenceStaysOnTheFallbackTempo() {
        val n = 44100 * 4
        val pcm = StereoPcm(44100, FloatArray(n), FloatArray(n))
        val grid = ClickGrid.fromPcm(pcm)
        assertEquals(120f, grid.bpm, 0.01f)
        assertEquals(0.0, grid.phaseSec, 1e-9)
        assertTrue(ClickGrid.isCurrent(grid))
    }

    @Test
    fun backbeatIsTheQuarterNotTheKickHalfOrTheDottedPulse() {
        val grid = ClickGrid.fromPcm(backbeat(102.0))
        assertEquals("backbeat was ${grid.bpm}", 102.0, grid.bpm.toDouble(), 2.0)
        assertTrue("must not sit on 68, was ${grid.bpm}", abs(grid.bpm - 68f) > 8f)
    }

    @Test
    fun dottedKickLosesWhenTheQuarterChainIsLonger() {
        val bpm = 102.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 36
        val duration = 0.05 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        var kick = 0.05
        while (kick < duration - 0.05) {
            spike(low, sr, kick, 1f)
            kick += period * 1.5
        }
        var snare = 0.05
        repeat(beats) {
            spike(mid, sr, snare, 1f)
            snare += period
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("dotted kick stayed, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        assertTrue("must not sit on 68, was ${grid.bpm}", abs(grid.bpm - 68f) > 8f)
    }

    @Test
    fun chainInterceptBeatsTheIntroAttack() {
        val bpm = 100.0
        val period = 60.0 / bpm
        val sr = 44100
        val introPhase = 0.052
        val chorusPhase = 0.220
        val beats = 16
        val duration = 8 * period + chorusPhase + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        repeat(7) { i -> spike(low, sr, introPhase + i * period, 1f) }
        repeat(beats) { i -> spike(low, sr, 8 * period + chorusPhase + i * period, 0.70f) }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration)
        assertEquals("tempo left the chain, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 1.5)
        val errChorus = wrapped(grid.phaseSec - chorusPhase, grid.periodSec)
        val errIntro = wrapped(grid.phaseSec - introPhase, grid.periodSec)
        assertTrue(
            "phase must sit on the chorus chain, err=${errChorus * 1000}ms phase=${grid.phaseSec}",
            abs(errChorus) < 0.040,
        )
        assertTrue(
            "phase must leave the intro, err=${errIntro * 1000}ms phase=${grid.phaseSec}",
            abs(errIntro) > 0.080,
        )
    }

    @Test
    fun halfTimeSnareDoesNotHalveTheKickTempo() {
        val grid = ClickGrid.fromPcm(fastKicksWithHalfTimeBody(155.0))
        assertEquals("half-time body halved the kick, was ${grid.bpm}", 155.0, grid.bpm.toDouble(), 3.0)
        assertTrue("must stay on the fast octave, was ${grid.bpm}", grid.bpm > 120f)
    }

    @Test
    fun slowHintDoesNotReplaceTheFasterKick() {
        val grid = ClickGrid.fromPcm(kicks(155.0, 0.08), hintBpm = 77.5f)
        assertEquals("hint 77.5 replaced 155, was ${grid.bpm}", 155.0, grid.bpm.toDouble(), 3.0)
    }

    @Test
    fun fastHintDoesNotDoubleTheKick() {
        val grid = ClickGrid.fromPcm(kicks(100.0, 0.08), hintBpm = 200f)
        assertEquals("hint 200 doubled the kick, was ${grid.bpm}", 100.0, grid.bpm.toDouble(), 3.0)
    }

    @Test
    fun eighthAccentsDoNotReplaceTheQuarter() {
        val grid = ClickGrid.fromPcm(quartersWithEighths(80.0))
        assertEquals("eighths replaced the quarter, was ${grid.bpm}", 80.0, grid.bpm.toDouble(), 3.0)
        assertTrue("must stay under 120, was ${grid.bpm}", grid.bpm < 120f)
    }

    @Test
    fun highClickDoesNotMoveTheKick() {
        val beat = 0.20
        val bpm = 105.0
        for (lead in doubleArrayOf(0.180, (60.0 / bpm) / 4.0)) {
            val body = beat + lead
            val grid = ClickGrid.fromPcm(kickWithEarlyClick(bpm, beat, leadSec = lead))
            val period = 60.0 / grid.bpm
            val err = wrapped(grid.phaseSec - body, period)
            assertTrue(
                "lead=${lead * 1000}ms must leave the kick body, err=${err * 1000}ms phase=${grid.phaseSec} bpm=${grid.bpm}",
                abs(err) < 0.040,
            )
        }
    }

    @Test
    fun bassAttack180msEarlierStaysOnTheKick() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val beat = 0.22
        val body = beat + 0.180
        val grid = gridWithLead(bpm, beat, body)
        val err = wrapped(grid.phaseSec - body, period)
        assertTrue(
            "180ms lead must not leave the kick, err=${err * 1000}ms phase=${grid.phaseSec} bpm=${grid.bpm}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun sixteenthEarlyBassDoesNotLeaveTheKickBody() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val beat = 0.22
        val body = beat + period / 4.0
        val grid = gridWithLead(bpm, beat, body)
        val err = wrapped(grid.phaseSec - body, period)
        assertTrue(
            "16th-early bass must stay on the body, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun snareInTheDrumsDoesNotStealTheKick() {
        val bpm = 155.0
        val period = 60.0 / bpm
        val kick = 0.30
        val snare = kick - 0.190
        val grid = gridWithLeadOnDrumsToo(bpm, snare, kick)
        val err = wrapped(grid.phaseSec - kick, period)
        assertTrue(
            "snare 190ms earlier must stay off the kick, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun lateLowHumpStaysOnTheFluxAttack() {
        val bpm = 102.0
        val period = 60.0 / bpm
        val attack = 0.20
        val body = attack + 0.080
        val grid = gridWithBody(bpm, attack, body)
        val err = wrapped(grid.phaseSec - attack, period)
        assertTrue(
            "80ms hump must leave the flux attack, err=${err * 1000}ms phase=${grid.phaseSec} bpm=${grid.bpm}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun anacrusisLosesWhenBassAndOtherHitTheDownbeat() {
        val bpm = 100.0
        val period = 60.0 / bpm
        val sr = 44100
        val beat0 = 0.30
        val beats = 32
        val duration = beat0 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        val other = FloatArray(n)
        for (i in 0 until beats) {
            val beat = beat0 + i * period
            spike(low, sr, beat - 0.050, 1f)
            spike(low, sr, beat, 0.70f)
            if (i % 4 == 0) {
                spike(bass, sr, beat, 1f)
                spike(other, sr, beat, 1f)
            }
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass, other = other)
        assertEquals("anacrusis moved the tempo, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val err = wrapped(grid.phaseSec - beat0, grid.periodSec)
        assertTrue(
            "anacrusis took the downbeat, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun shortKickStaysOnTheAttack() {
        val bpm = 155.0
        val period = 60.0 / bpm
        val attack = 0.12
        val grid = gridWithBody(bpm, attack, attack + 0.018)
        val err = wrapped(grid.phaseSec - attack, period)
        assertTrue(
            "20ms body must stay on the attack, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun olderJsonIsNotCurrent() {
        val stale = TrackAnalysis.grid(120f).copy(kickGridVersion = ClickGrid.VERSION - 1)
        assertTrue(!ClickGrid.isCurrent(stale))
        assertTrue(!ClickGrid.isCurrent(null))
    }

    /** Атака в одном хопе, горб низа на [bodyAt]. */
    private fun gridWithBody(bpm: Double, attackAt: Double, bodyAt: Double): TrackAnalysis {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = bodyAt + 16 * period + 0.2
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        var attack = attackAt
        var body = bodyAt
        repeat(16) {
            val a = (attack * sr / ClickGrid.HOP).toInt()
            val b = (body * sr / ClickGrid.HOP).toInt().coerceAtLeast(a)
            if (a in low.indices) low[a] = 0.55f
            if (b > a) {
                for (i in a + 1..b) {
                    if (i !in low.indices) break
                    val k = (i - a).toFloat() / (b - a)
                    low[i] = 0.55f + 0.45f * k
                }
            }
            attack += period
            body += period
        }
        return ClickGrid.fromBands(low, null, null, sr, duration)
    }

    /** Тот же удар и в низе, и в басу: малый, не чужая атака. */
    private fun gridWithLeadOnDrumsToo(bpm: Double, snareAt: Double, kickAt: Double): TrackAnalysis {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = kickAt + 16 * period + 0.2
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val lead = FloatArray(n)
        var snare = snareAt
        var kick = kickAt
        repeat(16) {
            spike(low, sr, kick, 1f)
            spike(mid, sr, snare, 1f)
            spike(lead, sr, snare, 1f)
            snare += period
            kick += period
        }
        return ClickGrid.fromBands(low, mid, null, sr, duration, lead = lead)
    }

    /** Низ — тело бочки, [leadAt] — отдельная атака баса/«музыки». */
    private fun gridWithLead(bpm: Double, leadAt: Double, bodyAt: Double): TrackAnalysis {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = bodyAt + 16 * period + 0.2
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val lead = FloatArray(n)
        var body = bodyAt
        var leadT = leadAt
        repeat(16) {
            spike(low, sr, body)
            spike(lead, sr, leadT)
            body += period
            leadT += period
        }
        return ClickGrid.fromBands(low, null, null, sr, duration, lead = lead)
    }

    private fun spike(energy: FloatArray, sampleRate: Int, timeSec: Double, amp: Float = 1f) {
        val i = (timeSec * sampleRate / ClickGrid.HOP).toInt()
        if (i in energy.indices) energy[i] = amp
    }

    private fun wrapped(delta: Double, period: Double): Double {
        var m = delta % period
        if (m > period / 2.0) m -= period
        if (m < -period / 2.0) m += period
        return m
    }

    private fun kicks(bpm: Double, phaseSec: Double): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = phaseSec + 8 * 4 * period + 0.5
        val n = (duration * sr).toInt()
        val left = FloatArray(n)
        val attack = (0.012 * sr).toInt()
        var t = phaseSec
        while (t < duration - 0.05) {
            val i0 = (t * sr).toInt()
            for (k in 0 until attack) {
                val i = i0 + k
                if (i !in left.indices) continue
                val env = 1f - k.toFloat() / attack
                left[i] = env * sin(2.0 * PI * 70.0 * k / sr).toFloat()
            }
            t += period
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    /** Бочка на 1 и 3, малый на 2 и 4. Низ один даёт половину темпа. */
    private fun backbeat(bpm: Double): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val beats = 32
        val n = (sr * (beats * period + 0.5)).toInt()
        val left = FloatArray(n)
        for (beat in 0 until beats) {
            val t = 0.05 + beat * period
            if (beat % 2 == 0) {
                burst(left, sr, t, freq = 70.0, seconds = 0.04, amp = 1f)
            } else {
                burst(left, sr, t, freq = 350.0, seconds = 0.02, amp = 0.8f)
            }
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    /** Четверти громче, восьмые почти такие же: короткий лаг не должен удвоить темп. */
    private fun quartersWithEighths(bpm: Double): StereoPcm {
        val sr = 44100
        val quarter = 60.0 / bpm
        val eighth = quarter / 2.0
        val beats = 32
        val n = (sr * (beats * quarter + 0.5)).toInt()
        val left = FloatArray(n)
        for (step in 0 until beats * 2) {
            val onQuarter = step % 2 == 0
            burst(
                left,
                sr,
                0.05 + step * eighth,
                freq = 70.0,
                seconds = if (onQuarter) 0.04 else 0.02,
                amp = if (onQuarter) 1f else 0.92f,
            )
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    /** Ровная бочка на каждой четверти 155 и громкое тело на каждой второй. */
    private fun fastKicksWithHalfTimeBody(bpm: Double): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val beats = 32
        val n = (sr * (beats * period + 0.5)).toInt()
        val left = FloatArray(n)
        for (beat in 0 until beats) {
            val t = 0.05 + beat * period
            burst(left, sr, t, freq = 70.0, seconds = 0.03, amp = 0.7f)
            if (beat % 2 == 0) {
                burst(left, sr, t, freq = 350.0, seconds = 0.04, amp = 1f)
            }
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    /** Короткий щелчок 2 кГц, тело 70 Гц на [leadSec] позже. */
    private fun kickWithEarlyClick(bpm: Double, phaseSec: Double, leadSec: Double): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val beats = 24
        val n = (sr * (phaseSec + beats * period + 0.5)).toInt()
        val left = FloatArray(n)
        var t = phaseSec
        repeat(beats) {
            burst(left, sr, t, freq = 2500.0, seconds = 0.004, amp = 0.9f)
            burst(left, sr, t + leadSec, freq = 70.0, seconds = 0.05, amp = 1f)
            t += period
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    private fun burst(left: FloatArray, sr: Int, timeSec: Double, freq: Double, seconds: Double, amp: Float) {
        val i0 = (timeSec * sr).toInt()
        val n = (seconds * sr).toInt().coerceAtLeast(1)
        for (k in 0 until n) {
            val i = i0 + k
            if (i !in left.indices) continue
            val env = 1f - k.toFloat() / n
            left[i] += amp * env * sin(2.0 * PI * freq * k / sr).toFloat()
        }
    }
}
