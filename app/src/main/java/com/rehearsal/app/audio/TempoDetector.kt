package com.rehearsal.app.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BPM + `beats[]` по Stembeat: onset_strength + beat_track tightness 100.
 * Не линейка TrackTempo `phase+n*period`. KickGrid.refine / DrumPhase не трогаем.
 */
object TempoDetector {
    const val SAMPLE_RATE = 44100
    const val HOP = Stembeat.HOP
    /** Окно snap доли к атаке: ±12% периода, не фиксированные ±40 мс. */
    const val SNAP_WINDOW_PERIOD = 0.12

    fun analyze(pcm: StereoPcm): TrackAnalysis = Stembeat.analyze(pcm)

    internal fun novelty(pcm: StereoPcm, hop: Int): FloatArray {
        val n = pcm.samples
        val frames = ((n + hop - 1) / hop).coerceAtLeast(1)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val end = min(start + hop, n)
            var sum = 0.0
            for (i in start until end) {
                val prev = if (i > 0) i - 1 else 0
                val hpL = pcm.left[i] - pcm.left[prev]
                val hpR = pcm.right[i] - pcm.right[prev]
                sum += hpL * hpL + hpR * hpR
            }
            val count = (end - start).coerceAtLeast(1)
            energy[f] = sqrt(sum / count).toFloat()
        }
        val flux = FloatArray(frames)
        var prev = 0f
        var max = 1e-8f
        for (f in 0 until frames) {
            val v = (energy[f] - prev).coerceAtLeast(0f)
            prev = energy[f]
            flux[f] = v
            if (v > max) max = v
        }
        val inv = 1f / max
        for (f in 0 until frames) flux[f] *= inv
        if (frames == 1) return flux
        val smooth = FloatArray(frames)
        for (f in 0 until frames) {
            val a = flux[(f - 1).coerceAtLeast(0)]
            val b = flux[f]
            val c = flux[(f + 1).coerceAtMost(frames - 1)]
            smooth[f] = 0.25f * a + 0.5f * b + 0.25f * c
        }
        return smooth
    }

    internal fun estimateBpm(novelty: FloatArray, hop: Int, sampleRate: Int): Double {
        val minLag = lagForBpm(240.0, hop, sampleRate).coerceAtLeast(2)
        val maxLag = lagForBpm(40.0, hop, sampleRate).coerceAtMost(novelty.size / 2).coerceAtLeast(minLag)
        val ac = autocorr(novelty, minLag, maxLag)
        var bestLag = lagForBpm(TempoMath.DEFAULT_BPM, hop, sampleRate).coerceIn(minLag, maxLag)
        var best = Float.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var score = ac[lag]
            val two = lag * 2
            if (two <= maxLag) score += 0.5f * ac[two]
            val three = lag * 3
            if (three <= maxLag) score += 0.25f * ac[three]
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        val prev = ac.getOrElse(bestLag - 1) { ac[bestLag] }
        val center = ac[bestLag]
        val next = ac.getOrElse(bestLag + 1) { ac[bestLag] }
        val delta = parabolicDelta(prev, center, next)
        val lag = (bestLag + delta).coerceAtLeast(1.0)
        return 60.0 * sampleRate / (lag * hop)
    }

    internal fun trackBeats(
        novelty: FloatArray,
        bpm: Double,
        duration: Double,
        hop: Int,
        sampleRate: Int,
        phaseSec: Double = Double.NaN,
        periodSec: Double = 0.0,
    ): Pair<DoubleArray, Double> {
        val period = if (periodSec > 1e-9) periodSec else 60.0 / bpm.coerceAtLeast(1.0)
        val periodHops = period * sampleRate / hop
        if (periodHops < 2.0 || novelty.isEmpty() || duration <= 0.0) {
            return DoubleArray(0) to 0.0
        }
        val draft = ArrayList<Double>()
        if (phaseSec.isFinite()) {
            var t = phaseSec % period
            if (t < 0.0) t += period
            if (t > period * 0.5) t -= period
            while (t < duration - 1e-9) {
                if (t >= -0.04) draft.add(t.coerceAtLeast(0.0))
                t += period
            }
        } else {
            val offsetSteps = periodHops.roundToInt().coerceAtLeast(2)
            var bestOff = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (off in 0 until offsetSteps) {
                var score = 0.0
                var count = 0
                var pos = off.toDouble()
                while (pos < novelty.size) {
                    val i = pos.roundToInt()
                    if (i in novelty.indices) {
                        score += novelty[i]
                        count++
                    }
                    pos += periodHops
                }
                if (count > 0) score /= count
                if (score > bestScore) {
                    bestScore = score
                    bestOff = off
                }
            }
            var t = bestOff.toDouble() * hop / sampleRate
            while (t < duration - 1e-9) {
                draft.add(t)
                t += period
            }
        }
        val beats = snapTimesToNovelty(
            draft.toDoubleArray(),
            novelty,
            period,
            hop,
            sampleRate,
            duration,
        )
        return beats to (beats.firstOrNull() ?: 0.0)
    }

    /**
     * Каждую долю тянет к ближайшему novelty в ±[SNAP_WINDOW_PERIOD]*period.
     * [chainFromPeriod]: черновая линейка `phase+n*period` — следующая точка
     * от уже притянутой, иначе дрифт 120→116 уезжает из окна. Для готовых
     * `beats[]` Stembeat это выключено: шаг period затирает свою метку и
     * после одного промаха клик едет мимо бочки до конца песни.
     * Нет онсета — исходная точка.
     */
    internal fun snapTimesToNovelty(
        times: DoubleArray,
        novelty: FloatArray,
        periodSec: Double,
        hop: Int,
        sampleRate: Int,
        duration: Double,
        chainFromPeriod: Boolean = true,
    ): DoubleArray {
        if (times.isEmpty() || novelty.isEmpty() || periodSec <= 1e-9) return times
        val sr = sampleRate.coerceAtLeast(1)
        val window = (SNAP_WINDOW_PERIOD * periodSec * sr / hop).roundToInt().coerceAtLeast(1)
        var peak = 1e-8f
        for (v in novelty) if (v > peak) peak = v
        val thresh = peak * 0.12f
        val out = ArrayList<Double>(times.size)
        var last = Double.NaN
        for (t0 in times) {
            val predicted = if (chainFromPeriod && last.isFinite()) last + periodSec else t0
            val snapped = snapOneToNovelty(
                predicted,
                novelty,
                window,
                thresh,
                hop,
                sr,
                duration,
            )
            if (snapped >= duration) continue
            val prev = out.lastOrNull()
            if (prev == null || snapped - prev > periodSec * 0.4) {
                out.add(snapped)
                last = snapped
            }
        }
        return out.toDoubleArray()
    }

    private fun snapOneToNovelty(
        t0: Double,
        novelty: FloatArray,
        window: Int,
        thresh: Float,
        hop: Int,
        sampleRate: Int,
        duration: Double,
    ): Double {
        val center = (t0 * sampleRate / hop).roundToInt()
        var bestI = center
        var bestV = novelty.getOrElse(center) { 0f }
        for (d in 1..window) {
            val left = center - d
            val right = center + d
            if (left in novelty.indices && novelty[left] > bestV) {
                bestV = novelty[left]
                bestI = left
            }
            if (right in novelty.indices && novelty[right] > bestV) {
                bestV = novelty[right]
                bestI = right
            }
        }
        val useSnap = bestI != center &&
            bestV >= thresh &&
            isLocalMax(novelty, bestI)
        return if (useSnap) {
            (bestI.toDouble() * hop / sampleRate).coerceIn(0.0, duration)
        } else {
            t0.coerceIn(0.0, duration)
        }
    }

    private fun isLocalMax(x: FloatArray, i: Int): Boolean {
        if (i !in x.indices) return false
        val v = x[i]
        val left = x.getOrElse(i - 1) { v }
        val right = x.getOrElse(i + 1) { v }
        return v >= left && v >= right
    }

    private fun autocorr(x: FloatArray, minLag: Int, maxLag: Int): FloatArray {
        val n = x.size
        var mean = 0.0
        for (v in x) mean += v
        mean /= n.coerceAtLeast(1)
        val ac = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            val count = n - lag
            if (count < 8) continue
            var num = 0.0
            for (i in 0 until count) {
                num += (x[i] - mean) * (x[i + lag] - mean)
            }
            ac[lag] = (num / count).toFloat()
        }
        return ac
    }

    private fun lagForBpm(bpm: Double, hop: Int, sampleRate: Int): Int {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        return (period * sampleRate / hop).roundToInt().coerceAtLeast(1)
    }

    private fun parabolicDelta(prev: Float, center: Float, next: Float): Double {
        val denom = (prev - 2f * center + next).toDouble()
        if (abs(denom) < 1e-12) return 0.0
        return 0.5 * (prev - next) / denom
    }
}
