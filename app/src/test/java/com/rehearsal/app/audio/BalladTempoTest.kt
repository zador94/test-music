package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Skyfall-like ballads sit near 73–76 BPM. Device showed 131, then 153.
 * 65.5×2 = 131, 76.5×2 = 153, kick+snare 8ths → ~150.
 */
class BalladTempoTest {
    @Test
    fun clampKeepsSixtyFivePointFive() {
        assertEquals(65.5, TempoMath.clampHalfDouble(65.5), 1e-9)
        assertEquals(65.6, TempoMath.clampHalfDouble(65.6), 1e-9)
        assertTrue(abs(TempoMath.clampHalfDouble(65.5) - 131.0) > 8.0)
    }

    @Test
    fun seventyFiveQuarterClicksStayNear75() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 75.0, bars = 8, beatsPerBar = 4)
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        assertNear75("mix clicks", mix.bpm)
        assertNear75("kick clicks", grid.bpm)
    }

    @Test
    fun seventyFiveKickSnareBalladStaysNear75() {
        val pcm = balladGroove(bpm = 75.0, hats = false, pianoBleed = false)
        assertBallad75("kick+snare", pcm)
    }

    @Test
    fun seventyFiveHalfTimeKickSnareMustNotBecome131() {
        val pcm = halfTimeBallad(bpm = 75.0)
        val grid = KickGrid.analyze(pcm)
        assertTrue("half-time KickGrid was ${grid.bpm}", abs(grid.bpm - 131f) > 4f)
        assertTrue("half-time KickGrid was ${grid.bpm}", abs(grid.bpm - 153f) > 4f)
        assertTrue("half-time KickGrid was ${grid.bpm}", grid.bpm in 60f..110f)
    }

    @Test
    fun seventyFiveWithEighthHatsMustNotDoubleTo150() {
        val pcm = balladGroove(bpm = 75.0, hats = true, pianoBleed = false)
        assertBallad75("kick+snare+hats", pcm)
    }

    @Test
    fun seventyFiveWithPianoBleedMustNotJumpTo153() {
        val pcm = balladGroove(bpm = 75.0, hats = true, pianoBleed = true)
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        assertTrue("piano-bleed mix was ${mix.bpm}", mix.bpm < 140f && abs(mix.bpm - 153f) > 4f)
        assertTrue("piano-bleed KickGrid was ${grid.bpm}", grid.bpm < 140f && abs(grid.bpm - 153f) > 4f)
    }

    @Test
    fun seventySevenWithHatsMustNotBecome156() {
        val pcm = balladGroove(bpm = 77.1, hats = true, pianoBleed = true)
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        assertTrue("77.1 mix was ${mix.bpm}", abs(mix.bpm - 156f) > 8f)
        assertTrue("77.1 kick was ${grid.bpm}", abs(grid.bpm - 156f) > 8f)
        assertEquals("77.1 mix, was ${mix.bpm}", 77.1, mix.bpm.toDouble(), 3.0)
        assertEquals("77.1 kick, was ${grid.bpm}", 77.1, grid.bpm.toDouble(), 4.0)
        val folded = KickGrid.refine(mix.copy(bpm = 156.6f), pcm)
        assertTrue("refine 156 must fold, was ${folded.bpm}", abs(folded.bpm - 156f) > 8f)
        assertEquals("refine folded, was ${folded.bpm}", 77.1, folded.bpm.toDouble(), 4.0)
    }

    @Test
    fun seventySixPointFiveMustNotBecome153() {
        val pcm = balladGroove(bpm = 76.5, hats = true, pianoBleed = true)
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        assertTrue("76.5 mix was ${mix.bpm}", abs(mix.bpm - 153f) > 4f)
        assertTrue("76.5 kick was ${grid.bpm}", abs(grid.bpm - 153f) > 4f)
        assertEquals("76.5 mix, was ${mix.bpm}", 76.5, mix.bpm.toDouble(), 8.0)
        assertEquals("76.5 kick, was ${grid.bpm}", 76.5, grid.bpm.toDouble(), 8.0)
        val refined = KickGrid.refine(mix.copy(bpm = 153f), pcm)
        assertTrue("refine 153/153 must fold, was ${refined.bpm}", abs(refined.bpm - 153f) > 4f)
        assertEquals("refine folded, was ${refined.bpm}", 76.5, refined.bpm.toDouble(), 8.0)
    }

    @Test
    fun refineKeepsMix75WhenDrumsAre131() {
        val mix = TempoDetector.analyze(TempoDetectorTest.stereoClicks(bpm = 75.0, bars = 8, beatsPerBar = 4))
        val doubled = KickGridTest.lowBandKicks(bpm = 131.0, phaseSec = 0.08, bars = 8, beatsPerBar = 4)
        val refined = KickGrid.refine(mix, doubled)
        assertNear75("mix seed", mix.bpm)
        assertNear75("refined vs drums 131", refined.bpm)
    }

    @Test
    fun sixtyFivePointFiveClicksMustNotBecome131() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 65.5, bars = 8, beatsPerBar = 4)
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        assertTrue("mix ${mix.bpm}", abs(mix.bpm - 131f) > 8f)
        assertTrue("kick ${grid.bpm}", abs(grid.bpm - 131f) > 8f)
        assertEquals(65.5, mix.bpm.toDouble(), 4.0)
        assertEquals(65.5, grid.bpm.toDouble(), 6.0)
    }

    @Test
    fun dumpSkyfallLikeFamilies() {
        val report = StringBuilder()
        fun dump(name: String, pcm: StereoPcm) {
            val novelty = TempoDetector.novelty(pcm, TempoDetector.HOP)
            val rawMix = TempoDetector.estimateBpm(novelty, TempoDetector.HOP, pcm.sampleRate)
            val mix = TempoDetector.analyze(pcm)
            val energy = KickGrid.lowBandEnergy(pcm, KickGrid.HOP, KickGrid.LOWPASS_HZ)
            val flux = KickGrid.fluxOf(energy)
            val rawDrums = TempoDetector.estimateBpm(flux, KickGrid.HOP, pcm.sampleRate)
            val grid = KickGrid.analyze(pcm)
            val ioi = KickGrid.ioiBpm(grid.onsetsSec)
            report.appendLine(
                "$name mix=${mix.bpm} rawMix=${fmt(rawMix)} clampMix=${fmt(TempoMath.clampHalfDouble(rawMix))} " +
                    "kick=${grid.bpm} rawDrums=${fmt(rawDrums)} clampDrums=${fmt(TempoMath.clampHalfDouble(rawDrums))} " +
                    "ioi=${ioi?.let { fmt(it) } ?: "-"} onsets=${grid.onsetsSec.size}",
            )
        }
        dump("75 clicks", TempoDetectorTest.stereoClicks(75.0, 8, 4))
        dump("73.7 clicks", TempoDetectorTest.stereoClicks(73.7, 8, 4))
        dump("76 clicks", TempoDetectorTest.stereoClicks(76.0, 8, 4))
        dump("65.5 clicks", TempoDetectorTest.stereoClicks(65.5, 8, 4))
        dump("75 kick+snare", balladGroove(75.0, hats = false, pianoBleed = false))
        dump("75 +hats", balladGroove(75.0, hats = true, pianoBleed = false))
        dump("75 +piano", balladGroove(75.0, hats = true, pianoBleed = true))
        dump("75 half-time", halfTimeBallad(75.0))
        dump("73.7 +hats", balladGroove(73.7, hats = true, pianoBleed = false))
        dump("74 +hats+piano", balladGroove(74.0, hats = true, pianoBleed = true))
        println(report.toString())
        assertTrue(report.isNotEmpty())
    }

    private fun assertBallad75(label: String, pcm: StereoPcm) {
        val mix = TempoDetector.analyze(pcm)
        val grid = KickGrid.analyze(pcm)
        val detail = "$label mix=${mix.bpm} kick=${grid.bpm} onsets=${grid.onsetsSec.size}"
        assertNear75("$detail mix", mix.bpm)
        assertNear75("$detail kick", grid.bpm)
        assertTrue(
            "$detail must not land on the 131 doubling",
            abs(grid.bpm - 131f) > 4f && abs(mix.bpm - 131f) > 4f,
        )
    }

    private fun assertNear75(label: String, bpm: Float) {
        assertEquals(label, 75.0, bpm.toDouble(), 2.0)
        assertTrue("$label doubled into 140–160: $bpm", bpm < 140f)
        assertTrue("$label must stay a ballad, was $bpm", bpm < 100f)
    }

    companion object {
        private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

        fun balladGroove(
            bpm: Double,
            hats: Boolean,
            pianoBleed: Boolean,
            bars: Int = 8,
            phaseSec: Double = 0.08,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val quarter = 60.0 / bpm
            val eighth = quarter / 2.0
            val durationSec = phaseSec + bars * 4 * quarter + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            for (bar in 0 until bars) {
                val t0 = phaseSec + bar * 4 * quarter
                addKick(left, right, t0, samples, sampleRate)
                addKick(left, right, t0 + 2 * quarter, samples, sampleRate)
                addSnare(left, right, t0 + quarter, samples, sampleRate)
                addSnare(left, right, t0 + 3 * quarter, samples, sampleRate)
                if (hats) {
                    for (e in 0 until 8) {
                        addHat(left, right, t0 + e * eighth, samples, sampleRate)
                    }
                }
                if (pianoBleed) {
                    addPiano(left, right, t0, samples, sampleRate)
                    addPiano(left, right, t0 + eighth * 3, samples, sampleRate)
                    addPiano(left, right, t0 + eighth * 5, samples, sampleRate)
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun halfTimeBallad(
            bpm: Double,
            bars: Int = 8,
            phaseSec: Double = 0.08,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val quarter = 60.0 / bpm
            val durationSec = phaseSec + bars * 4 * quarter + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            for (bar in 0 until bars) {
                val t0 = phaseSec + bar * 4 * quarter
                addKick(left, right, t0, samples, sampleRate)
                addSnare(left, right, t0 + 2 * quarter, samples, sampleRate)
            }
            return StereoPcm(sampleRate, left, right)
        }

        private fun addKick(
            left: FloatArray,
            right: FloatArray,
            t: Double,
            samples: Int,
            sampleRate: Int,
        ) {
            addHit(left, right, t, samples, sampleRate, amp = 0.95f, f0 = 55.0, decay = 0.14)
        }

        private fun addSnare(
            left: FloatArray,
            right: FloatArray,
            t: Double,
            samples: Int,
            sampleRate: Int,
        ) {
            addHit(left, right, t, samples, sampleRate, amp = 0.55f, f0 = 180.0, decay = 0.08)
        }

        private fun addHat(
            left: FloatArray,
            right: FloatArray,
            t: Double,
            samples: Int,
            sampleRate: Int,
        ) {
            addHit(left, right, t, samples, sampleRate, amp = 0.18f, f0 = 7000.0, decay = 0.02)
        }

        private fun addPiano(
            left: FloatArray,
            right: FloatArray,
            t: Double,
            samples: Int,
            sampleRate: Int,
        ) {
            addHit(left, right, t, samples, sampleRate, amp = 0.28f, f0 = 110.0, decay = 0.22)
        }

        private fun addHit(
            left: FloatArray,
            right: FloatArray,
            t: Double,
            samples: Int,
            sampleRate: Int,
            amp: Float,
            f0: Double,
            decay: Double,
        ) {
            val start = (t * sampleRate).toInt()
            val n = (decay * 3.0 * sampleRate).toInt().coerceAtLeast(32)
            val tau = decay * sampleRate
            for (i in 0 until n) {
                val idx = start + i
                if (idx < 0 || idx >= samples) break
                val env = exp(-i / tau)
                val s = (env * amp * sin(2.0 * PI * f0 * i / sampleRate)).toFloat()
                left[idx] = min(1f, left[idx] + s)
                right[idx] = min(1f, right[idx] + s * 0.96f)
            }
        }
    }
}
