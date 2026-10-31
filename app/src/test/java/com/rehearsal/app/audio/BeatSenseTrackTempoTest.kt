package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Порт `lib/track-tempo.test.ts`. `demo-rhythm.ts` в сторе нет:
 * `renderDemo` — удары на `start` + n·period (тот же якорь фазы).
 */
class BeatSenseTrackTempoTest {
    @Test
    fun sineIntegerBinPeaksThere() {
        val n = 64
        val bin = 5
        val re = DoubleArray(n) { i -> sin((2.0 * PI * bin * i) / n) }
        val im = DoubleArray(n)
        TrackTempo.transform(re, im)
        var peak = 0
        for (k in 1 until n / 2) {
            val mag = re[k] * re[k] + im[k] * im[k]
            val peakMag = re[peak] * re[peak] + im[peak] * im[peak]
            if (mag > peakMag) peak = k
        }
        assertEquals(bin, peak)
    }

    @Test
    fun demoRhythmGivesOwnTempoAndPhase() {
        for (bpm in intArrayOf(72, 96, 120, 128, 140, 174)) {
            val sampleRate = 48000
            val pcm = renderDemo(sampleRate, bpm.toDouble(), 24, DEMO_START)
            expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm.toDouble(), DEMO_START)
        }
    }

    @Test
    fun shortForeignIntroDoesNotSetWholeSong() {
        val sampleRate = 48000
        val intro = renderDemo(sampleRate, 170.0, 6, 0.2)
        val body = renderDemo(sampleRate, 100.0, 24, 0.2)
        val pcm = FloatArray(intro.size + body.size)
        intro.copyInto(pcm, 0)
        body.copyInto(pcm, intro.size)
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), 100.0)
    }

    @Test
    fun sustainedBassAndChordsDoNotMoveDrumBeat() {
        val sampleRate = 48000
        val bpm = 110.0
        val pcm = renderBand(sampleRate, bpm, 28, 0.4)
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm, 0.4)
    }

    @Test
    fun offbeatChordsDoNotDoubleTempo() {
        val sampleRate = 48000
        val bpm = 96.0
        val pcm = renderBand(sampleRate, bpm, 28, 0.3, offbeatChords = true)
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm, 0.3)
    }

    @Test
    fun sparseHitsStaySparseAndDoNotDouble() {
        val sampleRate = 48000
        val bpm = 66.0
        val pcm = renderBand(sampleRate, bpm, 32, 0.5, hats = false, chords = false)
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm, 0.5)
    }

    @Test
    fun eighthsOnTempoNear100DoNotGoTo200() {
        val sampleRate = 48000
        val bpm = 102.0
        val pcm = renderBand(sampleRate, bpm, 36, 0.4, hats = true)
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm, 0.4)
    }

    @Test
    fun kickEveryBeatDoesNotBecomeEighths() {
        val sampleRate = 48000
        val bpm = 100.0
        val pcm = renderBand(
            sampleRate, bpm, 24, 0.25,
            hats = false, chords = false, snare = false, kickEveryBeat = true,
        )
        expectTempo(TrackTempo.tempoOfTrack(pcm, sampleRate), bpm, 0.25)
    }

    companion object {
        const val DEMO_START = 0.2

        fun phaseError(nextBeat: Double, period: Double, anchor: Double): Double {
            val relative = (((nextBeat - anchor) % period) + period) % period
            return min(relative, period - relative)
        }

        fun expectTempo(estimate: TrackTempo.Estimate?, bpm: Double, anchor: Double? = null) {
            assertNotNull("темп не найден", estimate)
            val est = estimate!!
            println("beat-sense case expect=$bpm got=${est.bpm} nextBeat=${est.nextBeat} conf=${est.confidence}")
            assertTrue(
                "ждали около $bpm BPM, получили ${"%.2f".format(est.bpm)}; кандидаты ${
                    est.candidates.joinToString { "%.1f".format(it.bpm) }
                }",
                abs(est.bpm - bpm) < 2.0,
            )
            if (anchor == null) return
            val error = phaseError(est.nextBeat, 60.0 / bpm, anchor)
            assertTrue("фаза уехала на ${"%.1f".format(error * 1000)} мс", error < 0.03)
        }

        fun renderDemo(sampleRate: Int, bpm: Double, seconds: Int, start: Double): FloatArray {
            val data = FloatArray(floor(sampleRate * seconds.toDouble()).toInt())
            val quarter = 60.0 / bpm
            val beats = floor((seconds - start - 0.1) / quarter).toInt()
            for (beat in 0 until beats) {
                addKick(data, sampleRate, start + beat * quarter)
            }
            return data
        }

        fun renderBand(
            sampleRate: Int,
            bpm: Double,
            seconds: Int,
            start: Double,
            hats: Boolean = true,
            chords: Boolean = true,
            offbeatChords: Boolean = false,
            snare: Boolean = true,
            kickEveryBeat: Boolean = false,
        ): FloatArray {
            val data = FloatArray(floor(sampleRate * seconds.toDouble()).toInt())
            val quarter = 60.0 / bpm
            val beats = floor((seconds - start - 0.1) / quarter).toInt()
            val random = mulberry32(0x1234)
            val bassNotes = doubleArrayOf(55.0, 73.0, 82.0, 98.0)
            for (beat in 0 until beats) {
                val time = start + beat * quarter
                val place = beat % 4
                if (kickEveryBeat || place == 0 || place == 2) addKick(data, sampleRate, time)
                if (snare && (place == 1 || place == 3)) addSnare(data, sampleRate, time, random)
                if (hats) {
                    addHat(data, sampleRate, time, 0.16, random)
                    addHat(data, sampleRate, time + quarter / 2.0, 0.1, random)
                }
                addTone(data, sampleRate, time, bassNotes[place], quarter * 0.95, 0.28)
                if (chords && place == 0) {
                    addTone(data, sampleRate, time, 196.0, quarter * 3.6, 0.08)
                    addTone(data, sampleRate, time, 247.0, quarter * 3.6, 0.06)
                    addTone(data, sampleRate, time, 294.0, quarter * 3.6, 0.05)
                }
                if (offbeatChords) {
                    addTone(data, sampleRate, time + quarter / 2.0, 330.0, quarter * 0.4, 0.12)
                    addTone(data, sampleRate, time + quarter / 2.0, 415.0, quarter * 0.4, 0.08)
                }
            }
            return data
        }

        fun addKick(data: FloatArray, sampleRate: Int, time: Double) {
            val length = floor(0.18 * sampleRate).toInt()
            val start = floor(time * sampleRate).toInt()
            var phase = 0.0
            var i = 0
            while (i < length && start + i < data.size) {
                val seconds = i.toDouble() / sampleRate
                val freq = 140.0 * exp(-seconds / 0.045) + 42.0
                phase += (2.0 * PI * freq) / sampleRate
                data[start + i] += (sin(phase) * exp(-seconds / 0.06) * 0.9).toFloat()
                i++
            }
        }

        fun addSnare(data: FloatArray, sampleRate: Int, time: Double, random: () -> Double) {
            val length = floor(0.16 * sampleRate).toInt()
            val start = floor(time * sampleRate).toInt()
            var i = 0
            while (i < length && start + i < data.size) {
                val seconds = i.toDouble() / sampleRate
                val noise = random() * 2.0 - 1.0
                data[start + i] += (noise * exp(-seconds / 0.05) * 0.38).toFloat()
                data[start + i] += (sin(2.0 * PI * 180.0 * seconds) * exp(-seconds / 0.02) * 0.22).toFloat()
                i++
            }
        }

        fun addHat(data: FloatArray, sampleRate: Int, time: Double, amp: Double, random: () -> Double) {
            val length = floor(0.025 * sampleRate).toInt()
            val start = floor(time * sampleRate).toInt()
            var previous = 0.0
            var i = 0
            while (i < length && start + i < data.size) {
                val noise = random() * 2.0 - 1.0
                val high = noise - previous
                previous = noise * 0.2 + previous * 0.8
                data[start + i] += (high * exp(-i / (0.004 * sampleRate)) * amp).toFloat()
                i++
            }
        }

        fun addTone(
            data: FloatArray,
            sampleRate: Int,
            time: Double,
            freq: Double,
            duration: Double,
            amp: Double,
        ) {
            val start = floor(time * sampleRate).toInt()
            val length = floor(duration * sampleRate).toInt()
            var i = 0
            while (i < length && start + i < data.size) {
                val attack = min(1.0, i / (0.01 * sampleRate))
                val env = attack * exp(-i / (duration * sampleRate))
                data[start + i] += (sin((2.0 * PI * freq * i) / sampleRate) * amp * env).toFloat()
                i++
            }
        }

        fun mulberry32(seed: Int): () -> Double {
            var state = seed
            return {
                state = state + 0x6d2b79f5
                var t = (state xor (state ushr 15)) * (1 or state)
                t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
                ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
            }
        }
    }
}
