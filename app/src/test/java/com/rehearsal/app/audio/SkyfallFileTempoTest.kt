package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class SkyfallFileTempoTest {
    @Test
    fun skyfallFileStaysABallad() {
        val wav = File(System.getenv("TEMP") ?: "", "skyfall.wav")
        assumeTrue("decoded skyfall wav", wav.isFile && wav.length() > 1_000_000L)
        val pcm = WavReader.readStereo(wav)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals("Skyfall was ${analysis.bpm}", 75.6, analysis.bpm.toDouble(), 3.0)
        assertTrue("Skyfall must not stay on 151, was ${analysis.bpm}", analysis.bpm < 110f)
    }

    @Test
    fun loudDoubleTimeChorusDoesNotSetTheTempo() {
        val sr = 44100
        val verse = lowKicks(bpm = 76.0, seconds = 36.0, amp = 0.35f, sr = sr)
        val chorus = lowKicks(bpm = 152.0, seconds = 18.0, amp = 1f, sr = sr)
        val left = verse + chorus
        val pcm = StereoPcm(sr, left, left.copyOf())
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(
            "slow song with a loud double-time chorus was ${analysis.bpm}",
            76.0,
            analysis.bpm.toDouble(),
            4.0,
        )
        assertTrue(analysis.bpm < 110f)
    }

    private fun lowKicks(bpm: Double, seconds: Double, amp: Float, sr: Int): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val period = 60.0 / bpm
        var t = 0.02
        val decay = 0.11
        val tau = decay * sr
        val m = (decay * 3.0 * sr).toInt()
        while (t < seconds - 0.05) {
            val start = (t * sr).toInt()
            for (i in 0 until m) {
                val idx = start + i
                if (idx >= n) break
                val env = exp(-i / tau)
                out[idx] = (env * amp * sin(2.0 * PI * 55.0 * i / sr)).toFloat()
            }
            t += period
        }
        return out
    }
}
