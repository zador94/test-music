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

class MetronomePcmPipeTest {
    @Test
    fun togglingEnabledWhilePlayingDoesNotReloadOrThrowOrRewind() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = 256)
        var mediaSourceReloads = 0
        var decoderRewinds = 0

        fun playChunk(pts: Long, frames: Int = 512) {
            val decoder = sinePcm16(frames)
            val originPos = decoder.position()
            val accepted = drain(pipe, decoder, pts, sink)
            assertTrue(accepted)
            if (decoder.position() < originPos) decoderRewinds++
            assertEquals("decoder buffer must be consumed, never rewound", decoder.limit(), decoder.position())
        }

        var pts = 0L
        repeat(4) {
            playChunk(pts)
            pts += 12_000L
        }
        assertEquals(0, mediaSourceReloads)

        engine.enabled = true
        // mid-play toggle: no load(), no flush of the pipe (that would be a media-source restart)
        repeat(6) {
            playChunk(pts)
            pts += 12_000L
        }

        assertEquals("enabling clicks must not reload MediaSource", 0, mediaSourceReloads)
        assertEquals(0, decoderRewinds)
        assertTrue(sink.submitted.isNotEmpty())
        assertFalse("music must not go silent when clicks turn on", sink.isAllZeros())
    }

    @Test
    fun mixPathDoesNotStopOrZeroAudioWhenClicksTurnOn() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)

        val music = sinePcm16(frames = 44100)
        val original = snapshot(music)
        drain(pipe, music.duplicate(), 0L, sink)
        engine.enabled = true
        val afterOn = sinePcm16(frames = 44100)
        drain(pipe, afterOn, 0L, sink)

        val mixed = sink.lastSubmittedAsBuffer()
        assertTrue(mixed.remaining() > 0)
        assertTrue("music energy must survive clicks", rms(mixed) > rms(original) * 0.5)
        assertFalse(isAllZeros(mixed))
        assertTrue(correlation(original, mixed) > 0.85)
        assertTrue(peak(mixed) > peak(original) + 0.15)
    }

    @Test
    fun retryKeepsSameMixBufferWhenToggleHappensMidConsume() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = 64)

        val decoder = sinePcm16(frames = 256)
        val posBefore = decoder.position()
        val first = pipe.handleBuffer(decoder, 48_000L, 1, sink::handleBuffer)
        assertFalse("partial consume", first)
        assertEquals("decoder must not move until mix is accepted", posBefore, decoder.position())

        engine.enabled = true
        val accepted = drain(pipe, decoder, 48_000L, sink)
        assertTrue(accepted)
        assertEquals(decoder.limit(), decoder.position())
    }

    @Test
    fun submittedBufferIsNeverTheDecoderBuffer() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val seen = mutableListOf<ByteBuffer>()
        val decoder = sinePcm16(frames = 128)
        pipe.handleBuffer(decoder, 0L, 1) { buffer, _, _ ->
            seen.add(buffer)
            buffer.position(buffer.limit())
            true
        }
        assertEquals(1, seen.size)
        assertTrue(seen[0] !== decoder)
        engine.enabled = true
        val decoder2 = sinePcm16(frames = 128)
        pipe.handleBuffer(decoder2, 8_000L, 1) { buffer, _, _ ->
            seen.add(buffer)
            buffer.position(buffer.limit())
            true
        }
        assertTrue("toggle must not start passing the decoder buffer", seen[1] !== decoder2)
    }

    private fun drain(
        pipe: MetronomePcmPipe,
        source: ByteBuffer,
        pts: Long,
        sink: IdentityAudioSink,
    ): Boolean {
        var guard = 0
        var accepted = false
        while (guard < 10_000) {
            accepted = pipe.handleBuffer(source, pts, 1, sink::handleBuffer)
            if (accepted) return true
            guard++
        }
        return accepted
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

    private fun sinePcm16(frames: Int): ByteBuffer {
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

    private fun rms(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var sum = 0.0
        val n = shorts.limit()
        if (n == 0) return 0.0
        for (i in 0 until n) {
            val x = shorts.get(i) / 32768.0
            sum += x * x
        }
        return sqrt(sum / n)
    }

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
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

    private fun isAllZeros(buffer: ByteBuffer): Boolean {
        val dup = buffer.asReadOnlyBuffer()
        while (dup.hasRemaining()) {
            if (dup.get() != 0.toByte()) return false
        }
        return true
    }
}

/**
 * Как DefaultAudioSink: один и тот же ByteBuffer, пока не съеден целиком.
 * Другой инстанс → IllegalArgumentException (стоп плеера в 0.3.1).
 */
private class IdentityAudioSink(var bytesPerAccept: Int) {
    private var pending: ByteBuffer? = null
    val submitted = ArrayList<ByteArray>()

    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val held = pending
        if (held != null && held !== buffer) {
            throw IllegalArgumentException(
                "DefaultAudioSink requires the same ByteBuffer until consumed",
            )
        }
        pending = buffer
        val consume = minOf(buffer.remaining(), bytesPerAccept)
        val copy = ByteArray(consume)
        buffer.get(copy)
        submitted.add(copy)
        val done = !buffer.hasRemaining()
        if (done) pending = null
        return done
    }

    fun isAllZeros(): Boolean = submitted.all { chunk -> chunk.all { it == 0.toByte() } }

    fun lastSubmittedAsBuffer(): ByteBuffer {
        val all = submitted.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
    }
}
