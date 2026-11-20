package com.rehearsal.app.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class SpeedStretchTest {
    @Test
    fun audioTrackSpeedIsAlwaysOne() {
        assertEquals(1f, SpeedStretch.audioTrackSpeed(0.5f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(1f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(1.5f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(2f), 1e-5f)
    }

    @Test
    fun softwareSpeedCoversSlowDownAndSpeedUp() {
        assertEquals(0.5f, SpeedStretch.softwareSpeed(0.5f), 1e-5f)
        assertEquals(0.7f, SpeedStretch.softwareSpeed(0.7f), 1e-5f)
        assertEquals(1f, SpeedStretch.softwareSpeed(1f), 1e-5f)
        assertEquals(1.5f, SpeedStretch.softwareSpeed(1.5f), 1e-5f)
    }

    @Test
    fun speedOneIsBitExactPassthrough() {
        val src = sine(128)
        val view = src.duplicate()
        val out = SpeedStretch.timeStretch(
            view,
            ByteBuffer.allocateDirect(0),
            speed = 1.0f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertTrue(out === view)
        assertEquals(src.remaining(), out.remaining())
    }

    @Test
    fun timeStretchSpeedsUpWithoutDroppingSamplesIntoHelium() {
        val hz = 220.0
        val src = sine(frames = 44100, hz = hz)
        val stretched = SpeedStretch.timeStretch(
            src.duplicate(),
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = 1.5f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        val dropped = SpeedStretch.dropSample(
            src.duplicate(),
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = 1.5f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertEquals(29400 * 4, stretched.remaining())
        assertTrue(stretched.remaining() < src.remaining())
        assertFalse("speed-up must keep stem energy", isAllZeros(stretched))

        val originalPitch = pitchHz(src, 44100)
        val stretchPitch = pitchHz(stretched, 44100)
        val dropPitch = pitchHz(dropped, 44100)
        assertEquals("fixture 220 Hz, was $originalPitch", hz, originalPitch, 8.0)
        assertTrue(
            "drop-sample is the helium bug: pitch $dropPitch should rise toward 330",
            dropPitch > hz * 1.25,
        )
        assertTrue(
            "WSOLA speed-up must keep pitch near 220, not helium (was $stretchPitch, drop=$dropPitch)",
            abs(stretchPitch - hz) < abs(stretchPitch - hz * 1.5),
        )
        assertEquals("time-stretch pitch $stretchPitch", hz, stretchPitch, 25.0)
        assertFalse(
            "time-stretch must not be drop-sample resample",
            buffersEqual(stretched, dropped),
        )
    }

    @Test
    fun speedDownExpandsInSoftwareWithoutHelium() {
        val hz = 220.0
        val src = sine(frames = 44100, hz = hz)
        val stretched = SpeedStretch.timeStretch(
            src.duplicate(),
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = 0.5f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertEquals(88200 * 4, stretched.remaining())
        assertTrue(stretched.remaining() > src.remaining())
        assertFalse(isAllZeros(stretched))
        val stretchPitch = pitchHz(stretched, 44100)
        assertEquals("WSOLA slow-down must keep pitch near 220, was $stretchPitch", hz, stretchPitch, 25.0)
    }

    @Test
    fun slowDownKeepsSineLevelAndDoesNotClickOnChunkSeams() {
        val hz = 180.0
        val chunkFrames = 1024
        val chunks = 8
        val speed = 0.75f
        val src = sine(frames = chunkFrames * chunks, hz = hz)
        val whole = SpeedStretch.timeStretch(
            src.duplicate(),
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = speed,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        val inRms = rms(src)
        val outRms = rms(whole)
        assertTrue(
            "slow-down cancelled the wave: in=$inRms out=$outRms",
            outRms > inRms * 0.6,
        )
        val floor = minWindowRms(whole, window = 512)
        assertTrue(
            "a stretch window dropped out: floor=$floor global=$outRms",
            floor > outRms * 0.45,
        )

        var prev = 0
        var worstJump = 0
        for (c in 0 until chunks) {
            val slice = sliceFrames(src, c * chunkFrames, chunkFrames)
            val out = SpeedStretch.timeStretch(
                slice,
                ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
                speed = speed,
                encoding = C.ENCODING_PCM_16BIT,
                channelCount = 2,
            )
            val first = leftSample(out, 0)
            if (c > 0) worstJump = maxOf(worstJump, abs(first - prev))
            prev = leftSample(out, out.remaining() / 4 - 1)
            assertTrue(
                "chunk $c has a silent hole, rms=${rms(out)}",
                rms(out) > inRms * 0.45,
            )
        }
        val fullScale = (0.4 * Short.MAX_VALUE).toInt()
        assertTrue(
            "chunk seam clicks, jump=$worstJump",
            worstJump < fullScale / 3,
        )
    }

    @Test
    fun slowDownKeepsLowSineFromCombing() {
        val hz = 90.0
        val src = sine(frames = 8192, hz = hz)
        val out = SpeedStretch.timeStretch(
            src.duplicate(),
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = 0.6f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        val inRms = rms(src)
        val outRms = rms(out)
        assertTrue("90 Hz was combed away: in=$inRms out=$outRms", outRms > inRms * 0.55)
        val pitch = pitchHzInBand(out, 44100, minHz = 50.0, maxHz = 180.0)
        assertEquals("low sine pitch drifted to $pitch", hz, pitch, 12.0)
    }

    private fun sine(frames: Int, hz: Double = 220.0): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * hz * i / 44100.0) * 0.4 * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
    }

    /**
     * Autocorr of the left channel. Range 150–500 Hz so 220 stays 220 and helium 330
     * is not confused with a 110 Hz subharmonic.
     */
    private fun pitchHzInBand(buffer: ByteBuffer, sampleRate: Int, minHz: Double, maxHz: Double): Double {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val pos = view.position()
        val frames = view.remaining() / 4
        if (frames < 64) return 0.0
        val skip = minOf(1024, frames / 8)
        val n = frames - skip
        val x = DoubleArray(n) { i -> view.getShort(pos + (skip + i) * 4).toDouble() }
        val mean = x.average()
        for (i in x.indices) x[i] -= mean
        val minLag = (sampleRate / maxHz).roundToInt().coerceAtLeast(2)
        val maxLag = (sampleRate / minHz).roundToInt().coerceAtMost(n / 3)
        if (maxLag <= minLag) return 0.0
        var bestLag = minLag
        var best = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var acc = 0.0
            val count = n - lag
            for (i in 0 until count) acc += x[i] * x[i + lag]
            acc /= count
            if (acc > best) {
                best = acc
                bestLag = lag
            }
        }
        return sampleRate.toDouble() / bestLag
    }

    private fun pitchHz(buffer: ByteBuffer, sampleRate: Int): Double {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val pos = view.position()
        val frames = view.remaining() / 4
        if (frames < 64) return 0.0
        val skip = minOf(1024, frames / 8)
        val n = frames - skip
        val x = DoubleArray(n) { i -> view.getShort(pos + (skip + i) * 4).toDouble() }
        val mean = x.average()
        for (i in x.indices) x[i] -= mean
        val minLag = (sampleRate / 500.0).roundToInt().coerceAtLeast(2)
        val maxLag = (sampleRate / 150.0).roundToInt().coerceAtMost(n / 3)
        var bestLag = minLag
        var best = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var acc = 0.0
            val count = n - lag
            for (i in 0 until count) {
                acc += x[i] * x[i + lag]
            }
            acc /= count
            if (acc > best) {
                best = acc
                bestLag = lag
            }
        }
        return sampleRate.toDouble() / bestLag
    }

    private fun buffersEqual(a: ByteBuffer, b: ByteBuffer): Boolean {
        if (a.remaining() != b.remaining()) return false
        val aa = a.asReadOnlyBuffer()
        val bb = b.asReadOnlyBuffer()
        while (aa.hasRemaining()) {
            if (aa.get() != bb.get()) return false
        }
        return true
    }

    private fun sliceFrames(source: ByteBuffer, frame: Int, frames: Int): ByteBuffer {
        val bytes = frames * 4
        val start = frame * 4
        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val view = source.duplicate().order(ByteOrder.nativeOrder())
        view.position(start)
        view.limit(start + bytes)
        out.put(view)
        out.flip()
        return out
    }

    private fun leftSample(buffer: ByteBuffer, frame: Int): Int {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        return view.getShort(view.position() + frame * 4).toInt()
    }

    private fun rms(buffer: ByteBuffer): Double {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = view.remaining() / 4
        if (frames <= 0) return 0.0
        var acc = 0.0
        for (i in 0 until frames) {
            val s = view.getShort().toDouble()
            view.getShort()
            acc += s * s
        }
        return kotlin.math.sqrt(acc / frames)
    }

    private fun minWindowRms(buffer: ByteBuffer, window: Int): Double {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = view.remaining() / 4
        if (frames < window) return rms(buffer)
        val left = DoubleArray(frames) { view.getShort(view.position() + it * 4).toDouble() }
        var worst = Double.POSITIVE_INFINITY
        var acc = 0.0
        for (i in 0 until window) acc += left[i] * left[i]
        worst = kotlin.math.sqrt(acc / window)
        for (i in window until frames) {
            acc += left[i] * left[i]
            acc -= left[i - window] * left[i - window]
            val value = kotlin.math.sqrt(acc / window)
            if (value < worst) worst = value
        }
        return worst
    }

    private fun isAllZeros(buffer: ByteBuffer): Boolean {
        val dup = buffer.asReadOnlyBuffer()
        while (dup.hasRemaining()) {
            if (dup.get() != 0.toByte()) return false
        }
        return true
    }
}
