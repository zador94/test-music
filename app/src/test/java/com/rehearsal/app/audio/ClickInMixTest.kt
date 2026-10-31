package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.WavFixtures
import com.rehearsal.app.data.StemId
import com.rehearsal.app.player.SpeedStretch
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

class ClickInMixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun clickLandsAtExpectedOutputSampleAtSpeed07() {
        assertClickAtExpectedOutput(speed = 0.7f)
    }

    @Test
    fun clickLandsAtExpectedOutputSampleAtSpeed10() {
        assertClickAtExpectedOutput(speed = 1.0f)
    }

    @Test
    fun clickLandsAtExpectedOutputSampleAtSpeed15() {
        assertClickAtExpectedOutput(speed = 1.5f)
    }

    @Test
    fun clickWaveformIsUnstretchedAtNonUnitySpeeds() {
        val expected = MetronomeClickEngine.synthesizeClick(44100)
        for (speed in floatArrayOf(0.7f, 1.5f)) {
            val out = sinkPcm(silence(44_100), pts = 0L, speed = speed, clicks = true)
            val beatUs = 500_000L
            val inFrames = 44_100
            val outFrames = out.remaining() / 4
            val start = SpeedStretch.outputFrameForInput(
                BeatClock.frameOffset(beatUs, 0L, 44100),
                inFrames,
                outFrames,
            )
            val n = expected.size
            assertTrue("need the full unstretched click in the buffer at $speed", start + n < outFrames)
            val shorts = out.duplicate().order(out.order()).asShortBuffer()
            val mixed = FloatArray(n) { i -> shorts.get((start + i) * 2) / Short.MAX_VALUE.toFloat() }
            val want = FloatArray(n) { i ->
                MetronomeClickEngine.limit(expected[i] * MetronomeClickEngine.CLICK_VOLUME)
            }
            val corr = correlation(mixed, want)
            assertTrue("click at speed $speed must stay crisp, corr=$corr", corr > 0.98)
            assertTrue(
                "attack peak stays on the unstretched click, not smeared by WSOLA",
                abs(peakIndex(want) - peakIndex(mixed)) <= 2,
            )
        }
    }

    @Test
    fun seekPlacesNextClickOnAbsoluteBeat() {
        val engine = pcmEngine(enabled = true)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        pipe.softwareSpeed = 1f
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, silence(2048), 0L, sink))
        pipe.flush()
        sink.submitted.clear()
        val seekUs = 1_500_000L
        assertTrue(drain(pipe, silence(44_100), seekUs, sink))
        val out = sink.lastSubmittedAsBuffer()
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        val clickFrames = engine.clickSizeHint()
        val at15 = 0
        val at20 = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        assertTrue(
            "after seek to 1.5s the beat at 1.5s is frame 0, peak=${peakInRange(shorts, at15, at15 + clickFrames)}",
            peakInRange(shorts, at15, at15 + clickFrames) > 0.5,
        )
        assertTrue(
            "next absolute beat is 2.0s, not a new grid at seek",
            peakInRange(shorts, at20, at20 + clickFrames) > 0.5,
        )
        val tooEarly = peakInRange(shorts, clickFrames + 64, at20 - 64)
        assertTrue("no extra click between 1.5s and 2.0s, peak=$tooEarly", tooEarly < 0.05)
    }

    @Test
    fun mutingAllStemsStillHasClickInSinkPcm() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("mute-click")))
        StemId.entries.forEach { bank.setGain(it, 0f) }
        val engine = pcmEngine(enabled = true)
        val pipe = MetronomePcmPipe(engine, bank)
        pipe.configure(pcm16Format())
        val queued = renderBank(bank)
        assertTrue("muted stems are silence before mix", isSilent(queued))
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, queued.duplicate(), 0L, sink))
        val submitted = sink.lastSubmittedAsBuffer()
        assertFalse("muted stems must still submit the click", isSilent(submitted))
        assertTrue(peak(submitted) > 0.85)
        bank.clear()
    }

    @Test
    fun limiterDoesNotWrapFullScaleMusicPlusClick() {
        val engine = pcmEngine(enabled = true)
        val hot = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder())
        repeat(2048) {
            hot.putShort(Short.MAX_VALUE)
            hot.putShort(Short.MAX_VALUE)
        }
        hot.flip()
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        assertTrue(drain(pipe, hot, 0L, sink))
        val out = sink.lastSubmittedAsBuffer()
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        var wraps = 0
        var prev = 0
        for (i in 0 until shorts.limit()) {
            val s = shorts.get(i).toInt()
            assertTrue("limiter output in Short range", s in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
            if (i > 0 && prev > 20_000 && s < -20_000) wraps++
            prev = s
        }
        assertEquals("adding 2.2 click to full-scale must not wrap 16-bit", 0, wraps)
        assertTrue("click still punches through limited mix", peak(out) > 0.9)
    }

    @Test
    fun stretchAtSpeedOneIsBitExactPassthrough() {
        val src = sine(300)
        val view = src.duplicate()
        val stretched = SpeedStretch.timeStretch(
            view,
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()),
            speed = 1.0f,
            encoding = C.ENCODING_PCM_16BIT,
            channelCount = 2,
        )
        assertTrue("speed 1.0 must return the same buffer", stretched === view)
        assertTrue(buffersEqual(src.duplicate(), stretched))

        val engine = pcmEngine(enabled = false)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        pipe.softwareSpeed = 1f
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val music = sine(512)
        val original = snapshot(music)
        assertTrue(drain(pipe, music, 0L, sink))
        assertTrue(buffersEqual(original, sink.lastSubmittedAsBuffer()))
    }

    @Test
    fun aacMimeAtSinkStillMixesAudibleClick() {
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
        assertTrue("0.3.8 trap: AAC mime must still be treated as PCM at the sink", engine.isPcm())
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, silence(2048), 0L, sink))
        val submitted = sink.lastSubmittedAsBuffer()
        assertFalse("AAC mime still submits click PCM", isSilent(submitted))
        assertTrue(peak(submitted) > 0.5)
    }

    private fun assertClickAtExpectedOutput(speed: Float) {
        val inFrames = 44_100
        val out = sinkPcm(silence(inFrames), pts = 0L, speed = speed, clicks = true)
        val outFrames = out.remaining() / 4
        val expectedOut = SpeedStretch.outputFrameCount(inFrames, speed)
        assertEquals("stretched length at $speed", expectedOut * 4, out.remaining())
        val beatUs = 500_000L
        val start = SpeedStretch.outputFrameForInput(
            BeatClock.frameOffset(beatUs, 0L, 44100),
            inFrames,
            outFrames,
        )
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        val clickFrames = (0.006 * 44100).toInt().coerceAtLeast(32)
        val hit = peakInRange(shorts, start, start + clickFrames)
        assertTrue("click missing at speed $speed frame $start peak=$hit", hit > 0.5)
        val gapFrom = (start - 44100 / 8).coerceAtLeast(0)
        val gapTo = (start - 8).coerceAtLeast(gapFrom)
        if (gapTo > gapFrom) {
            val gap = peakInRange(shorts, gapFrom, gapTo)
            assertTrue("energy before expected click at $speed, peak=$gap", gap < 0.05)
        }
    }

    private fun sinkPcm(
        source: ByteBuffer,
        pts: Long,
        speed: Float,
        clicks: Boolean,
    ): ByteBuffer {
        val engine = pcmEngine(enabled = clicks)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16Format())
        pipe.softwareSpeed = speed
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        assertTrue(drain(pipe, source, pts, sink))
        return sink.lastSubmittedAsBuffer()
    }

    private fun pcmEngine(enabled: Boolean): MetronomeClickEngine {
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
        val frames = 2048
        val out = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        bank.render(out, C.ENCODING_PCM_16BIT, 2, 0L)
        out.position(0)
        out.limit(frames * 4)
        return out
    }

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        buf.limit(frames * 4)
        buf.position(0)
        return buf
    }

    private fun sine(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * 220.0 * i / 44100.0) * 0.25 * Short.MAX_VALUE).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
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

    private fun MetronomeClickEngine.clickSizeHint(): Int =
        (clickDurationUs() * sampleRate / 1_000_000L).toInt().coerceAtLeast(32)

    private fun peakIndex(wave: FloatArray): Int {
        var best = 0
        var bestAbs = 0f
        for (i in wave.indices) {
            val a = abs(wave[i])
            if (a > bestAbs) {
                bestAbs = a
                best = i
            }
        }
        return best
    }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na * nb)
        return if (denom < 1e-9) 0.0 else dot / denom
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

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
    }

    private fun isSilent(buffer: ByteBuffer): Boolean {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        for (i in 0 until shorts.limit()) {
            if (abs(shorts.get(i).toInt()) > 8) return false
        }
        return true
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
}

/**
 * Как DefaultAudioSink: один и тот же ByteBuffer, пока не съеден целиком.
 */
internal class IdentityAudioSink(var bytesPerAccept: Int) {
    private var pending: ByteBuffer? = null
    val submitted = ArrayList<ByteArray>()
    var drainHoldCount: Int = 0

    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val held = pending
        if (held != null && held !== buffer) {
            throw IllegalArgumentException(
                "DefaultAudioSink requires the same ByteBuffer until consumed",
            )
        }
        if (drainHoldCount > 0) {
            drainHoldCount--
            pending = buffer
            return false
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
