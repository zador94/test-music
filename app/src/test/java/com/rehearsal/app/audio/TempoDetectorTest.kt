package com.rehearsal.app.audio

import com.rehearsal.app.data.SplitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
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
            "synthetic 120 BPM click must land ±1.5, was ${analysis.bpm}",
            120.0,
            analysis.bpm.toDouble(),
            1.5,
        )
        assertTrue("beats ≥ 12, was ${analysis.beats.size}", analysis.beats.size >= 12)
        assertTrue(analysis.downbeatOffset >= 0.0)
        assertTrue(analysis.downbeatOffset < 0.6)
    }

    @Test
    fun halfDoubleClamp() {
        assertEquals(120.0, TempoMath.clampHalfDouble(60.0), 1e-9)
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
    fun sixtyBpmClicksClampTo120() {
        val pcm = stereoClicks(bpm = 60.0, bars = 4, beatsPerBar = 4)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(
            "half-tempo train must clamp into 70–180 near 120, was ${analysis.bpm}",
            120.0,
            analysis.bpm.toDouble(),
            1.5,
        )
        assertTrue(analysis.beats.size >= 12)
    }

    @Test
    fun analysisJsonRoundtripKeepsBeats() {
        val pcm = stereoClicks(bpm = 120.0, bars = 4, beatsPerBar = 4)
        val original = TempoDetector.analyze(pcm)
        val parsed = TrackAnalysis.fromJson(original.toJson())
        assertNotNull(parsed)
        assertEquals(original.bpm, parsed!!.bpm, 1e-3f)
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
