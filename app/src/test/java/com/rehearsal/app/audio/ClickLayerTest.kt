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
        engine.setBeatTimesUs(longArrayOf(0L, 500_000L))
        val silence = silence(44100)
        val layer = layer(engine, silence, 0L)
        assertTrue("click layer must not be all zeros when stems are muted", peak(layer) > 0.85)
        assertFalse(isSilent(layer))
    }

    @Test
    fun disabledLayerIsSilence() {
        val engine = engine()
        engine.enabled = false
        engine.setBeatTimesUs(longArrayOf(0L))
        assertTrue(isSilent(layer(engine, silence(2048), 0L)))
    }

    @Test
    fun emptyBeatsFallsBackToGrid() {
        val engine = engine()
        engine.setBeatTimesUs(longArrayOf())
        val out = layer(engine, silence(44100), 0L)
        assertFalse("BeatClock grid must click without analysis", isSilent(out))
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt()
        assertTrue(peakInRange(out, 0, clickFrames) > 0.85)
    }

    @Test
    fun downbeatIsLouderAndLongerThanOtherBeats() {
        val engine = engine()
        engine.setBeatTimesUs(longArrayOf(0L, 500_000L))
        val out = layer(engine, silence(44100), 0L)
        val downMs = (0.028 * 44100).toInt()
        val beatMs = (0.012 * 44100).toInt()
        val downPeak = peakInRange(out, 0, downMs)
        val offStart = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        val offPeak = peakInRange(out, offStart, offStart + beatMs)
        assertTrue("downbeat ~2.2 must punch, peak=$downPeak", downPeak > 0.95)
        assertTrue("other beat still audible, peak=$offPeak", offPeak > 0.5)
        assertTrue(downPeak >= offPeak)
        assertEquals(2.2f, MetronomeClickEngine.DOWNBEAT_GAIN, 1e-4f)
        assertTrue(MetronomeClickEngine.BEAT_GAIN < MetronomeClickEngine.DOWNBEAT_GAIN)
        assertTrue(MetronomeClickEngine.synthesizeDownbeat(44100).size >
            MetronomeClickEngine.synthesizeBeat(44100).size)
    }

    @Test
    fun layerDoesNotDependOnSinkMime() {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(48000)
                .setChannelCount(1)
                .build(),
        )
        engine.originalBpm = 120f
        engine.enabled = true
        engine.setBeatTimesUs(longArrayOf(0L))
        val out = layer(engine, silence(2048), 0L)
        assertFalse("click layer is its own 44.1 stereo track, not sink mime", isSilent(out))
        assertTrue(peak(out) > 0.85)
    }

    @Test
    fun timeUnsetPlayheadStillClicks() {
        val engine = engine()
        engine.setBeatTimesUs(longArrayOf(0L, 500_000L))
        val out = layer(engine, silence(2048), C.TIME_UNSET)
        assertFalse(isSilent(out))
        assertTrue(peak(out) > 0.85)
    }

    @Test
    fun lookaheadChunkIsAbout120ms() {
        val frames = ClickLayer.chunkFrames()
        val sec = frames.toDouble() / ClickLayer.SAMPLE_RATE
        assertTrue("web lookahead 0.12s wall, was $sec", sec in 0.10..0.15)
        assertEquals(0.12f, ClickLayer.LOOKAHEAD_SEC, 1e-4f)
        assertTrue(ClickLayer.lookaheadUs(1.5f) > ClickLayer.lookaheadUs(1f))
        assertTrue(
            "media window scales with playbackRate",
            ClickLayer.mediaAdvanceUs(2f) > ClickLayer.mediaAdvanceUs(1f),
        )
        assertTrue(ClickLayer.mediaAdvanceUs(1f) in 100_000L..150_000L)
    }

    @Test
    fun speedUpCompressesBeatSpacingInWallBuffer() {
        val engine = engine()
        engine.setBeatTimesUs(longArrayOf(0L, 500_000L))
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
