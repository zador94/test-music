package com.rehearsal.app.audio

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Device 0.3.24: pipe click−kick = 0.000 ms, two songs still want −170 ms.
 * Timebase of KickGrid (WAV → kickPhaseSec), not playback.
 */
class KickGridTimebaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun delayBudgetIsOneMillisecondNotOneHundredSeventy() {
        val resampleMs = 0.0
        val iirMs = analyticIirDelayMs(44100)
        val stftMs = 0.0
        val hopEndMs = 0.0
        val smoothMs = 0.0
        val sumLateMs = resampleMs + iirMs + stftMs + hopEndMs + smoothMs
        println(
            "KickGridTimebase delay budget resample=${resampleMs}ms IIR=${"%.2f".format(iirMs)}ms " +
                "STFT=${stftMs}ms hopEnd=${hopEndMs}ms smooth=${smoothMs}ms SUM_LATE=${"%.2f".format(sumLateMs)}ms",
        )
        assertTrue("one-pole 150 Hz group delay ~1 ms, was ${iirMs}ms", abs(iirMs - 1.05) < 0.15)
        assertTrue(
            "KickGrid late delay must sum to ~1 ms, not 170. sum=${sumLateMs}ms",
            sumLateMs < 3.0,
        )
    }

    @Test
    fun iirLowpassGroupDelayIsAboutOneMillisecond() {
        val sr = 44100
        val hop = KickGrid.HOP
        val start = 20 * hop
        val left = FloatArray(sr * 2)
        val right = FloatArray(sr * 2)
        for (i in start until left.size) {
            val t = (i - start).toDouble() / sr
            val s = (0.95 * sin(2.0 * PI * 70.0 * t)).toFloat()
            left[i] = s
            right[i] = s
        }
        val energy = KickGrid.lowBandEnergy(StereoPcm(sr, left, right), hop, KickGrid.LOWPASS_HZ)
        var peak = 1e-8f
        for (v in energy) if (v > peak) peak = v
        val thresh = peak * KickGrid.ENERGY_START_FRAC
        var hopIndex = 0
        while (hopIndex < energy.size && energy[hopIndex] < thresh) hopIndex++
        val reportedSec = hopIndex * hop / sr.toDouble()
        val trueSec = start.toDouble() / sr
        val errMs = (reportedSec - trueSec) * 1000.0
        println(
            "KickGridTimebase IIR+hopStart err=${"%.2f".format(errMs)}ms " +
                "true=${"%.4f".format(trueSec)}s reported=${"%.4f".format(reportedSec)}s " +
                "analyticIIR=${"%.2f".format(analyticIirDelayMs(sr))}ms hop=${hopMs()}ms",
        )
        assertTrue(
            "IIR 150 Hz + hop-start timestamp must not be a 170 ms pad, err=${errMs}ms",
            abs(errMs) <= hopMs() + 3.0,
        )
    }

    @Test
    fun hopTimestampIsWindowStartNotCenterOrEnd() {
        val sr = 44100
        val hop = KickGrid.HOP
        // Impulse in the first sample of hop 10 — time must be 10*hop, not center/end.
        val hopIndex = 10
        val at = hopIndex * hop
        val left = FloatArray(hop * 40)
        val right = FloatArray(hop * 40)
        left[at] = 0.95f
        right[at] = 0.95f
        val energy = KickGrid.lowBandEnergy(StereoPcm(sr, left, right), hop, KickGrid.LOWPASS_HZ)
        var peakHop = 0
        var peak = 1e-8f
        for (i in energy.indices) {
            if (energy[i] > peak) {
                peak = energy[i]
                peakHop = i
            }
        }
        val reportedMs = peakHop * hop * 1000.0 / sr
        val startMs = hopIndex * hop * 1000.0 / sr
        val centerMs = startMs + hopMs() / 2.0
        val endMs = startMs + hopMs()
        println(
            "KickGridTimebase hop stamp reported=${"%.2f".format(reportedMs)}ms " +
                "start=${"%.2f".format(startMs)}ms center=${"%.2f".format(centerMs)}ms " +
                "end=${"%.2f".format(endMs)}ms",
        )
        assertTrue(
            "energy hop must be the window that contains the impulse, peakHop=$peakHop want=$hopIndex",
            abs(peakHop - hopIndex) <= 1,
        )
        val centerStamp = KickGrid.hopCenterSec(peakHop, hop, sr) * 1000.0
        assertTrue(
            "energy lives in the hop window (start ${startMs}ms … end ${endMs}ms), peakHop=$peakHop",
            abs(reportedMs - startMs) < hopMs() + 0.5,
        )
        assertTrue(
            "phase timestamp is hop CENTER (${"%.1f".format(centerStamp)}ms), not start or end",
            abs(centerStamp - centerMs) < 0.5,
        )
    }

    @Test
    fun realisticGlissandoKickFromWavMatchesHitTime() {
        val bpm = 104.6
        val phaseSec = 0.08
        val pcm = realisticGlissandoKicks(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val refined = refineFromWav(pcm, bpm)
        val period = 60.0 / bpm
        val errMs = wrappedMs(refined.kickPhaseSec - phaseSec, period)
        println(
            "KickGridTimebase WAV-path glissando bpm=${refined.bpm} " +
                "phase=${refined.kickPhaseSec} err=${"%.1f".format(errMs)}ms version=${refined.kickGridVersion}",
        )
        assertTrue(
            "file-path KickGrid.refine phase error ${errMs}ms (want ≤10ms vs hit at ${phaseSec}s) " +
                "bpm=${refined.bpm} phase=${refined.kickPhaseSec}",
            abs(errMs) <= 10.0,
        )
    }

    @Test
    fun realisticGlissandoAtFonariTempoFromWav() {
        val bpm = 102.0
        val phaseSec = 0.08
        val pcm = realisticGlissandoKicks(bpm = bpm, phaseSec = phaseSec, bars = 8)
        val refined = refineFromWav(pcm, bpm)
        val errMs = wrappedMs(refined.kickPhaseSec - phaseSec, 60.0 / bpm)
        println(
            "KickGridTimebase WAV-path Fonari-tempo bpm=${refined.bpm} " +
                "phase=${refined.kickPhaseSec} err=${"%.1f".format(errMs)}ms",
        )
        assertTrue(
            "102 BPM glissando phase error ${errMs}ms, phase=${refined.kickPhaseSec}",
            abs(errMs) <= 10.0,
        )
    }

    private fun refineFromWav(pcm: StereoPcm, bpm: Double): TrackAnalysis {
        val file = File(tmp.newFolder(), "drums.wav")
        WavWriter(file, pcm.sampleRate, channels = 2).use { writer ->
            writer.writePlanarFloatStereo(pcm.left, pcm.right)
        }
        val drums = WavReader.readStereo(file)
        val period = 60.0 / bpm
        val seed = TrackAnalysis.fallbackGrid(drums.samples, drums.sampleRate).copy(
            bpm = bpm.toFloat(),
            kickGridVersion = 0,
            periodSec = period,
            phaseSec = 0.0,
        )
        return KickGrid.refine(seed, drums)
    }

    companion object {
        fun hopMs(sampleRate: Int = 44100): Double =
            KickGrid.HOP * 1000.0 / sampleRate

        fun analyticIirDelayMs(sampleRate: Int, cutoffHz: Double = KickGrid.LOWPASS_HZ): Double {
            val alpha = 1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)
            val samples = (1.0 - alpha) / alpha
            return samples * 1000.0 / sampleRate
        }

        /**
         * Beater click + 120→50 Hz glissando, 250 ms decay, 8th-note bass bleed, hats.
         * Hits at [phaseSec + n * period].
         */
        fun realisticGlissandoKicks(
            bpm: Double,
            phaseSec: Double,
            bars: Int,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.6
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val kickLen = (0.25 * sampleRate).toInt()
            val clickLen = (0.004 * sampleRate).toInt().coerceAtLeast(8)
            val clickTau = 0.0012 * sampleRate
            val bodyTau = 0.090 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / clickTau)
                    val s = (env * 0.55 * sin(2.0 * PI * 2800.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
                for (i in 0 until kickLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / bodyTau)
                    val frac = i.toDouble() / kickLen
                    val freq = 120.0 + (50.0 - 120.0) * frac
                    val phase = 2.0 * PI * (120.0 * i / sampleRate + (50.0 - 120.0) * frac * i / (2.0 * sampleRate))
                    val s = (env * 0.95 * sin(phase)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
            }
            val eighth = period / 2.0
            val bassLen = (0.12 * sampleRate).toInt()
            val bassTau = 0.050 * sampleRate
            val eighths = (beats * 2)
            for (e in 0 until eighths) {
                val start = ((phaseSec + e * eighth) * sampleRate).toInt()
                for (i in 0 until bassLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / bassTau)
                    val s = (env * 0.28 * sin(2.0 * PI * 45.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
            }
            val hatLen = (0.025 * sampleRate).toInt()
            val hatTau = 0.008 * sampleRate
            for (e in 0 until eighths) {
                val start = ((phaseSec + e * eighth + 0.01) * sampleRate).toInt()
                for (i in 0 until hatLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / hatTau)
                    val s = (env * 0.18 * sin(2.0 * PI * 9000.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s * 0.7f
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun wrappedMs(deltaSec: Double, periodSec: Double): Double {
            val p = periodSec.coerceAtLeast(1e-9)
            var m = deltaSec % p
            if (m < 0.0) m += p
            val signed = if (m <= p - m) m else m - p
            return signed * 1000.0
        }
    }
}
