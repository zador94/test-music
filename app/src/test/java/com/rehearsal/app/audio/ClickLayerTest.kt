package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ClickLayerTest {
    @Test
    fun mutedStemsStillHaveClickEnergy() {
        val engine = engine()
        val silence = silence(44100)
        val layer = layer(engine, silence, 0L)
        assertTrue("click layer must not be all zeros when stems are muted", peak(layer) > 0.85)
        assertFalse(isSilent(layer))
    }

    @Test
    fun disabledLayerIsSilence() {
        val engine = engine()
        engine.enabled = false
        assertTrue(isSilent(layer(engine, silence(2048), 0L)))
    }

    @Test
    fun emptyAnalysisClicksOnOriginalBpmGrid() {
        val engine = engine()
        engine.setAnalysis(null)
        val out = layer(engine, silence(44100), 0L)
        assertFalse("BeatClock grid must click without analysis", isSilent(out))
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt()
        assertTrue(peakInRange(out, 0, clickFrames) > 0.85)
    }

    @Test
    fun everyBeatUsesTheSameClick() {
        val engine = engine()
        val out = layer(engine, silence(44100), 0L)
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt()
        val first = peakInRange(out, 0, clickFrames)
        val offStart = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        val second = peakInRange(out, offStart, offStart + clickFrames)
        assertTrue("first click peak=$first", first > 0.85)
        assertTrue("second click peak=$second", second > 0.85)
        assertEquals("uniform click, no downbeat accent", first, second, 0.02)
        assertEquals(
            MetronomeClickEngine.synthesizeClick(44100).size.toLong(),
            (MetronomeClickEngine.CLICK_DURATION_SEC * 44100).toLong().coerceAtLeast(32),
        )
        assertEquals(1f, MetronomeClickEngine.CLICK_GAIN, 1e-4f)
        assertEquals(2.2f, MetronomeClickEngine.CLICK_VOLUME, 1e-4f)
    }

    @Test
    fun aacMimeStillMixesPcmClick() {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = 120f
        engine.enabled = true
        assertTrue("0.3.8: AAC mime at the sink is still decoded PCM", engine.isPcm())
        val out = layer(engine, silence(2048), 0L)
        assertFalse(isSilent(out))
        assertTrue(peak(out) > 0.85)
    }

    @Test
    fun timeUnsetPlayheadDoesNotPaintZeroGrid() {
        val engine = engine()
        val out = layer(engine, silence(2048), C.TIME_UNSET)
        assertTrue("TIME_UNSET is not t=0; do not paint the start grid", isSilent(out))
    }

    @Test
    fun speedUpCompressesBeatSpacingInWallBuffer() {
        val engine = engine()
        val one = layer(engine, silence(44100), 0L, speed = 1f)
        val fast = layer(engine, silence(44100), 0L, speed = 2f)
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt()
        val at250 = BeatClock.mediaUsToSample(250_000L, 44100).toInt()
        assertTrue("1×: beat at 500ms, not 250ms", peakInRange(one, at250, at250 + 64) < 0.05)
        assertTrue("2×: 500ms media lands at 250ms wall", peakInRange(fast, at250, at250 + clickFrames) > 0.5)
    }

    private fun engine(): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.originalBpm = 120f
        engine.enabled = true
        return engine
    }

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        buf.limit(frames * 4)
        buf.position(0)
        return buf
    }

    private fun layer(
        engine: MetronomeClickEngine,
        dest: ByteBuffer,
        mediaStartUs: Long,
        speed: Float = 1f,
    ): ByteBuffer {
        ClickLayer.render(dest, engine, mediaStartUs, speed)
        dest.position(0)
        dest.limit(dest.capacity())
        return dest
    }

    private fun isSilent(buffer: ByteBuffer): Boolean {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        for (i in 0 until shorts.limit()) {
            if (abs(shorts.get(i).toInt()) > 8) return false
        }
        return true
    }

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
    }

    private fun peakInRange(buffer: ByteBuffer, startFrame: Int, endFrame: Int): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val from = startFrame.coerceIn(0, frames)
        val to = endFrame.coerceIn(from, frames)
        var max = 0
        for (i in from until to) {
            max = maxOf(max, abs(shorts.get(i * 2).toInt()), abs(shorts.get(i * 2 + 1).toInt()))
        }
        return max / 32768.0
    }
}
