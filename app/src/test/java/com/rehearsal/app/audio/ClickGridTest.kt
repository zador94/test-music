package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Совместный поиск: темп и одна фаза.
 * Восьмая и половинный темп не подменяют четверть бочки.
 * Бас и other не уводят фазу с более сильного удара бочки.
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
    fun halfTimeKickBelowTheSearchWindowBecomesTheQuarter() {
        val grid = ClickGrid.fromPcm(backbeat(75.0))
        assertEquals("half-time kick stayed off 75, was ${grid.bpm}", 75.0, grid.bpm.toDouble(), 2.0)
        assertTrue("must not sit on the dotted 93, was ${grid.bpm}", abs(grid.bpm - 93f) > 8f)
    }

    @Test
    fun halfNoteUnderTheFloorBeatsAnEqualInRangePeak() {
        assertTrue(ClickGrid.halfNoteBeatsInRange(0.0007f, 0.0006f))
        assertTrue(ClickGrid.halfNoteBeatsInRange(0.0004f, 0.0004f))
        assertTrue(ClickGrid.halfNoteBeatsInRange(0.0016f, 0.0005f))
        assertTrue(!ClickGrid.halfNoteBeatsInRange(0.0010f, 0.0013f))
        assertTrue(!ClickGrid.halfNoteBeatsInRange(0.0005f, 0.0007f))
        assertTrue(!ClickGrid.halfNoteBeatsInRange(0f, 0f))
    }

    @Test
    fun threeTwoPolyrhythmPrefersKickTempo() {
        // ~101.91 vs ~68.14: ложный 3:2 от пунктира бочки + малого на четверти.
        val quarterBpm = 101.91
        val kickBpm = 68.14
        val quarter = 60.0 / quarterBpm
        val kickPeriod = 60.0 / kickBpm
        val sr = 44100
        val beats = 36
        val duration = 0.05 + beats * quarter + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        var kick = 0.05
        while (kick < duration - 0.05) {
            spike(low, sr, kick, 1f)
            kick += kickPeriod
        }
        var snare = 0.05
        repeat(beats) {
            spike(mid, sr, snare, 1f)
            snare += quarter
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("3:2 stole the kick, was ${grid.bpm}", kickBpm, grid.bpm.toDouble(), 2.0)
        assertTrue("must not sit on 3:2 quarter, was ${grid.bpm}", abs(grid.bpm - quarterBpm) > 8.0)
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
    fun eighthKicksDoNotDoubleWhenTheSnareHoldsTheSlowTempo() {
        val grid = ClickGrid.fromPcm(fastKicksWithHalfTimeBody(155.0))
        assertEquals("eighth kicks doubled, was ${grid.bpm}", 77.5, grid.bpm.toDouble(), 3.0)
        assertTrue("must stay under 120, was ${grid.bpm}", grid.bpm < 120f)
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
    fun chordsOnTheDownbeatDoNotLeaveTheLouderKick() {
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
        val err = wrapped(grid.phaseSec - (beat0 - 0.050), grid.periodSec)
        assertTrue(
            "chords left the louder kick, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun equalKicksPreferTheChordOnTheDownbeat() {
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
            spike(low, sr, beat, 1f)
            if (i % 4 == 0) {
                spike(bass, sr, beat, 1f)
                spike(other, sr, beat, 1f)
            }
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass, other = other)
        val err = wrapped(grid.phaseSec - beat0, grid.periodSec)
        assertTrue(
            "equal kicks ignored the downbeat, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun syncopatedChordsDoNotPullTheKick() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val sr = 44100
        val kickAt = 0.20
        val shift = 0.140
        val beats = 32
        val duration = kickAt + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val bass = FloatArray(n)
        val other = FloatArray(n)
        repeat(beats) { i ->
            val t = kickAt + i * period
            spike(low, sr, t, 1f)
            spike(mid, sr, t + shift, 1f)
            spike(bass, sr, t + shift, 1f)
            spike(other, sr, t + shift, 1f)
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration, bass = bass, other = other)
        assertEquals("syncopation moved the tempo, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val err = wrapped(grid.phaseSec - kickAt, grid.periodSec)
        assertTrue(
            "syncopated chords pulled the kick, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun dottedKickAndSnareKeepTheKickNotThreeTwo() {
        val bpm = 102.0
        val period = 60.0 / bpm
        val kickBpm = bpm * 2.0 / 3.0
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
        assertEquals("3:2 stole the kick, was ${grid.bpm}", kickBpm, grid.bpm.toDouble(), 2.0)
        assertTrue("must not sit on 102, was ${grid.bpm}", abs(grid.bpm - bpm) > 8.0)
    }

    @Test
    fun snareOnEveryFastKickStaysFast() {
        val bpm = 155.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 32
        val duration = 0.05 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        repeat(beats) { i ->
            val t = 0.05 + i * period
            spike(low, sr, t, 1f)
            spike(mid, sr, t, 1f)
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("fast snare slowed the kick, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 3.0)
        assertTrue("must stay on the fast octave, was ${grid.bpm}", grid.bpm > 120f)
    }

    @Test
    fun syncopatedKick332WithBackbeatKeepsTheQuarter() {
        // Бочка на восьмых 0, 3, 6 такта (3+3+2), малый на 2 и 4. Период бочки 1.5 четверти, но четверть — 102.
        val bpm = 102.0
        val quarter = 60.0 / bpm
        val eighth = quarter / 2.0
        val sr = 44100
        val bars = 12
        val duration = 0.05 + bars * 4 * quarter + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        repeat(bars) { bar ->
            val start = 0.05 + bar * 4 * quarter
            for (step in intArrayOf(0, 3, 6)) spike(low, sr, start + step * eighth, 1f)
            for (step in intArrayOf(2, 6)) spike(mid, sr, start + step * eighth, 1f)
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("3+3+2 kick left the quarter, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val err = wrapped(grid.phaseSec - 0.05, grid.periodSec)
        assertTrue("phase off the downbeat, err=${err * 1000}ms phase=${grid.phaseSec}", abs(err) < 0.030)
    }

    @Test
    fun backbeat155KeepsTheQuarterNotHalfTime() {
        val grid = ClickGrid.fromPcm(backbeat(155.0))
        assertEquals("backbeat was ${grid.bpm}", 155.0, grid.bpm.toDouble(), 3.0)
        assertTrue("backbeat halved to half-time, was ${grid.bpm}", grid.bpm > 120f)
    }

    @Test
    fun kickAndSnareOnEveryFastBeatKeepTheFastTempo() {
        // Бочка на каждой доле, малый тоже на каждой, но на чётных сильнее. Малый на обеих половинах — быстрая сетка настоящая.
        val grid = ClickGrid.fromPcm(kickWithAlternatingSnare(155.0))
        assertEquals("snare on every beat halved the tempo, was ${grid.bpm}", 155.0, grid.bpm.toDouble(), 3.0)
        assertTrue("must stay on the fast octave, was ${grid.bpm}", grid.bpm > 120f)
    }

    @Test
    fun periodHoldsTheBeatAtBothEndsOfALongTrack() {
        // Сетка одна на трек: ошибка периода копится на каждой доле и видна к последней.
        val phase = 0.20
        val beats = 360
        for (bpm in doubleArrayOf(105.23, 88.61, 123.37)) {
            val grid = lowSpikes(bpm, phase, beats)
            val truePeriod = 60.0 / bpm
            for (k in intArrayOf(0, beats - 1)) {
                val truth = phase + k * truePeriod
                val nearest = Math.round((truth - grid.phaseSec) / grid.periodSec)
                val err = grid.phaseSec + nearest * grid.periodSec - truth
                assertTrue(
                    "$bpm beat $k off by ${err * 1000}ms, grid bpm=${grid.bpm} phase=${grid.phaseSec}",
                    abs(err) < 0.030,
                )
            }
        }
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
    fun nearIntegerTempoSnapsToTheWholeBpm() {
        // Второй проход находит темп спайков точно, а не со сдвигом автокорреляции (104.90 → 104.90, не 105.0x).
        // Окно 0.03 тянет к целому только то, что в нём лежит: 104.98 → 105.
        val grid = lowSpikes(104.98, 0.20, 64)
        assertEquals("104.98 did not snap, was ${grid.bpm}", 105f, grid.bpm, 0f)
        assertEquals(60.0 / 105.0, grid.periodSec, 1e-9)
    }

    @Test
    fun halfwayTempoIsNotSnapped() {
        val grid = lowSpikes(105.5, 0.20, 64)
        assertTrue("105.5 snapped, was ${grid.bpm}", abs(grid.bpm - 105.5f) < 0.3f)
        assertTrue("105.5 snapped, was ${grid.bpm}", abs(grid.bpm - grid.bpm.roundToInt()) > 0.12f)
    }

    @Test
    fun liveCoverTempoKeepsItsHundredths() {
        // Раньше окно 0.12 тянуло 105.07 к 105; теперь дробный хвост остаётся.
        val grid = lowSpikes(105.07, 0.20, 64)
        assertTrue("105.07 snapped to whole, was ${grid.bpm}", abs(grid.bpm - grid.bpm.roundToInt()) > 0.03f)
        assertTrue("period snapped to 60/105", abs(grid.periodSec - 60.0 / 105.0) > 1e-6)
    }

    @Test
    fun loudOffbeatSnareDoesNotPullThePhase() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val sr = 44100
        val kickAt = 0.20
        val snareLag = 0.285
        val beats = 32
        val duration = kickAt + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        repeat(beats) { i ->
            val t = kickAt + i * period
            spike(low, sr, t, 1f)
            spike(low, sr, t + snareLag, 0.5f)
            spike(mid, sr, t + snareLag, 1f)
            spike(high, sr, t + snareLag, 1f)
        }
        val grid = ClickGrid.fromBands(low, mid, high, sr, duration)
        assertEquals("snare moved the tempo, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val err = wrapped(grid.phaseSec - kickAt, grid.periodSec)
        assertTrue(
            "snare at +285ms pulled the phase, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun backbeatSnareMovesThePhaseOffTheSidechainBass() {
        val bpm = 105.0
        val period = 60.0 / bpm
        val sr = 44100
        val kickAt = 0.20
        val beats = 32
        val duration = kickAt + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        repeat(beats) { i ->
            val t = kickAt + i * period
            spike(low, sr, t, 0.6f)
            spike(low, sr, t + period / 2.0, 1f)
            if (i % 2 == 1) {
                spike(mid, sr, t, 1f)
                spike(high, sr, t, 1f)
            }
        }
        val grid = ClickGrid.fromBands(low, mid, high, sr, duration)
        assertEquals("bass moved the tempo, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val err = wrapped(grid.phaseSec - kickAt, grid.periodSec)
        assertTrue(
            "off-beat bass kept the phase, err=${err * 1000}ms phase=${grid.phaseSec}",
            abs(err) < 0.030,
        )
    }

    @Test
    fun hiHatOnTheBassBarelyQuieterThanSnareStillShiftsToTheKick() {
        val grid = sidechainBackbeat(hiHat = 0.44f, ghost = 0f)
        val err = wrapped(grid.phaseSec - SIDECHAIN_KICK_AT, grid.periodSec)
        assertTrue("hi-hat kept the phase on the bass, err=${err * 1000}ms", abs(err) < 0.030)
    }

    @Test
    fun ghostNotesOnOneAndThreeDoNotBlockTheBackbeat() {
        val grid = sidechainBackbeat(hiHat = 0f, ghost = 0.6f)
        val err = wrapped(grid.phaseSec - SIDECHAIN_KICK_AT, grid.periodSec)
        assertTrue("ghost notes kept the phase on the bass, err=${err * 1000}ms", abs(err) < 0.030)
    }

    @Test
    fun busyHatOnTheBassDoesNotBlockTheBackbeatShift() {
        // Хэт на басе так же громок, как малый на 2 и 4 с его слабыми ударами на 1 и 3: сумма середины на
        // обеих половинах почти равна. Решает чередование на долях (1.0 / 0.6), а не сумма.
        val grid = sidechainBackbeat(hiHat = 0.8f, ghost = 0.6f)
        val err = wrapped(grid.phaseSec - SIDECHAIN_KICK_AT, grid.periodSec)
        assertTrue("busy hat kept the phase on the bass, err=${err * 1000}ms", abs(err) < 0.030)
    }

    @Test
    fun snareOnEveryEighthKeepsThePhase() {
        val grid = sidechainBackbeat(hiHat = 0f, ghost = 0.9f)
        val err = wrapped(grid.phaseSec - (SIDECHAIN_KICK_AT + grid.periodSec / 2.0), grid.periodSec)
        assertTrue("even snare moved the phase, err=${err * 1000}ms", abs(err) < 0.030)
    }

    @Test
    fun fastBackbeatAboveTheCapKeepsTwoHundred() {
        val bpm = 200.0
        val quarter = 60.0 / bpm
        val sr = 44100
        val beats = 64
        val duration = 0.20 + beats * quarter + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        repeat(beats) { i ->
            val t = 0.20 + i * quarter
            if (i % 2 == 0) spike(low, sr, t, 1f) else spike(mid, sr, t, 1f)
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("200 was halved, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
    }

    @Test
    fun splitFundamentalBeatsANearUnrelatedTempo() {
        val sr = 44100
        val slow = 60.0 / 94.0
        val fast = 60.0 / 96.0
        val beats = 48
        val duration = 0.20 + beats * (slow + fast) / 2.0 + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        var t = 0.20
        repeat(beats) { i ->
            spike(low, sr, t, 1f)
            t += if (i % 2 == 0) slow else fast
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration)
        assertEquals("split 94/96 left ${grid.bpm}", 95.0, grid.bpm.toDouble(), 1.0)
        assertTrue("unrelated tempo won, was ${grid.bpm}", abs(grid.bpm - 105.0) > 4.0)
    }

    @Test
    fun kickOctaveSnapsToTheIntegerNotTheMixPeak() {
        val sr = 44100
        val snareHops = 89
        val kickHops = snareHops * 2
        val beats = 48
        val n = beats * kickHops + 400
        val low = FloatArray(n)
        val mid = FloatArray(n)
        for (i in 0 until beats) {
            val kick = 40 + i * kickHops
            val snare = 40 + i * snareHops
            if (kick in low.indices) low[kick] = 1f
            if (snare in mid.indices) mid[snare] = 1f
        }
        val duration = n * ClickGrid.HOP.toDouble() / sr
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("octave stayed on the mix peak, was ${grid.bpm}", 155.0, grid.bpm.toDouble(), 0.5)
    }

    @Test
    fun flatDrumsFollowTheBassPulse() {
        val bpm = 75.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 32
        val duration = 0.20 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        var t = 0.20
        repeat(beats) {
            spike(bass, sr, t, 1f)
            t += period
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass)
        assertEquals("flat drums kept a guess, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
    }

    @Test
    fun ghostKickOnTheFastBeatStillKeepsTwoHundred() {
        val bpm = 200.0
        val quarter = 60.0 / bpm
        val sr = 44100
        val beats = 64
        val duration = 0.20 + beats * quarter + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        repeat(beats) { i ->
            val t = 0.20 + i * quarter
            if (i % 2 == 0) {
                spike(low, sr, t, 1f)
            } else {
                spike(low, sr, t, 0.28f)
                spike(mid, sr, t, 1f)
            }
        }
        val grid = ClickGrid.fromBands(low, mid, null, sr, duration)
        assertEquals("ghost kick halved 200, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
    }

    @Test
    fun combBassKeepsTheHintWhenDrumsAreSilent() {
        val sr = 44100
        val beats = 48
        val duration = 0.20 + beats * (60.0 / 90.0) + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        for (bpm in doubleArrayOf(90.0, 128.0, 164.0)) {
            val period = 60.0 / bpm
            var t = 0.20
            while (t < duration - 0.2) {
                spike(bass, sr, t, 1f)
                t += period
            }
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, hintBpm = 100f, bass = bass)
        assertEquals("comb bass replaced the hint, was ${grid.bpm}", 100.0, grid.bpm.toDouble(), 1.0)
    }

    @Test
    fun swellAtSeventyFiveSurvivesACombOfAttacks() {
        val bpm = 75.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 32
        val duration = 0.20 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        val hopSec = ClickGrid.HOP.toDouble() / sr
        for (i in bass.indices) {
            val phase = (i * hopSec - 0.20) / period
            if (phase >= 0.0 && phase < beats) {
                val x = phase % 1.0
                bass[i] = (0.5 - 0.5 * cos(2.0 * PI * x)).toFloat()
            }
        }
        var state = 1
        for (i in bass.indices) {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            if (state % 12 == 0) bass[i] = 1f
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass)
        assertEquals("swell stayed hidden, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val crest = 0.20 + period / 2.0
        val err = wrapped(grid.phaseSec - crest, grid.periodSec)
        assertTrue("swell phase off by ${err * 1000}ms", abs(err) < 0.08)
    }

    @Test
    fun drumPulseAtNinetyFiveBeatsACombBass() {
        val sr = 44100
        val bpm = 95.0
        val period = 60.0 / bpm
        val beats = 48
        val duration = 0.20 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        var t = 0.20
        repeat(beats) {
            spike(low, sr, t, 1f)
            t += period
        }
        for (other in doubleArrayOf(90.0, 128.0, 164.0)) {
            val step = 60.0 / other
            var u = 0.15
            while (u < duration - 0.2) {
                spike(bass, sr, u, 1f)
                u += step
            }
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass)
        assertEquals("comb bass replaced 95, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
    }

    @Test
    fun profilePutsEveryBeatOnBinsZeroAndEight() {
        val profile = profileOfSpikes(period = 0.5, phase = 0.10, offset = 0.0, everyBeats = 1)
        assertEquals(16, profile.size)
        assertEquals(1.0, profile[0], 0.02)
        assertEquals(1.0, profile[8], 0.02)
        for (bin in (1..7) + (9..15)) assertEquals("bin $bin", 0.0, profile[bin], 1e-9)
    }

    @Test
    fun profileSeparatesTheTwoBeatsOfThePair() {
        val profile = profileOfSpikes(period = 0.5, phase = 0.10, offset = 0.0, everyBeats = 2)
        assertEquals(1.0, profile[0], 1e-9)
        assertEquals(0.0, profile[8], 1e-9)
    }

    @Test
    fun profilePutsTheEighthAfterTheBeatOnBinFour() {
        val profile = profileOfSpikes(period = 0.5, phase = 0.10, offset = 0.25, everyBeats = 2)
        assertEquals(1.0, profile[4], 1e-9)
        assertEquals(0.0, profile[0], 1e-9)
        assertEquals(0.0, profile[12], 1e-9)
    }

    @Test
    fun diagAcfListsTheTenStrongestPeaksByHeight() {
        val hop = 0.004
        val corr = FloatArray(420)
        val lags = intArrayOf(80, 110, 140, 170, 200, 230, 260, 290, 320, 350, 380, 40)
        for (i in lags.indices) {
            val lag = lags[i]
            corr[lag] = (100 - i).toFloat()
            corr[lag - 1] = 1f
            corr[lag + 1] = 1f
        }
        corr[60] = 80f
        corr[61] = 90f
        corr[62] = 70f
        val line = ClickGrid.diagAcf(corr, hop, 2, 410)
        val parts = line.removePrefix("acf ").split(" | ")
        assertEquals(10, parts.size)
        assertEquals(acfPeak(80, hop, 100f), parts[0])
        assertEquals(acfPeak(110, hop, 99f), parts[1])
        assertEquals(acfPeak(350, hop, 91f), parts[9])
        assertTrue(!line.contains(acfPeak(380, hop, 90f)))
        assertTrue(!line.contains(acfPeak(40, hop, 89f)))
        assertTrue(!line.contains(acfPeak(61, hop, 90f)))
        assertEquals("acf none", ClickGrid.diagAcf(FloatArray(20), hop, 2, 18))
        assertEquals("acf none", ClickGrid.diagAcf(corr, 0.0, 2, 410))
    }

    @Test
    fun choiceKeepsKickOnAWeakThreeTwoAndOnAnUnrelatedTempo() {
        val dotted = ClickGrid.choiceDiag(false, true, null, false, 95.3313, 70.8463)
        assertTrue(dotted.keepKick)
        assertEquals("keep=kick reason=threeTwo", dotted.text)
        val strong = ClickGrid.choiceDiag(false, true, null, false, 180.0, 100.0)
        assertTrue(!strong.keepKick)
        assertEquals("keep=best reason=threeTwo", strong.text)
        val unrelated = ClickGrid.choiceDiag(false, false, null, false, 114.0, 100.0)
        assertTrue(unrelated.keepKick)
        assertEquals("keep=kick reason=unrelated", unrelated.text)
        val won = ClickGrid.choiceDiag(false, false, null, false, 115.0, 100.0)
        assertTrue(!won.keepKick)
        assertEquals("keep=best reason=unrelated", won.text)
    }

    @Test
    fun choiceKeepsTheSlowKickWhenTheKickAlsoHitsTheFastBeat() {
        val eighths = ClickGrid.choiceDiag(true, false, 0.98, false, 200.0, 100.0)
        assertTrue(eighths.keepKick)
        assertEquals("keep=kick reason=octave", eighths.text)
        val backbeat = ClickGrid.choiceDiag(true, false, 0.44, false, 200.0, 100.0)
        assertTrue(!backbeat.keepKick)
        assertEquals("keep=best reason=octave", backbeat.text)
        val snareOnBoth = ClickGrid.choiceDiag(true, false, 0.98, true, 200.0, 100.0)
        assertTrue(!snareOnBoth.keepKick)
        assertEquals("keep=best reason=octave", snareOnBoth.text)
        val noise = ClickGrid.choiceDiag(true, false, 1.094, true, 107.2, 64.8, octaveBelowFloor = true)
        assertTrue(noise.keepKick)
        assertEquals("keep=kick reason=octave", noise.text)
    }

    @Test
    fun contestNamesTheNonHarmonicRivalAndRejectsACloseOne() {
        val hop = 0.004
        val corr = FloatArray(420)
        peak(corr, 150, 10f)
        peak(corr, 75, 9f)
        peak(corr, 100, 8f)
        peak(corr, 120, 8f)
        assertEquals(
            "best=100.0000 c=10.0000 rival=125.0000 c=8.0000 ratio=0.8000",
            ClickGrid.diagContest(corr, hop, 2, 400),
        )
        assertTrue(!ClickGrid.contestIsDominant(corr, hop, 2, 400))
        peak(corr, 120, 5f)
        assertEquals(
            "best=100.0000 c=10.0000 rival=125.0000 c=5.0000 ratio=0.5000",
            ClickGrid.diagContest(corr, hop, 2, 400),
        )
        assertTrue(ClickGrid.contestIsDominant(corr, hop, 2, 400))
        val alone = FloatArray(420)
        peak(alone, 150, 10f)
        assertEquals(
            "best=100.0000 c=10.0000 rival=null c=0.0000 ratio=null",
            ClickGrid.diagContest(alone, hop, 2, 400),
        )
        assertEquals("best=null", ClickGrid.diagContest(FloatArray(20), hop, 2, 18))
    }

    @Test
    fun attackRepeatReportsTheDoubleThatIsCloseToTheBestPeak() {
        assertEquals(
            "rivalBpm=160.0000 c=10.0000 bestC=10.0000 ratio=1.0000",
            ClickGrid.diagAttackRepeat(listOf(160.0 to 10f, 100.0 to 4f), 80.0),
        )
        assertEquals(
            "rivalBpm=320.0000 c=9.0000 bestC=10.0000 ratio=0.9000",
            ClickGrid.diagAttackRepeat(listOf(320.0 to 9f, 100.0 to 10f), 80.0),
        )
        assertTrue(ClickGrid.diagAttackRepeat(listOf(160.0 to 7f, 100.0 to 10f), 80.0) == null)
        assertTrue(ClickGrid.diagAttackRepeat(listOf(150.0 to 10f), 80.0) == null)
    }

    @Test
    fun fourPercentOffTheStrumIsNotABlurredAttack() {
        // 80.26 × 4 ещё рядом с 328. 78.74 × 4 = 315 — уже нет. Окно это не лечит: решает бочка.
        assertTrue(ClickGrid.diagAttackRepeat(listOf(328.3095 to 0.0036f, 261.6223 to 0.0027f), 80.2573) != null)
        assertTrue(ClickGrid.diagAttackRepeat(listOf(328.2538 to 0.0057f, 261.5563 to 0.0041f), 78.7433) == null)
    }

    @Test
    fun loggedSeventyFiveIsABeatAndEightyIsFlat() {
        val at75 = doubleArrayOf(1.00, 0.36, 0.33, 0.62, 0.63, 0.44, 0.76, 0.69, 0.40, 0.35, 0.37, 0.37, 0.30, 0.28, 0.34, 0.88)
        val at80 = doubleArrayOf(1.00, 0.85, 0.81, 0.82, 0.83, 0.92, 0.89, 0.93, 0.89, 0.91, 0.86, 0.84, 0.90, 0.86, 0.89, 0.88)
        val beat = ClickGrid.pulseContrast(at75)
        val flat = ClickGrid.pulseContrast(at80)
        assertTrue("75 contrast $beat", beat >= 0.30)
        assertTrue("80 contrast $flat", flat < 0.20)
        assertTrue(beat >= flat + 0.15)
    }

    @Test
    fun kickAtSeventyFiveBeatsTheFlatEnvelopeAndAnEmptyKickDoesNot() {
        val hop = ClickGrid.HOP.toDouble() / 44100
        val n = (30.0 / hop).toInt()
        val kick = pulseTrain(n, hop, 75.0)
        assertTrue(ClickGrid.kickBeatsEnvelope(60.0 / 78.74, 60.0 / 75.0, kick, hop))
        assertTrue(!ClickGrid.kickBeatsEnvelope(60.0 / 78.74, 60.0 / 75.0, FloatArray(n), hop))
        val onTheEnvelope = pulseTrain(n, hop, 78.74)
        assertTrue(!ClickGrid.kickBeatsEnvelope(60.0 / 78.74, 60.0 / 75.0, onTheEnvelope, hop))
    }

    @Test
    fun lowBeatAtSeventyFiveBeatsTheEnvelopeAndItsFastOctave() {
        // Счёт берёт 152, свёртка и низ — 75. Огибающая 78.74 на этом низе плоская.
        val hop = ClickGrid.HOP.toDouble() / 44100
        val n = (30.0 / hop).toInt()
        val low = pulseTrain(n, hop, 75.0)
        val picked = ClickGrid.beatPeriodOverEnvelope(
            60.0 / 78.74,
            listOf(60.0 / 75.1022, 60.0 / 152.6383, 60.0 / 93.1795),
            low,
            hop,
        )
        assertTrue("low beat was $picked", picked != null)
        assertEquals(75.0, 60.0 / picked!!, 1.0)
        assertTrue(ClickGrid.beatPeriodOverEnvelope(60.0 / 78.74, listOf(60.0 / 75.0), null, hop) == null)
        val onEnvelope = pulseTrain(n, hop, 78.74)
        assertTrue(
            ClickGrid.beatPeriodOverEnvelope(
                60.0 / 78.74,
                listOf(60.0 / 75.0, 60.0 / 152.0),
                onEnvelope,
                hop,
            ) == null,
        )
    }

    @Test
    fun silentDrumsKeepTheBassSwellBesideAStrumComb() {
        val bpm = 80.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 32
        val duration = 0.20 + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val bass = FloatArray(n)
        val other = FloatArray(n)
        val hopSec = ClickGrid.HOP.toDouble() / sr
        for (i in bass.indices) {
            val phase = (i * hopSec - 0.20) / period
            if (phase >= 0.0 && phase < beats) {
                val swell = (0.5 - 0.5 * cos(2.0 * PI * phase)).toFloat()
                bass[i] = swell
                other[i] = swell
            }
        }
        for (strum in doubleArrayOf(320.0, 260.0)) {
            var t = 0.27
            val step = 60.0 / strum
            while (t < duration - 0.2) {
                spike(bass, sr, t, 1f)
                spike(other, sr, t, 1f)
                t += step
            }
        }
        val grid = ClickGrid.fromBands(low, null, null, sr, duration, bass = bass, other = other)
        assertEquals("bass swell was dropped, was ${grid.bpm}", bpm, grid.bpm.toDouble(), 2.0)
        val crest = 0.20 + period / 2.0
        val err = wrapped(grid.phaseSec - crest, grid.periodSec)
        assertTrue("phase left the swell, err=${err * 1000}ms phase=${grid.phaseSec}", abs(err) < 0.08)
    }

    @Test
    fun diagFoldsKeepsTheTenHighestDistinctPeriods() {
        val ranked = ArrayList<Pair<Double, Float>>()
        val scores = floatArrayOf(0.5f, 0.25f, 0.125f, 0.0625f, 0.03125f, 0.015625f, 0.0078125f, 0.00390625f, 0.001953125f, 0.0009765625f, 0.00048828125f)
        for (i in scores.indices) ranked.add((60.0 / (70.0 + i)) to scores[i])
        val parts = ClickGrid.diagFolds(ranked).removePrefix("fold ").split(" | ")
        assertEquals(10, parts.size)
        assertEquals("bpm=70.0000 s=0.500000", parts[0])
        assertEquals("bpm=79.0000 s=0.000977", parts[9])
        assertEquals("fold none", ClickGrid.diagFolds(emptyList()))
    }

    @Test
    fun profileAlignedPutsTheStrongestBinOnZero() {
        val hop = ClickGrid.HOP.toDouble() / 44100
        val period = 60.0 / 75.0
        val energy = FloatArray((8 * period / hop).toInt() + 2)
        var t = 0.2
        while (t < energy.size * hop) {
            val i = (t / hop).toInt()
            if (i in energy.indices) energy[i] = 1f
            t += period
        }
        val raw = ClickGrid.profile(energy, hop, period, 0.0)
        assertTrue(raw[0] < 1.0)
        val aligned = ClickGrid.profileAligned(energy, hop, 75.0)
        assertEquals(1.0, aligned[0], 1e-9)
        assertTrue(aligned[1] < 1.0)
    }

    @Test
    fun profileOfSilenceAndBadInputIsZeros() {
        val zeros = DoubleArray(16)
        assertTrue(ClickGrid.profile(FloatArray(400), 0.004, 0.5, 0.0).contentEquals(zeros))
        assertTrue(ClickGrid.profile(FloatArray(0), 0.004, 0.5, 0.0).contentEquals(zeros))
        assertTrue(ClickGrid.profile(FloatArray(400) { 1f }, 0.004, 0.0, 0.0).contentEquals(zeros))
    }

    @Test
    fun olderJsonIsNotCurrent() {
        val stale = TrackAnalysis.grid(120f).copy(kickGridVersion = ClickGrid.VERSION - 1)
        assertTrue(!ClickGrid.isCurrent(stale))
        assertTrue(!ClickGrid.isCurrent(null))
    }

    private fun pulseTrain(n: Int, hop: Double, bpm: Double): FloatArray {
        val out = FloatArray(n)
        val period = 60.0 / bpm
        var t = 0.2
        while (t < n * hop - 0.05) {
            val i = (t / hop).toInt()
            if (i in out.indices) out[i] = 1f
            t += period
        }
        return out
    }

    private fun peak(corr: FloatArray, lag: Int, c: Float) {
        corr[lag] = c
        corr[lag - 1] = c * 0.5f
        corr[lag + 1] = c * 0.5f
    }

    private fun acfPeak(lag: Int, hop: Double, c: Float): String {
        val bpm = 60.0 / (lag * hop)
        return "bpm=${String.format(Locale.US, "%.4f", bpm)} c=${String.format(Locale.US, "%.4f", c.toDouble())}"
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

    /** Бочка на доле, сайдчейн-бас на восьмой, малый на 2 и 4; [ghost] на 1 и 3, [hiHat] в середине на басе. */
    private fun sidechainBackbeat(hiHat: Float, ghost: Float): TrackAnalysis {
        val bpm = 105.0
        val period = 60.0 / bpm
        val sr = 44100
        val beats = 32
        val duration = SIDECHAIN_KICK_AT + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        repeat(beats) { i ->
            val t = SIDECHAIN_KICK_AT + i * period
            spike(low, sr, t, 0.6f)
            spike(low, sr, t + period / 2.0, 1f)
            spike(mid, sr, t + period / 2.0, hiHat)
            val snare = if (i % 2 == 1) 1f else ghost
            spike(mid, sr, t, snare)
            spike(high, sr, t, snare)
        }
        return ClickGrid.fromBands(low, mid, high, sr, duration)
    }

    /** Спайки на `phase + offset + k * period`, каждый [everyBeats]-й k. */
    private fun profileOfSpikes(period: Double, phase: Double, offset: Double, everyBeats: Int): DoubleArray {
        val sr = 44100
        val duration = 20.0
        val energy = FloatArray((duration * sr / ClickGrid.HOP).toInt())
        var k = 0
        while (phase + offset + k * period < duration - 0.1) {
            spike(energy, sr, phase + offset + k * period)
            k += everyBeats
        }
        return ClickGrid.profile(energy, ClickGrid.HOP.toDouble() / sr, period, phase)
    }

    private fun lowSpikes(bpm: Double, phaseSec: Double, beats: Int): TrackAnalysis {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = phaseSec + beats * period + 0.5
        val n = (duration * sr / ClickGrid.HOP).toInt().coerceAtLeast(8)
        val low = FloatArray(n)
        repeat(beats) { i -> spike(low, sr, phaseSec + i * period, 1f) }
        return ClickGrid.fromBands(low, null, null, sr, duration)
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

    /** Бочка на каждой доле, малый на каждой: чётные сильнее ([evenAmp]), нечётные слабее ([oddAmp]). */
    private fun kickWithAlternatingSnare(bpm: Double, evenAmp: Float = 1f, oddAmp: Float = 0.6f): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val beats = 32
        val n = (sr * (beats * period + 0.5)).toInt()
        val left = FloatArray(n)
        for (beat in 0 until beats) {
            val t = 0.05 + beat * period
            burst(left, sr, t, freq = 70.0, seconds = 0.03, amp = 0.7f)
            burst(left, sr, t, freq = 350.0, seconds = 0.04, amp = if (beat % 2 == 0) evenAmp else oddAmp)
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

    /** Бочка на каждой восьмой относительно 77.5 и малый только на четверти. */
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

    private companion object {
        const val SIDECHAIN_KICK_AT = 0.20
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
