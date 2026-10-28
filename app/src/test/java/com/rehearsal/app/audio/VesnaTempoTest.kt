package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * «Ночные снайперы — 31-я весна». На 0.3.28 метроном невпопад: детектор
 * часто берёт октаву ~156 вместо ~77. Mix без Demucs.
 */
class VesnaTempoTest {
    @Test
    fun thirtyFirstSpringIsBalladNotDouble() {
        val wav = vesnaWav()
        val pcm = WavReader.readStereo(wav)
        val novelty = TempoDetector.novelty(pcm, TempoDetector.HOP)
        val rawMix = TempoDetector.estimateBpm(novelty, TempoDetector.HOP, pcm.sampleRate)
        val mix = TempoDetector.analyze(pcm)
        val energy = KickGrid.lowBandEnergy(pcm, KickGrid.HOP, KickGrid.LOWPASS_HZ)
        val flux = KickGrid.fluxOf(energy)
        val rawDrums = TempoDetector.estimateBpm(flux, KickGrid.HOP, pcm.sampleRate)
        val grid = KickGrid.analyze(pcm)
        val refined = KickGrid.refine(mix, pcm)
        val ioi = KickGrid.ioiBpm(grid.onsetsSec)
        val period = 60.0 / refined.bpm.coerceAtLeast(1f)
        println(
            "vesna dur=${pcm.samples.toDouble() / pcm.sampleRate} " +
                "rawMix=${fmt(rawMix)} mix=${mix.bpm} " +
                "rawDrums=${fmt(rawDrums)} kick=${grid.bpm} phase=${fmt(grid.kickPhaseSec)} " +
                "ioi=${ioi?.let { fmt(it) } ?: "-"} onsets=${grid.onsetsSec.size} " +
                "early=${grid.earlyClickUsed}/${grid.earlyClickHits}/${grid.earlyClickExamined} " +
                "lead=${fmt(grid.earlyClickLeadSec)} " +
                "refine=${refined.bpm} refinePhase=${fmt(refined.kickPhaseSec)} " +
                "period=${fmt(period)} on16=${grid.onsetsOnSixteenth} off16=${grid.onsetsOffSixteenth} " +
                "offset=${fmt(mix.downbeatOffset)} beats=${mix.beats.size}",
        )
        val top = grid.phaseCandidates.take(5).joinToString { c ->
            "p=${fmt(c.phaseSec)}/${fmt(c.score)}"
        }
        println("vesna candidates $top")
        println("vesna firstOnsets ${grid.onsetsSec.take(12).joinToString { fmt(it) }}")
        println("vesna firstBeats ${mix.beats.take(12).joinToString { fmt(it) }}")

        assertTrue("need audio, frames=${pcm.samples}", pcm.samples > pcm.sampleRate * 60)
        assertTrue("mix must not stay on the 156 octave, was ${mix.bpm}", mix.bpm < 130f)
        assertTrue("kick must not stay on the 156 octave, was ${grid.bpm}", grid.bpm < 130f)
        assertTrue("refine must not stay on the 156 octave, was ${refined.bpm}", refined.bpm < 130f)
        assertEquals("vesna mix ~77.5, was ${mix.bpm}", 77.5, mix.bpm.toDouble(), 2.5)
        assertTrue("vesna kick must not be 155, was ${grid.bpm}", grid.bpm < 130f)
        assertEquals("vesna refine follows file tempo, was ${refined.bpm}", mix.bpm.toDouble(), refined.bpm.toDouble(), 0.2)
        assertTrue("phase in one bar, was ${refined.kickPhaseSec}", refined.kickPhaseSec >= 0.0)
        assertTrue("phase < period, was ${refined.kickPhaseSec} per=$period", refined.kickPhaseSec < period)
    }

    companion object {
        private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

        fun vesnaWav(): File {
            val tmp = File("/tmp/vesna44100.wav")
            if (tmp.isFile && tmp.length() > 1_000_000L) return tmp
            val store = File(
                "/cursor/stores/bc-22c447ce-3b80-4608-9e7b-876f4a324148/internal/samples/31-ya-vesna.wav",
            )
            if (store.isFile && store.length() > 1_000_000L) return store
            throw AssertionError("Нет декодированной 31-ya-vesna.wav в /tmp/vesna44100.wav")
        }
    }
}
