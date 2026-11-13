package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavReaderKickGridTest {
    @Test
    fun readStereoRoundtripThenClickGridStampsVersion() {
        val sr = 44100
        val bpm = 104.6
        val phase = 0.08
        val period = 60.0 / bpm
        val n = (sr * 16)
        val left = FloatArray(n)
        val attack = (0.012 * sr).toInt()
        var t = phase
        while (t < 15.5) {
            val i0 = (t * sr).toInt()
            for (k in 0 until attack) {
                val i = i0 + k
                if (i !in left.indices) continue
                left[i] = (1f - k.toFloat() / attack) * sin(2.0 * PI * 70.0 * k / sr).toFloat()
            }
            t += period
        }
        val pcm = StereoPcm(sr, left, left.copyOf())
        val file = File.createTempFile("drums", ".wav")
        try {
            WavWriter(file, pcm.sampleRate, channels = 2).use { writer ->
                writer.writePlanarFloatStereo(pcm.left, pcm.right)
            }
            val read = WavReader.readStereo(file)
            assertEquals(pcm.sampleRate, read.sampleRate)
            assertEquals(pcm.samples, read.samples)
            assertTrue(abs(read.left[100] - pcm.left[100]) < 1e-5f)
            val grid = ClickGrid.fromPcm(read)
            assertEquals(ClickGrid.VERSION, grid.kickGridVersion)
            assertTrue(ClickGrid.isCurrent(grid))
            assertEquals(bpm, grid.bpm.toDouble(), 0.5)
        } finally {
            file.delete()
        }
    }
}
