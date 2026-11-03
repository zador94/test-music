package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.3.45: доли как Stembeat — onset_strength + beat_track tightness 100.
 * Не линейка TrackTempo. `downbeatOffset` — индекс 0..3.
 */
class StembeatBeatsTest {
    @Test
    fun drumsSoloLandsOnKicks() {
        val bpm = 120.0
        val phase = 0.0
        val pcm = KickGridTest.lowBandKicks(bpm = bpm, phaseSec = phase, bars = 8, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        val period = 60.0 / bpm
        assertTrue("drums solo beats, was ${analysis.beats.size}", analysis.beats.size >= 12)
        assertEquals("drums solo ~120, was ${analysis.bpm}", 120.0, analysis.bpm.toDouble(), 2.0)
        val mid = analysis.beats.drop(2).take(16)
        for (t in mid) {
            val err = wrapped(t - phase, period)
            assertTrue(
                "drums solo beat $t must sit on the kick grid, err=${err * 1000}ms",
                err < 0.030,
            )
        }
        assertTrue(
            "downbeatOffset is index 0..3, was ${analysis.downbeatOffset}",
            analysis.downbeatOffset in 0.0..3.0,
        )
        assertEquals(analysis.downbeatOffset.toInt().toDouble(), analysis.downbeatOffset, 0.0)
    }

    @Test
    fun synthetic120BeatsFollowClicks() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 120.0, bars = 8, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(120.0, analysis.bpm.toDouble(), 0.6)
        assertTrue("120 clicks beats, was ${analysis.beats.size}", analysis.beats.size >= 24)
        val period = 0.5
        val mid = analysis.beats.drop(1).take(20)
        for (t in mid) {
            val err = wrapped(t, period)
            assertTrue("120 beat $t err=${err * 1000}ms", err < 0.025)
        }
    }

    @Test
    fun drift120to116FollowsKicksNotConstantGrid() {
        val kicks = ClickBeatDriftTest.driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 32)
        val pcm = ClickBeatDriftTest.kicksAt(kicks)
        val analysis = TempoDetector.analyze(pcm)
        assertTrue("need beats on drift, was ${analysis.beats.size}", analysis.beats.size >= 24)
        val late = kicks.drop(kicks.size / 2)
        for (kick in late) {
            val nearest = analysis.beats.minBy { abs(it - kick) }
            assertTrue(
                "late kick $kick nearest beat $nearest d=${abs(nearest - kick) * 1000}ms",
                abs(nearest - kick) < 0.040,
            )
        }
        val intervals = diffs(analysis.beats).take(12)
        val frozen = 0.5
        val lastKickInterval = kicks[kicks.lastIndex] - kicks[kicks.lastIndex - 1]
        assertTrue(
            "last kick interval ${lastKickInterval * 1000}ms is not the 120 ruler",
            abs(lastKickInterval - frozen) > 0.008,
        )
        val lastBeatInterval = analysis.beats.last() - analysis.beats[analysis.beats.lastIndex - 1]
        assertTrue(
            "beat_track last interval ${lastBeatInterval * 1000}ms must not stay on the 500ms ruler",
            abs(lastBeatInterval - frozen) > 0.008 || abs(lastBeatInterval - lastKickInterval) < 0.040,
        )
        println("drift intervals ms=${intervals.map { (it * 1000).toInt() }}")
    }

    @Test
    fun balladIsNotDoubled() {
        val pcm = BalladTempoTest.balladGroove(bpm = 75.0, hats = true, pianoBleed = true)
        val analysis = TempoDetector.analyze(pcm)
        assertTrue("ballad must not be 2×, was ${analysis.bpm}", abs(analysis.bpm - 150f) > 8f)
        assertTrue("ballad must not be 153, was ${analysis.bpm}", abs(analysis.bpm - 153f) > 8f)
        assertTrue("ballad must not be 131, was ${analysis.bpm}", abs(analysis.bpm - 131f) > 8f)
        assertEquals("ballad ~75, was ${analysis.bpm}", 75.0, analysis.bpm.toDouble(), 3.0)
        val median = median(diffs(analysis.beats))
        assertTrue(
            "ballad beat step ${median * 1000}ms must be a quarter ~800ms, not 400ms",
            median in 0.70..0.95,
        )
    }

    @Test
    fun funkDownbeatIsKickNotSnare() {
        val bpm = 104.6
        val phase = 0.08
        val pcm = KickFunkGridTest.funkGroove(bpm = bpm, phaseSec = phase, bars = 8)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals("funk mix, was ${analysis.bpm}", 104.6, analysis.bpm.toDouble(), 4.0)
        val period = 60.0 / bpm
        val sixteenth = period / 4.0
        val db = analysis.downbeatOffset.toInt().coerceIn(0, 3)
        assertTrue("downbeatOffset 0..3, was ${analysis.downbeatOffset}", db in 0..3)
        val downbeats = analysis.beats.filterIndexed { i, _ -> i % 4 == db }
        assertTrue("need downbeats, was ${downbeats.size}", downbeats.size >= 4)
        val hop = TempoDetector.HOP / 44100.0
        val errKick = median(downbeats.map { wrapped(it - phase, period) })
        val errSnare = median(downbeats.map { wrapped(it - (phase + 4 * sixteenth), period) })
        val errSixteenth = median(downbeats.map { wrapped(it - (phase + sixteenth), period) })
        assertTrue(
            "funk downbeat on kick not snare. errKick=${errKick * 1000}ms errSnare=${errSnare * 1000}ms " +
                "err16=${errSixteenth * 1000}ms db=$db bpm=${analysis.bpm} beats=${analysis.beats.take(8).toList()}",
            errKick <= hop + 0.045 && errKick < errSixteenth,
        )
    }

    @Test
    fun realGrooveBeatIntervalsAreNotConstant() {
        val pcm = humanizedGroove(bpm = 102.0, bars = 12)
        val analysis = TempoDetector.analyze(pcm)
        assertTrue("need beats, was ${analysis.beats.size}", analysis.beats.size >= 16)
        val intervals = diffs(analysis.beats)
        assertTrue("need 10 intervals, was ${intervals.size}", intervals.size >= 10)
        val ten = intervals.take(10)
        val minI = ten.min()
        val maxI = ten.max()
        val spreadMs = (maxI - minI) * 1000.0
        println("stembeat real-groove 10 intervals s=${ten.map { "%.4f".format(it) }} spreadMs=$spreadMs")
        assertTrue(
            "Stembeat beats[] intervals must not be a constant ruler, spread=${spreadMs}ms ten=$ten",
            spreadMs > 8.0,
        )
        assertEquals(KickGrid.VERSION, analysis.kickGridVersion)
        assertEquals(15, KickGrid.VERSION)
    }

    @Test
    fun vesnaBeatIntervalsAreNotConstant() {
        val file = File("/tmp/vesna44100.wav")
        Assume.assumeTrue("vesna fixture", file.isFile)
        val full = WavReader.readStereo(file)
        val seconds = 40
        val n = min(full.samples, full.sampleRate * seconds)
        val pcm = StereoPcm(full.sampleRate, full.left.copyOf(n), full.right.copyOf(n))
        val analysis = TempoDetector.analyze(pcm)
        val intervals = diffs(analysis.beats)
        assertTrue("vesna beats, was ${analysis.beats.size}", analysis.beats.size >= 12)
        assertTrue("vesna intervals, was ${intervals.size}", intervals.size >= 10)
        val ten = intervals.drop(2).take(10)
        val spreadMs = (ten.max() - ten.min()) * 1000.0
        println(
            "vesna bpm=${analysis.bpm} downbeat=${analysis.downbeatOffset} " +
                "10 intervals s=${ten.map { "%.4f".format(it) }} spreadMs=$spreadMs",
        )
        assertTrue(
            "vesna beats[] must not be a constant ruler, spread=${spreadMs}ms ten=$ten",
            spreadMs > 8.0,
        )
    }

    private fun humanizedGroove(bpm: Double, bars: Int, sampleRate: Int = 44100): StereoPcm {
        val period = 60.0 / bpm
        val times = ArrayList<Double>()
        var t = 0.04
        val beats = bars * 4
        for (i in 0 until beats) {
            val swing = if (i % 2 == 1) 0.012 else 0.0
            val human = 0.009 * sin(i * 1.7) + 0.004 * ((i % 5) - 2)
            times.add((t + swing + human).coerceAtLeast(0.0))
            t += period
        }
        return ClickBeatDriftTest.kicksAt(times.toDoubleArray(), sampleRate)
    }

    private fun diffs(beats: DoubleArray): List<Double> {
        val out = ArrayList<Double>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d > 1e-4) out.add(d)
        }
        return out
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun wrapped(delta: Double, period: Double): Double {
        val p = period.coerceAtLeast(1e-9)
        var m = delta % p
        if (m < 0.0) m += p
        return min(m, p - m)
    }
}
