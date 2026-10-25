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

    private fun render(bank: StemMixBank): ByteBuffer {
        val frames = 1024
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        bank.render(out, C.ENCODING_PCM_16BIT, 2, 0L)
        out.position(0)
        out.limit(frames * 4)
        return out
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
