package com.rehearsal.app.audio

import com.rehearsal.app.data.StemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.sin

/**
 * 0.3.29: внешние куски 30 с + линейный кроссфейд поверх окон HT-Demucs 7.8 с.
 * Identity-склейка совпадающего overlap проходит, но независимый Demucs
 * каждого 30 с паддит последний внутренний сегмент нулями — помехи.
 * Стрим overlap-add теми же окнами, что 0.3.28, должен восстанавливать микс.
 */
class HtDemucsStreamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun identityReconstructsShortRampShorterThanOneWindow() {
        val n = 20_000
        assertTrue(n < HtDemucs.SEGMENT_SAMPLES)
        assertIdentity("short-ramp", ramp(n))
    }

    @Test
    fun identityReconstructsAcrossSeveralModelWindows() {
        val n = HtDemucs.STRIDE_SAMPLES * 3 + 12_345
        assertTrue(n > HtDemucs.SEGMENT_SAMPLES)
        assertIdentity("multi-window", sine(n, freq = 220.0))
    }

    @Test
    fun lastWindowShorterThanSegmentDoesNotEmitTrailingZeros() {
        val n = HtDemucs.STRIDE_SAMPLES + 100
        assertTrue(n < HtDemucs.SEGMENT_SAMPLES)
        val src = ramp(n)
        src.left[n - 1] = 0.73f
        src.right[n - 1] = -0.41f
        val drums = streamIdentity(src)
        assertEquals(n, drums.samples)
        assertEquals(0.73f, drums.left[n - 1], 1e-5f)
        assertEquals(-0.41f, drums.right[n - 1], 1e-5f)
        assertTrue("no leading silence from pad", abs(drums.left[0] - src.left[0]) < 1e-5f)
    }

    @Test
    fun chunkCountMatchesStrideCoverage() {
        val n = HtDemucs.STRIDE_SAMPLES * 2 + 50
        var seen = 0
        var lastTotal = 0
        val mixFile = tmp.newFile("count.wav")
        WavWriter(mixFile, HtDemucs.SAMPLE_RATE, channels = 2).use {
            it.writePlanarFloatStereo(FloatArray(n) { 0.01f }, FloatArray(n) { 0.01f })
        }
        val outDir = tmp.newFolder("stems-count")
        val writers = StemId.entries.associateWith { stem ->
            WavWriter(java.io.File(outDir, stem.fileName), HtDemucs.SAMPLE_RATE, channels = 2)
        }
        try {
            HtDemucs.streamOverlapAddIdentity(mixFile, writers) { index, total ->
                seen = index
                lastTotal = total
            }
        } finally {
            writers.values.forEach { it.close() }
        }
        val expect = maxOf(1, (n + HtDemucs.STRIDE_SAMPLES - 1) / HtDemucs.STRIDE_SAMPLES)
        assertEquals(expect, lastTotal)
        assertEquals(expect, seen)
        assertEquals(n, WavReader.frameCount(java.io.File(outDir, StemId.DRUMS.fileName)))
    }

    private fun assertIdentity(label: String, src: StereoPcm) {
        val drums = streamIdentity(src)
        assertEquals("$label frames", src.samples, drums.samples)
        var peak = 0f
        for (i in 0 until src.samples) {
            val el = abs(drums.left[i] - src.left[i])
            val er = abs(drums.right[i] - src.right[i])
            if (el > peak) peak = el
            if (er > peak) peak = er
            if (el > 1e-4f || er > 1e-4f) {
                throw AssertionError(
                    "$label sample $i left ${drums.left[i]} vs ${src.left[i]} " +
                        "right ${drums.right[i]} vs ${src.right[i]}",
                )
            }
        }
        assertTrue("$label peak err $peak", peak < 1e-4f)
    }

    private fun streamIdentity(src: StereoPcm): StereoPcm {
        val mixFile = tmp.newFile("mix-${src.samples}.wav")
        WavWriter(mixFile, src.sampleRate, channels = 2).use {
            it.writePlanarFloatStereo(src.left, src.right)
        }
        val outDir = tmp.newFolder("stems-${src.samples}")
        val files = StemId.entries.associateWith { stem ->
            java.io.File(outDir, stem.fileName)
        }
        val writers = files.mapValues { (_, file) ->
            WavWriter(file, HtDemucs.SAMPLE_RATE, channels = 2)
        }
        try {
            HtDemucs.streamOverlapAddIdentity(mixFile, writers)
        } finally {
            writers.values.forEach { it.close() }
        }
        val drums = WavReader.readStereo(files.getValue(StemId.DRUMS))
        val vocals = WavReader.readStereo(files.getValue(StemId.VOCALS))
        assertEquals(drums.samples, vocals.samples)
        assertEquals(drums.left[0], vocals.left[0], 1e-6f)
        return drums
    }

    private fun ramp(n: Int): StereoPcm {
        val left = FloatArray(n) { i -> i / 1000f }
        val right = FloatArray(n) { i -> -i / 1000f }
        return StereoPcm(HtDemucs.SAMPLE_RATE, left, right)
    }

    private fun sine(n: Int, freq: Double): StereoPcm {
        val left = FloatArray(n) { i ->
            (0.35 * sin(2.0 * Math.PI * freq * i / HtDemucs.SAMPLE_RATE)).toFloat()
        }
        val right = FloatArray(n) { i -> left[i] * 0.8f }
        return StereoPcm(HtDemucs.SAMPLE_RATE, left, right)
    }
}
