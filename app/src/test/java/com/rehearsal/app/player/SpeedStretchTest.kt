package com.rehearsal.app.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeedStretchTest {
    @Test
    fun audioTrackSpeedNeverExceedsOne() {
        assertEquals(0.5f, SpeedStretch.audioTrackSpeed(0.5f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(1f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(1.5f), 1e-5f)
        assertEquals(1f, SpeedStretch.audioTrackSpeed(2f), 1e-5f)
    }

    @Test
    fun softwareSpeedOnlyAboveOne() {
        assertEquals(1f, SpeedStretch.softwareSpeed(0.5f), 1e-5f)
        assertEquals(1f, SpeedStretch.softwareSpeed(1f), 1e-5f)
        assertEquals(1.5f, SpeedStretch.softwareSpeed(1.5f), 1e-5f)
    }

    @Test
    fun decimateSpeedsUpByDroppingFrames() {
        val frames = 300
        val src = sine(frames)
        val out = SpeedStretch.decimate(
            src.duplicate(),
            ByteBuffer.allocateDirect(0),
            speed = 1.5f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertEquals(200 * 4, out.remaining())
        assertTrue(out.remaining() < src.remaining())
        assertFalse("speed-up must keep stem energy", isAllZeros(out))
    }

    @Test
    fun speedDownDoesNotDecimate() {
        val src = sine(128)
        val view = src.duplicate()
        val out = SpeedStretch.decimate(
            view,
            ByteBuffer.allocateDirect(0),
            speed = 0.5f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertEquals(src.remaining(), out.remaining())
        assertTrue("speed ≤ 1 must pass the same buffer to AudioTrack", out === view)
    }

    private fun sine(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 44100.0) * 0.4 * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
    }

    private fun isAllZeros(buffer: ByteBuffer): Boolean {
        val dup = buffer.asReadOnlyBuffer()
        while (dup.hasRemaining()) {
            if (dup.get() != 0.toByte()) return false
        }
        return true
    }
}
