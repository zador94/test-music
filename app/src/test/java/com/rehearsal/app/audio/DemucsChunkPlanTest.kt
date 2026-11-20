package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class DemucsChunkPlanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun streamWindowsAreTheModelSegment() {
        assertEquals(HtDemucs.SEGMENT_SAMPLES, DemucsChunkPlan.MIN_CHUNK_SAMPLES)
        assertEquals(7.8, DemucsChunkPlan.MIN_CHUNK_SAMPLES / 44100.0, 0.05)
        assertEquals(HtDemucs.SEGMENT_SAMPLES / 4, HtDemucs.OVERLAP_SAMPLES)
        assertEquals(HtDemucs.SEGMENT_SAMPLES - HtDemucs.OVERLAP_SAMPLES, HtDemucs.STRIDE_SAMPLES)
        assertTrue(HtDemucs.STRIDE_SAMPLES > HtDemucs.OVERLAP_SAMPLES)
    }

    @Test
    fun memoryFailureDetectsOomAndAllocMessages() {
        assertTrue(DemucsChunkPlan.isMemoryFailure(OutOfMemoryError("Java heap space")))
        assertTrue(DemucsChunkPlan.isMemoryFailure(RuntimeException("Failed to allocate 1GB")))
        assertFalse(DemucsChunkPlan.isMemoryFailure(IllegalStateException("bad tensor")))
        assertEquals(
            "Не хватило RAM для HT-Demucs. Попробуйте более короткий трек.",
            DemucsChunkPlan.SHORT_TRACK_MESSAGE,
        )
    }

    @Test
    fun wavRangeReadsOnlyRequestedFrames() {
        val n = 20_000
        val left = FloatArray(n) { i -> i / 1000f }
        val right = FloatArray(n) { i -> -i / 1000f }
        val file = tmp.newFile("slice.wav")
        WavWriter(file, 44100, channels = 2).use { it.writePlanarFloatStereo(left, right) }
        val slice = WavReader.readStereoRange(file, 5000, 128)
        assertEquals(128, slice.samples)
        assertEquals(left[5000], slice.left[0], 1e-5f)
        assertEquals(right[5127], slice.right[127], 1e-5f)
        assertEquals(n, WavReader.frameCount(file))
    }

    @Test
    fun resampleWavDoublesLengthFrom22050() {
        val inRate = 22050
        val seconds = 1
        val n = inRate * seconds
        val left = FloatArray(n) { 0.2f }
        val right = FloatArray(n) { -0.1f }
        val src = tmp.newFile("in.wav")
        val dest = tmp.newFile("out.wav")
        WavWriter(src, inRate, channels = 2).use { it.writePlanarFloatStereo(left, right) }
        AudioBuffers.resampleWavTo44100(src, dest)
        val out = WavReader.readStereo(dest)
        assertEquals(44100, out.sampleRate)
        assertEquals(44100, out.samples)
        assertTrue(abs(out.left[1000] - 0.2f) < 0.02f)
        assertTrue(abs(out.right[1000] + 0.1f) < 0.02f)
    }
}
