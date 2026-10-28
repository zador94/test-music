package com.rehearsal.app.audio

import com.rehearsal.app.data.SplitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TempoDetectorTest {
    @Test
    fun syntheticStereoClick120BpmFourBars() {
        val pcm = stereoClicks(bpm = 120.0, bars = 4, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)

        assertEquals(44100, analysis.sampleRate)
        assertEquals(
            pcm.samples.toDouble() / 44100.0,
            analysis.duration,
            1e-9,
        )
        assertEquals(4, analysis.timeSignature[0])
        assertEquals(4, analysis.timeSignature[1])
        assertEquals(SplitKind.HTDEMUCS_4STEM, analysis.separator)
        assertEquals(TrackAnalysis.DEFAULT_STEMS, analysis.stems)
        assertEquals(
            "synthetic 120 BPM click must land ±0.4, was ${analysis.bpm}",
            120.0,
            analysis.bpm.toDouble(),
            0.4,
        )
        assertTrue("beats ≥ 12, was ${analysis.beats.size}", analysis.beats.size >= 12)
        assertTrue(analysis.downbeatOffset >= 0.0)
        assertTrue(analysis.downbeatOffset < 0.6)
    }

    @Test
    fun halfDoubleClamp() {
        assertEquals(60.0, TempoMath.clampHalfDouble(60.0), 1e-9)
        assertEquals(65.5, TempoMath.clampHalfDouble(65.5), 1e-9)
        assertEquals(120.0, TempoMath.clampHalfDouble(240.0), 1e-9)
        assertEquals(102.0, TempoMath.clampHalfDouble(102.0), 1e-9)
        assertEquals(102.0, TempoMath.clampHalfDouble(51.0), 1e-9)
        assertEquals(102.0, TempoMath.clampHalfDouble(204.0), 1e-9)
        assertEquals(80.0, TempoMath.clampHalfDouble(40.0), 1e-9)
        assertEquals(100.0, TempoMath.clampHalfDouble(200.0), 1e-9)
        assertEquals(120.0, TempoMath.clampHalfDouble(0.0), 1e-9)
        assertEquals(120.0, TempoMath.clampHalfDouble(Double.NaN), 1e-9)
        assertEquals(70.0, TempoMath.clampHalfDouble(70.0), 1e-9)
        assertEquals(180.0, TempoMath.clampHalfDouble(180.0), 1e-9)
    }

    @Test
    fun sixtyBpmClicksStayNear60Or120() {
        val pcm = stereoClicks(bpm = 60.0, bars = 4, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        val bpm = analysis.bpm.toDouble()
        assertTrue(
            "60 BPM clicks stay a ballad or the 120 pulse, was $bpm",
            bpm in 58.0..72.0 || bpm in 115.0..125.0,
        )
        assertTrue(analysis.beats.size >= 8)
    }

    @Test
    fun sixtyFivePointFiveMustNotBecome131() {
        val pcm = stereoClicks(bpm = 65.5, bars = 8, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        assertTrue("65.5 clicks must not clamp to 131, was ${analysis.bpm}", abs(analysis.bpm - 131f) > 8f)
        assertEquals("65.5 ballad pulse, was ${analysis.bpm}", 65.5, analysis.bpm.toDouble(), 3.0)
    }

    @Test
    fun preferMixKeepsBalladWhenDrumsDouble() {
        assertEquals(75f, TempoMath.preferMixIfDrumsOctave(75f, 150f), 1e-3f)
        assertEquals(75f, TempoMath.preferMixIfDrumsOctave(75f, 131f), 1e-3f)
        assertEquals(75f, TempoMath.preferMixIfDrumsOctave(75f, 65.5f), 1e-3f)
        assertEquals(73.7f, TempoMath.preferMixIfDrumsOctave(73.7f, 131f), 0.05f)
        assertEquals(104.8f, TempoMath.preferMixIfDrumsOctave(104.6f, 104.8f), 1e-3f)
        assertEquals(75f, TempoMath.preferMixIfDrumsOctave(150f, 75f), 1e-3f)
        assertEquals(76.5f, TempoMath.preferMixIfDrumsOctave(153f, 153f), 0.05f)
        assertEquals(76.5, TempoMath.foldDoubleBallad(153.0), 1e-9)
        assertEquals(75.0, TempoMath.foldDoubleBallad(150.0), 1e-9)
        assertEquals(104.6, TempoMath.foldDoubleBallad(104.6), 1e-9)
        assertEquals(120.0, TempoMath.foldDoubleBallad(120.0), 1e-9)
    }

    @Test
    fun bpmFromBeatsRecoversHintThatIsTwoBpmOff() {
        val period = 60.0 / 102.0
        val beats = DoubleArray(24) { i ->
            i * period + if (i % 2 == 0) 0.006 else -0.005
        }
        val measured = TempoMath.bpmFromBeats(beats, hintBpm = 100.0)
        assertEquals(102.0, measured!!, 0.15)
    }

    @Test
    fun synthetic102BpmDoesNotDriftByOneOrTwo() {
        val pcm = stereoClicks(bpm = 102.0, bars = 8, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(
            "102 BPM song must not be stored as 100 or 104, was ${analysis.bpm}",
            102.0,
            analysis.bpm.toDouble(),
            0.4,
        )
    }

    @Test
    fun analysisJsonRoundtripKeepsBeats() {
        val pcm = stereoClicks(bpm = 120.0, bars = 4, beatsPerBar = 4)
        val original = TempoDetector.analyze(pcm)
        val parsed = TrackAnalysis.fromJson(original.toJson())
        assertNotNull(parsed)
        assertEquals(original.bpm, parsed!!.bpm, 1e-3f)
        assertEquals(original.kickPhaseSec, parsed.kickPhaseSec, 1e-6)
        assertEquals(original.kickGridVersion, parsed.kickGridVersion)
        assertEquals(original.beats.size, parsed.beats.size)
        assertEquals(original.duration, parsed.duration, 1e-6)
        assertEquals(original.sampleRate, parsed.sampleRate)
        assertEquals(original.separator, parsed.separator)
        for (i in original.beats.indices) {
            assertEquals(original.beats[i], parsed.beats[i], 1e-5)
        }
        val us = parsed.beatTimesUs()
        assertEquals(parsed.beats.size, us.size)
        assertEquals(0L, us[0])
        assertTrue(us[1] > us[0])
    }

    companion object {
        fun stereoClicks(
            bpm: Double,
            bars: Int,
            beatsPerBar: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val beats = bars * beatsPerBar
            val period = 60.0 / bpm
            val durationSec = beats * period
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (sampleRate * 0.008).toInt().coerceAtLeast(32)
            for (b in 0 until beats) {
                val start = (b * period * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx >= samples) break
                    val env = 1.0 - i.toDouble() / clickLen
                    val s = (env * 0.95 * sin(2.0 * PI * 2000.0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s * 0.92f
                }
            }
            return StereoPcm(sampleRate, left, right)
        }
    }
}
