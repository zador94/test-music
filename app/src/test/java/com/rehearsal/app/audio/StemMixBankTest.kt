package com.rehearsal.app.audio

import androidx.media3.common.C
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
import kotlin.math.sqrt

class StemMixBankTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun renderWithFourFloatWavsIsNotSilence() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("stems")))
        val out = render(bank)
        assertFalse("silent-stems bug: mix of four non-zero WAVs was all zeros", isSilent(out))
        assertTrue(rms(out) > 0.05)
        bank.clear()
    }

    @Test
    fun muteVocalsZeroesOnlyVocalsGain() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("stems")))
        StemId.entries.forEach { bank.setGain(it, 1f) }
        val full = rms(render(bank))

        bank.setGain(StemId.VOCALS, 0f)
        assertEquals(0f, bank.gain(StemId.VOCALS), 1e-6f)
        assertEquals(1f, bank.gain(StemId.DRUMS), 1e-6f)
        assertEquals(1f, bank.gain(StemId.BASS), 1e-6f)
        assertEquals(1f, bank.gain(StemId.OTHER), 1e-6f)

        val muted = render(bank)
        assertFalse("mute vocals must not silence the whole mix", isSilent(muted))
        assertTrue("mute vocals should drop energy", rms(muted) < full * 0.9)
        bank.clear()
    }

    @Test
    fun muteWithoutLiveRemixLeavesQueuedChunkUnchanged() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("queued")))
        val queued = render(bank)
        val before = rms(queued)
        bank.setGain(StemId.VOCALS, 0f)
        val engine = MetronomeClickEngine()
        engine.enabled = false
        val stale = ClickMix.copyAndMix(queued.duplicate(), ByteBuffer.allocateDirect(0), engine, 0L)
        assertEquals("MediaSource queue keeps old gains until sink remix", before, rms(stale), 1e-4)
        bank.clear()
    }

    @Test
    fun liveRemixAppliesMuteOnTheSameQueuedBuffer() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("live")))
        StemId.entries.forEach { bank.setGain(it, 1f) }
        val queued = render(bank)
        val before = rms(queued)
        bank.setGain(StemId.VOCALS, 0f)
        val engine = MetronomeClickEngine()
        engine.configure(
            androidx.media3.common.Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.enabled = false
        val live = ClickMix.copyAndMix(
            queued.duplicate(),
            ByteBuffer.allocateDirect(0),
            engine,
            0L,
            bank,
        )
        assertTrue("live remix must apply mute on this buffer, not after 1s", rms(live) < before * 0.9)
        assertFalse(isSilent(live))
        bank.clear()
    }

    @Test
    fun muteAllStemsIsSilence() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("stems")))
        StemId.entries.forEach { bank.setGain(it, 0f) }
        assertTrue(isSilent(render(bank)))
        bank.clear()
    }

    @Test
    fun liveRemixOutputIsNotAllZerosWhenStemHasSignal() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("signal")))
        StemId.entries.forEach { bank.setGain(it, 1f) }
        val engine = pcm16Engine(enabled = false)
        val queued = render(bank)
        assertFalse(isSilent(queued))
        val live = ClickMix.copyAndMix(queued.duplicate(), ByteBuffer.allocateDirect(0), engine, 0L, bank)
        assertFalse("sink remix must not zero a stem mix with gains > 0", isSilent(live))
        assertTrue(rms(live) > 0.05)
        bank.clear()
    }

    @Test
    fun metronomeDoesNotBuryClicksInStemMix() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("click-id")))
        StemId.entries.forEach { bank.setGain(it, 0.12f) }
        val off = pcm16Engine(enabled = false)
        val on = pcm16Engine(enabled = true)
        on.originalBpm = 120f
        on.setBeatTimesUs(longArrayOf(0L, 500_000L))
        val queued = render(bank)
        val stemsOnly = ClickMix.copyAndMix(queued.duplicate(), ByteBuffer.allocateDirect(0), off, 0L, bank)
        val withFlag = ClickMix.copyAndMix(queued.duplicate(), ByteBuffer.allocateDirect(0), on, 0L, bank)
        assertFalse("stems-only mix must have signal", isSilent(stemsOnly))
        assertTrue(
            "web architecture: clicks are not mixed into stem PCM",
            sameBytes(stemsOnly, withFlag),
        )
        bank.clear()
    }

    @Test
    fun clickLayerHasEnergyWhenAllStemsMuted() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("muted-click")))
        StemId.entries.forEach { bank.setGain(it, 0f) }
        val engine = pcm16Engine(enabled = true)
        engine.setBeatTimesUs(longArrayOf(0L, 500_000L))
        val stems = render(bank)
        assertTrue("muted stems are silence", isSilent(stems))
        val mixed = ClickMix.copyAndMix(stems.duplicate(), ByteBuffer.allocateDirect(0), engine, 0L, bank)
        assertTrue("metronome must not inject clicks into muted stem PCM", isSilent(mixed))
        val layer = ByteBuffer.allocateDirect(stems.remaining()).order(ByteOrder.nativeOrder())
        layer.limit(stems.remaining())
        ClickLayer.render(layer, engine, 0L, 1f)
        layer.position(0)
        assertFalse("stems muted → click layer still has energy", isSilent(layer))
        assertTrue(peak(layer) > 0.85)
        bank.clear()
    }

    @Test
    fun metronomeGridClicksWhenBeatsArrayEmpty() {
        val engine = pcm16Engine(enabled = true)
        engine.originalBpm = 120f
        engine.setBeatTimesUs(longArrayOf())
        val layer = ByteBuffer.allocateDirect(44100 * 4).order(ByteOrder.nativeOrder())
        layer.limit(44100 * 4)
        ClickLayer.render(layer, engine, 0L, 1f)
        layer.position(0)
        assertFalse("empty beats[] must still click on BeatClock grid", isSilent(layer))
    }

    @Test
    fun liveRemixKeepsSourceWhenPtsIsPastDuration() {
        val bank = StemMixBank()
        bank.load(WavFixtures.writeStemSet(tmp.newFolder("pts")))
        val engine = pcm16Engine(enabled = false)
        val queued = render(bank)
        val farPts = 3_600_000_000L
        assertTrue(farPts > bank.durationUs())
        assertFalse(ClickMix.canLiveRemix(queued.remaining(), C.ENCODING_PCM_16BIT, 2, farPts, bank.durationUs()))
        val live = ClickMix.copyAndMix(queued.duplicate(), ByteBuffer.allocateDirect(0), engine, farPts, bank)
        assertFalse("OOB pts must not replace MediaSource PCM with zeros", isSilent(live))
        assertEquals(rms(queued), rms(live), 1e-4)
        bank.clear()
    }

    @Test
    fun canLiveRemixRejectsUnsetPtsAndBadFrameSize() {
        assertFalse(ClickMix.canLiveRemix(4096, C.ENCODING_PCM_16BIT, 2, C.TIME_UNSET, 1_000_000L))
        assertFalse(ClickMix.canLiveRemix(4096, C.ENCODING_PCM_16BIT, 2, 0L, 0L))
        assertFalse(ClickMix.canLiveRemix(4096, C.ENCODING_PCM_16BIT, 2, 1_000_000L, 1_000_000L))
        assertFalse(ClickMix.canLiveRemix(6, C.ENCODING_PCM_16BIT, 2, 0L, 1_000_000L))
        assertTrue(ClickMix.canLiveRemix(4096, C.ENCODING_PCM_16BIT, 2, 0L, 1_000_000L))
    }

    private fun pcm16Engine(enabled: Boolean): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            androidx.media3.common.Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.enabled = enabled
        return engine
    }

    private fun render(bank: StemMixBank): ByteBuffer {
        val frames = 1024
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        bank.render(out, C.ENCODING_PCM_16BIT, 2, 0L)
        out.position(0)
        out.limit(frames * 4)
        return out
    }

    private fun sameBytes(a: ByteBuffer, b: ByteBuffer): Boolean {
        if (a.remaining() != b.remaining()) return false
        val aa = a.asReadOnlyBuffer()
        val bb = b.asReadOnlyBuffer()
        while (aa.hasRemaining()) {
            if (aa.get() != bb.get()) return false
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

    private fun isSilent(buffer: ByteBuffer): Boolean {
        val shorts = buffer.asShortBuffer()
        for (i in 0 until shorts.limit()) {
            if (abs(shorts.get(i).toInt()) > 8) return false
        }
        return true
    }

    private fun rms(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var sum = 0.0
        val n = shorts.limit()
        for (i in 0 until n) {
            val s = shorts.get(i) / 32768.0
            sum += s * s
        }
        return sqrt(sum / n.coerceAtLeast(1))
    }
}
