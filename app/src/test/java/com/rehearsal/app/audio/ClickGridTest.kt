package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
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
