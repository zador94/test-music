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
import kotlin.math.sin
import kotlin.math.sqrt

class MetronomeClickEngineTest {
    @Test
    fun disabledMixLeavesPcmUnchanged() {
        val engine = engine(enabled = false)
        val src = sinePcm16()
        val before = snapshot(src)
        val dest = ByteBuffer.allocateDirect(0)
        val out = ClickMix.copyAndMix(src, dest, engine, 0L)
        assertTrue(buffersEqual(before, out))
    }

    @Test
    fun enabledMixDoesNotJackhammerMusic() {
        val engine = engine(enabled = true)
        val src = sinePcm16(frames = 44100)
        val original = snapshot(src)
        val out = ClickMix.copyAndMix(src, ByteBuffer.allocateDirect(0), engine, 0L)
        assertFalse(isJackhammer(out))
        assertTrue(
            "clicks may add peaks but music must still correlate",
            correlation(original, out) > 0.85,
        )
        assertTrue("click should be audible over the 0.25 sine", peak(out) > peak(original) + 0.2)
        assertTrue(peak(out) <= 1.0)
    }

    @Test
    fun togglingEnabledThenSwitchingLayoutDoesNotFillGarbage() {
        val engine = engine(enabled = true)
        val music = sinePcm16()
        val withClicks = ClickMix.copyAndMix(music, ByteBuffer.allocateDirect(0), engine, 0L)
        assertFalse(isJackhammer(withClicks))

        engine.enabled = false
        engine.onFlush()
        val off = ClickMix.copyAndMix(music, ByteBuffer.allocateDirect(0), engine, 0L)
        assertTrue(buffersEqual(snapshot(music), off))

        engine.configure(pcm16Format())
        engine.enabled = true
        engine.onFlush()
        val stemsLike = ClickMix.copyAndMix(music, ByteBuffer.allocateDirect(0), engine, 48_000L)
        assertFalse("tab switch with metronome on produced jackhammer PCM", isJackhammer(stemsLike))
        assertTrue(correlation(snapshot(music), stemsLike) > 0.85)
    }

    @Test
    fun beatTimestampsAlignToMediaTimeAtOriginalBpm() {
        val engine = engine(enabled = true)
        engine.originalBpm = 120f
        val sampleRate = 44100
        val intervalUs = BeatClock.intervalUs(120f)
        assertEquals(500_000L, intervalUs)

        val frames = sampleRate * 2
        val out = ClickMix.copyAndMix(silencePcm16(frames), ByteBuffer.allocateDirect(0), engine, 0L)
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        val clickFrames = (engine.clickDurationUs() * sampleRate / 1_000_000L).toInt().coerceAtLeast(48)
        val expected = listOf(0L, 500_000L, 1_000_000L, 1_500_000L)
        expected.forEach { beatUs ->
            val start = BeatClock.mediaUsToSample(beatUs, sampleRate).toInt()
            val clickPeak = peakInRange(shorts, start, start + clickFrames)
            assertTrue("click missing at media beat $beatUs (peak=$clickPeak)", clickPeak > 0.5)
            val gapStart = start - sampleRate / 8
            val gapEnd = start - 8
            if (gapStart >= 0 && gapEnd > gapStart) {
                val gapPeak = peakInRange(shorts, gapStart, gapEnd)
                assertTrue("energy before beat $beatUs, peak=$gapPeak", gapPeak < 0.05)
            }
        }
    }

    @Test
    fun chunkedMixStaysOnTheSameBeatGrid() {
        val engine = engine(enabled = true)
        engine.originalBpm = 90f
        val sampleRate = 44100
        val frames = sampleRate
        val chunkFrames = 512
        val whole = ClickMix.copyAndMix(silencePcm16(frames), ByteBuffer.allocateDirect(0), engine, 0L)

        val concat = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        var dest = ByteBuffer.allocateDirect(0)
        while (offset < frames) {
            val n = minOf(chunkFrames, frames - offset)
            val pts = BeatClock.sampleToMediaUs(offset.toLong(), sampleRate)
            val chunk = ClickMix.copyAndMix(silencePcm16(n), dest, engine, pts)
            dest = chunk
            concat.put(chunk.asReadOnlyBuffer())
            offset += n
        }
        concat.flip()
        assertTrue(
            "chunk boundaries must not double-click or drop beats",
            correlation(whole, concat) > 0.99,
        )
        val shorts = concat.duplicate().order(concat.order()).asShortBuffer()
        val clickFrames = (engine.clickDurationUs() * sampleRate / 1_000_000L).toInt().coerceAtLeast(48)
        val interval = BeatClock.intervalUs(90f)
        var beatUs = 0L
        while (beatUs < 1_000_000L) {
            val start = BeatClock.mediaUsToSample(beatUs, sampleRate).toInt()
            if (start + clickFrames < frames) {
                val peak = peakInRange(shorts, start, start + clickFrames)
                assertTrue("chunked mix missed originalBpm beat $beatUs", peak > 0.5)
            }
            beatUs += interval
        }
    }

    @Test
    fun nonPcmConfigureDoesNotTreatBufferAsPcm() {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.enabled = true
        assertFalse(engine.isPcm())
        val src = sinePcm16()
        val before = snapshot(src)
        val out = ClickMix.copyAndMix(src, ByteBuffer.allocateDirect(0), engine, 0L)
        assertTrue(
            "non-PCM must copy, not mix encoded bytes as samples",
            buffersEqual(before, out),
        )
    }

    private fun engine(enabled: Boolean): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(pcm16Format())
        engine.originalBpm = 120f
        engine.enabled = enabled
        return engine
    }

    private fun pcm16Format(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(44100)
        .setChannelCount(2)
        .build()

    private fun silencePcm16(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.limit(frames * 4)
        buf.position(0)
        return buf
    }

    private fun peakInRange(shorts: java.nio.ShortBuffer, startFrame: Int, endFrame: Int): Double {
        val frames = shorts.limit() / 2
        val from = startFrame.coerceIn(0, frames)
        val to = endFrame.coerceIn(from, frames)
        var max = 0
        for (i in from until to) {
            max = maxOf(max, abs(shorts.get(i * 2).toInt()), abs(shorts.get(i * 2 + 1).toInt()))
        }
        return max / 32768.0
    }

    private fun sinePcm16(frames: Int = 2048): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * 220.0 * i / 44100.0) * 0.25 * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
    }

    private fun snapshot(buffer: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocate(buffer.remaining()).order(buffer.order())
        copy.put(buffer.asReadOnlyBuffer())
        copy.flip()
        return copy
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

    private fun isJackhammer(buffer: ByteBuffer): Boolean {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var clips = 0
        var flips = 0
        var prev = 0
        val n = shorts.limit()
        for (i in 0 until n) {
            val s = shorts.get(i).toInt()
            if (abs(s) >= 32000) clips++
            if (i > 0 && abs(s - prev) > 50000) flips++
            prev = s
        }
        return clips > n / 8 || flips > n / 10
    }

    private fun correlation(a: ByteBuffer, b: ByteBuffer): Double {
        val sa = a.duplicate().order(a.order()).asShortBuffer()
        val sb = b.duplicate().order(b.order()).asShortBuffer()
        val n = minOf(sa.limit(), sb.limit())
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            val x = sa.get(i) / 32768.0
            val y = sb.get(i) / 32768.0
            dot += x * y
            na += x * x
            nb += y * y
        }
        val denom = sqrt(na * nb)
        return if (denom < 1e-9) 0.0 else dot / denom
    }

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
    }
}
