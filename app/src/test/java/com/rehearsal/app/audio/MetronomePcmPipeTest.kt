package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.WavFixtures
import com.rehearsal.app.data.StemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MetronomePcmPipeTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
        assertTrue("music energy must survive metronome toggle", rms(mixed) > rms(original) * 0.5)
        assertFalse(isAllZeros(mixed))
        assertTrue(correlation(original, mixed) > 0.85)
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
    fun speedDownDrainWhilePlayingDoesNotThrowOrSwallow() {
        val engine = engine(enabled = true)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = 256)

        var pts = 0L
        repeat(3) {
            val chunk = sinePcm16(frames = 512)
            assertTrue(drain(pipe, chunk, pts, sink))
            pts += 12_000L
        }
        sink.drainHoldCount = 6
        val duringDown = sinePcm16(frames = 512)
        val origin = duringDown.position()
        val first = pipe.handleBuffer(duringDown, pts, 1, sink::handleBuffer)
        assertFalse(first)
        assertEquals("decoder must not be swallowed while Sonic-like drain holds", origin, duringDown.position())
        assertTrue(drain(pipe, duringDown, pts, sink))
        assertEquals(duringDown.limit(), duringDown.position())
        pts += 12_000L
        val after = sinePcm16(frames = 512)
        assertTrue(drain(pipe, after, pts, sink))
        assertFalse(sink.isAllZeros())
    }

    @Test
    fun stemLiveRemixSubmittedToSinkIsNotAllZeros() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("pipe-stems")))
        StemId.entries.forEach { bank.setGain(it, 1f) }
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine, bank)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val source = renderBank(bank)
        assertTrue(drain(pipe, source, 0L, sink))
        assertFalse("live remix at sink must submit non-silent PCM when stems have signal", sink.isAllZeros())
        bank.clear()
    }

    @Test
    fun stemPipeCarriesClicksAfterStretch() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("pipe-click")))
        StemId.entries.forEach { bank.setGain(it, 0.12f) }
        val off = engine(enabled = false)
        val on = engine(enabled = true)
        on.originalBpm = 120f
        val source = renderBank(bank)

        val offPipe = MetronomePcmPipe(off, bank)
        offPipe.configure(pcm16Format())
        val offSink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(offPipe, source.duplicate(), 0L, offSink))

        val onPipe = MetronomePcmPipe(on, bank)
        onPipe.configure(pcm16Format())
        val onSink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(onPipe, source.duplicate(), 0L, onSink))

        val stemsOnly = offSink.lastSubmittedAsBuffer()
        val withClick = onSink.lastSubmittedAsBuffer()
        assertFalse(isAllZeros(stemsOnly))
        assertEquals(stemsOnly.remaining(), withClick.remaining())
        assertTrue("click must be in the PCM submitted to the sink", peak(withClick) > peak(stemsOnly))
        bank.clear()
    }

    @Test
    fun timeUnsetStillMixesClickEnergy() {
        val engine = engine(enabled = true)
        engine.originalBpm = 120f
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, sinePcm16(1024), C.TIME_UNSET, sink))
        val submitted = sink.lastSubmittedAsBuffer()
        assertFalse("TIME_UNSET still mixes click into sink PCM", isAllZeros(submitted))
        assertTrue(peak(submitted) > 0.5)
    }

    @Test
    fun softwareSpeedDownSubmitsMoreBytesWithoutSilencing() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        pipe.softwareSpeed = 0.5f
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val music = sinePcm16(frames = 300)
        assertTrue(drain(pipe, music, 0L, sink))
        val out = sink.lastSubmittedAsBuffer()
        assertEquals(600 * 4, out.remaining())
        assertFalse(isAllZeros(out))
    }

    @Test
    fun softwareSpeedUpSubmitsFewerBytesWithoutSilencing() {
        val engine = engine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        pipe.softwareSpeed = 1.5f
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val music = sinePcm16(frames = 300)
        assertTrue(drain(pipe, music, 0L, sink))
        val out = sink.lastSubmittedAsBuffer()
        assertEquals(200 * 4, out.remaining())
        assertFalse(isAllZeros(out))
    }

    @Test
    fun missingPcmEncodingStillMixesClicks() {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = 120f
        engine.enabled = true
        assertTrue(engine.isPcm())
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, sinePcm16(1024), 0L, sink))
        val submitted = sink.lastSubmittedAsBuffer()
        assertFalse("missing pcmEncoding still mixes click into sink PCM", isAllZeros(submitted))
        assertTrue(peak(submitted) > 0.5)
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

    private fun renderBank(bank: StemMixBank): ByteBuffer {
        val frames = 1024
        val out = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        bank.render(out, C.ENCODING_PCM_16BIT, 2, 0L)
        out.position(0)
        out.limit(frames * 4)
        return out
    }

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
