package com.rehearsal.app.audio

import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.player.PlaybackMath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BPM + `beats[]` по 44.1 stereo PCM через [TrackTempo] (порт beat-sense
 * `tempoOfTrack`). HT-Demucs не трогает: после decode, до/рядом с separate.
 */
object TempoDetector {
    const val SAMPLE_RATE = 44100
    const val HOP = 512

    fun analyze(pcm: StereoPcm): TrackAnalysis {
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val samples = pcm.samples
        val duration = samples.toDouble() / sr
        if (samples < sr / 2) {
            return TrackAnalysis.fallbackGrid(samples, sr)
        }
        val tracked = TrackTempo.tempoOfTrack(pcm)
        return TrackAnalysis(
            bpm = PlaybackMath.roundToTenth(tracked.bpm.toFloat()),
            beats = tracked.beats,
            downbeatOffset = tracked.phaseSec,
            timeSignature = intArrayOf(4, 4),
            duration = duration,
            sampleRate = sr,
            separator = SplitKind.HTDEMUCS_4STEM,
            stems = TrackAnalysis.DEFAULT_STEMS,
            kickPhaseSec = tracked.phaseSec,
        )
    }

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
    ): Pair<DoubleArray, Double> {
        val periodSec = 60.0 / bpm.coerceAtLeast(1.0)
        val periodHops = periodSec * sampleRate / hop
        if (periodHops < 2.0 || novelty.isEmpty() || duration <= 0.0) {
            return DoubleArray(0) to 0.0
        }
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
        val snapSec = min(0.04, periodSec * 0.25)
        val snapHops = (snapSec * sampleRate / hop).roundToInt().coerceAtLeast(1)
        val beats = ArrayList<Double>()
        var t = bestOff.toDouble() * hop / sampleRate
        while (t < duration - 1e-9) {
            val center = (t * sampleRate / hop).roundToInt()
            var bestI = center
            var bestV = novelty.getOrElse(center) { 0f }
            for (d in 1..snapHops) {
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
            val snapped = (bestI.toDouble() * hop / sampleRate).coerceIn(0.0, duration)
            if (snapped < duration) {
                val last = beats.lastOrNull()
                if (last == null || snapped - last > periodSec * 0.4) {
                    beats.add(snapped)
                }
            }
            t += periodSec
        }
        val offset = beats.firstOrNull() ?: 0.0
        return beats.toDoubleArray() to offset
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
