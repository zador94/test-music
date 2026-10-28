package com.rehearsal.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Порт `lib/track-tempo.ts` из beat-sense, функция в функцию.
 * Не микрофонный `tempo.ts`. Не угаданные тела 0.3.33/0.3.34.
 */
object TrackTempo {
    const val MIN_BPM = 55.0
    const val MAX_BPM = 210.0
    const val WINDOW_SECONDS = 12.0
    const val HOP_SECONDS = 3.0

    data class Candidate(
        val bpm: Double,
        val period: Double,
        val score: Double,
        val nextBeat: Double,
    )

    data class Estimate(
        val bpm: Double,
        val period: Double,
        val nextBeat: Double,
        val confidence: Double,
        val candidates: List<Candidate>,
        val phase: Double,
    )

    data class Result(
        val bpm: Double,
        val phaseSec: Double,
        val periodSec: Double,
        val beats: DoubleArray,
        val flux: FloatArray = FloatArray(0),
        val envelope: FloatArray = FloatArray(0),
        val confidence: Double = 0.0,
    )

    private class Flux(
        val mix: DoubleArray,
        val dt: Double,
        val time0: Double,
    )

    private class Accum(
        val values: DoubleArray,
        val minLag: Int,
        val dt: Double,
    )

    private class Choice(
        val period: Double,
        val phase: Double,
        val confidence: Double,
        val candidates: List<Candidate>,
    )

    private class FamilyLag(
        val lag: Double,
        val value: Double,
        val bpm: Double,
    )

    fun tempoOfTrack(pcm: StereoPcm): Result {
        val n = pcm.samples
        val sr = pcm.sampleRate
        val duration = n.toDouble() / sr.coerceAtLeast(1)
        val mono = FloatArray(n) { i -> 0.5f * (pcm.left[i] + pcm.right[i]) }
        val estimate = tempoOfTrack(mono, sr) ?: return fallback(duration)
        val period = estimate.period
        val phase = positiveMod(estimate.phase, period)
        return Result(
            bpm = estimate.bpm,
            phaseSec = phase,
            periodSec = period,
            beats = beatTimes(estimate.phase, period, duration),
            confidence = estimate.confidence,
        )
    }

    fun tempoOfTrack(pcm: FloatArray, sampleRate: Int): Estimate? {
        if (sampleRate < 8000 || pcm.size < sampleRate * 4) return null
        val flux = spectralFlux(pcm, sampleRate) ?: return null
        val envelope = emphasizePeaks(flux.mix)
        val accum = accumulateCorrelation(envelope, flux.dt) ?: return null
        val chosen = choosePeriod(accum, envelope, flux) ?: return null
        val end = max(4.0, pcm.size.toDouble() / sampleRate - 0.05)
        var nextBeat = chosen.phase
        if (nextBeat < end) {
            val steps = ceil((end - nextBeat) / chosen.period - 1e-12).toInt()
            if (steps > 0) nextBeat += steps * chosen.period
        }
        return Estimate(
            bpm = 60.0 / chosen.period,
            period = chosen.period,
            nextBeat = nextBeat,
            confidence = chosen.confidence,
            candidates = chosen.candidates,
            phase = chosen.phase,
        )
    }

    private fun spectralFlux(pcm: FloatArray, sampleRate: Int): Flux? {
        val targetRate = if (sampleRate >= 15000) 16000 else sampleRate
        val audio = decimate(pcm, sampleRate, targetRate)
        val rate = targetRate
        val nfft = 512
        val hop = max(1, jsRound(rate * 0.01))
        if (audio.size < nfft * 2) return null

        val hann = DoubleArray(nfft) { i ->
            0.5 * (1.0 - cos((2.0 * PI * i) / (nfft - 1)))
        }

        val low0 = max(1, floor((40.0 * nfft) / rate).toInt())
        val low1 = max(low0 + 1, floor((200.0 * nfft) / rate).toInt())
        val mid1 = min(nfft / 2 - 1, floor((2800.0 * nfft) / rate).toInt())

        val frameCount = floor((audio.size - nfft).toDouble() / hop).toInt()
        if (frameCount < 200) return null

        val mix = DoubleArray(frameCount)
        val prev = DoubleArray(nfft / 2)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)

        for (frame in 0 until frameCount) {
            val offset = frame * hop
            for (i in 0 until nfft) {
                re[i] = audio[offset + i].toDouble() * hann[i]
                im[i] = 0.0
            }
            transform(re, im)

            var low = 0.0
            var mid = 0.0
            for (k in low0..mid1) {
                val power = re[k] * re[k] + im[k] * im[k]
                val mag = ln(1.0 + power)
                val diff = mag - prev[k]
                prev[k] = mag
                if (diff <= 0.0) continue
                if (k <= low1) low += diff else mid += diff
            }
            mix[frame] = if (frame == 0) 0.0 else low + mid
        }

        return Flux(mix = mix, dt = hop.toDouble() / rate, time0 = nfft / 2.0 / rate)
    }

    internal fun emphasizePeaks(x: DoubleArray): DoubleArray {
        val radius = 12
        val out = DoubleArray(x.size)
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        for (i in x.indices) {
            val a = max(0, i - radius)
            val b = min(x.size - 1, i + radius)
            val local = (prefix[b + 1] - prefix[a]) / (b - a + 1)
            out[i] = max(0.0, x[i] - local)
        }
        return out
    }

    private fun accumulateCorrelation(envelope: DoubleArray, dt: Double): Accum? {
        val minLag = max(1, jsRound(60.0 / MAX_BPM / dt))
        val maxLag = min(envelope.size - 2, jsRound(60.0 / MIN_BPM / dt))
        if (maxLag <= minLag + 4) return null

        val win = max(maxLag * 3, jsRound(WINDOW_SECONDS / dt))
        val hop = max(1, jsRound(HOP_SECONDS / dt))
        val starts = ArrayList<Int>()
        if (envelope.size <= win) {
            starts.add(0)
        } else {
            var start = 0
            while (start + win <= envelope.size) {
                starts.add(start)
                start += hop
            }
            val tail = envelope.size - win
            if (starts.last() != tail) starts.add(tail)
        }

        val values = DoubleArray(maxLag - minLag + 1)
        var used = 0

        for (start in starts) {
            val length = min(win, envelope.size - start)
            if (length < maxLag * 2) continue
            val slice = envelope.copyOfRange(start, start + length)
            var energy = 0.0
            for (v in slice) energy += v
            if (energy < 0.5) continue

            val ac = correlate(slice, minLag, maxLag)
            val weight = sqrt(energy)
            for (i in values.indices) values[i] += weight * ac[i]
            used++
        }

        if (used == 0) return null
        for (i in values.indices) values[i] /= used.toDouble()
        return Accum(values = values, minLag = minLag, dt = dt)
    }

    private fun correlate(x: DoubleArray, minLag: Int, maxLag: Int): DoubleArray {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        val y = DoubleArray(x.size) { i -> x[i] - mean }
        val out = DoubleArray(maxLag - minLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var left = 0.0
            var right = 0.0
            val limit = y.size - lag
            for (i in 0 until limit) {
                val a = y[i]
                val b = y[i + lag]
                sum += a * b
                left += a * a
                right += b * b
            }
            out[lag - minLag] = if (left > 1e-12 && right > 1e-12) sum / sqrt(left * right) else 0.0
        }
        return out
    }

    private fun choosePeriod(accum: Accum, envelope: DoubleArray, flux: Flux): Choice? {
        val values = accum.values
        val minLag = accum.minLag
        val dt = accum.dt
        var bestIndex = 0
        for (i in 1 until values.size) {
            if (values[i] > values[bestIndex]) bestIndex = i
        }
        val bestValue = values[bestIndex]
        if (!(bestValue > 0.05)) return null

        val sorted = values.copyOf()
        sorted.sort()
        val median = sorted[floor(sorted.size / 2.0).toInt()]
        val prominence = bestValue / max(median, 1e-6)
        if (prominence < 1.2) return null

        val peak = refinePeak(values, bestIndex)
        val bestLag = minLag + peak.first
        val family = octaveFamily(bestLag, values, minLag, dt)
        if (family.isEmpty()) return null

        family.sortBy { it.bpm }
        var chosen = family[0]
        for (i in 0 until family.size - 1) {
            val slower = family[i]
            val faster = family[i + 1]
            if (slower !== chosen) continue
            if (abs(faster.bpm / slower.bpm - 2.0) > 0.08) continue
            val offbeatRatio = medianOffbeatRatio(envelope, flux, slower.lag * dt)
            val eighthsOfAComfortableTempo = slower.bpm >= 85.0 && slower.bpm <= 145.0 && faster.bpm > 185.0
            val offbeatNeeded = if (eighthsOfAComfortableTempo) 0.7 else 0.48
            if (offbeatRatio >= offbeatNeeded && faster.value >= slower.value * 0.5) chosen = faster
        }

        val coarse = chosen.lag * dt
        val coarsePhase = align(envelope, coarse, flux).phase
        val refined = refineTiming(envelope, flux, coarse, coarsePhase)
        val chosenPeriod = refined.first
        val chosenPhase = refined.second
        val candidates = family.map { item ->
            val itemPeriod = item.lag * dt
            val itemPhase = align(envelope, itemPeriod, flux).phase
            Candidate(
                bpm = 60.0 / itemPeriod,
                period = itemPeriod,
                score = item.value,
                nextBeat = itemPhase,
            )
        }.toMutableList()
        candidates.sortByDescending { it.score }
        val primaryIndex = candidates.indexOfFirst { abs(it.period - chosenPeriod) / chosenPeriod < 0.04 }
        if (primaryIndex > 0) {
            val primary = candidates.removeAt(primaryIndex)
            candidates.add(0, primary)
        }

        return Choice(
            period = chosenPeriod,
            phase = chosenPhase,
            confidence = clamp01((prominence - 1.2) / 3.5),
            candidates = candidates,
        )
    }

    private fun refinePeak(values: DoubleArray, index: Int): Pair<Double, Double> {
        if (index <= 0 || index >= values.size - 1) return index.toDouble() to values[index]
        val a = values[index - 1]
        val b = values[index]
        val c = values[index + 1]
        val denom = a - 2.0 * b + c
        val delta = if (denom == 0.0) 0.0 else max(-0.5, min(0.5, (0.5 * (a - c)) / denom))
        return (index + delta) to b
    }

    private fun octaveFamily(
        bestLag: Double,
        values: DoubleArray,
        minLag: Int,
        dt: Double,
    ): MutableList<FamilyLag> {
        val maxLag = minLag + values.size - 1
        val best = lookup(values, minLag, maxLag, bestLag) ?: return mutableListOf()
        val found = ArrayList<FamilyLag>()
        for (factor in doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)) {
            val near = lookup(values, minLag, maxLag, bestLag * factor) ?: continue
            if (near.second < best.second * 0.58) continue
            val bpm = 60.0 / (near.first * dt)
            if (bpm < MIN_BPM || bpm > MAX_BPM) continue
            if (found.any { abs(it.bpm - bpm) / bpm < 0.04 }) continue
            found.add(FamilyLag(lag = near.first, value = near.second, bpm = bpm))
        }
        return found
    }

    private fun lookup(
        values: DoubleArray,
        minLag: Int,
        maxLag: Int,
        lag: Double,
    ): Pair<Double, Double>? {
        val center = jsRound(lag)
        var bestLag = -1
        var best = Double.NEGATIVE_INFINITY
        for (delta in -2..2) {
            val index = center + delta
            if (index < minLag || index > maxLag) continue
            val value = values[index - minLag]
            if (value > best) {
                best = value
                bestLag = index
            }
        }
        if (bestLag < 0) return null
        val refined = refinePeak(values, bestLag - minLag)
        return (minLag + refined.first) to max(best, refined.second)
    }

    private fun medianOffbeatRatio(envelope: DoubleArray, flux: Flux, period: Double): Double {
        val win = max(8, jsRound(8.0 / flux.dt))
        val hop = max(1, jsRound(4.0 / flux.dt))
        val ratios = ArrayList<Double>()
        val starts = ArrayList<Int>()
        if (envelope.size <= win) {
            starts.add(0)
        } else {
            var start = 0
            while (start + win <= envelope.size) {
                starts.add(start)
                start += hop
            }
        }

        for (start in starts) {
            val length = min(win, envelope.size - start)
            val slice = envelope.copyOfRange(start, start + length)
            val sliceFlux = Flux(mix = slice, dt = flux.dt, time0 = flux.time0 + start * flux.dt)
            val aligned = align(slice, period, sliceFlux)
            if (aligned.beat < 1e-4) continue
            ratios.add(aligned.offbeat / aligned.beat)
        }

        if (ratios.isEmpty()) return 0.0
        ratios.sort()
        return ratios[floor(ratios.size / 2.0).toInt()]
    }

    private fun refineTiming(
        envelope: DoubleArray,
        flux: Flux,
        period: Double,
        phase: Double,
    ): Pair<Double, Double> {
        var maxV = 0.0
        for (v in envelope) if (v > maxV) maxV = v
        val threshold = maxV * 0.35
        val times = ArrayList<Double>()
        for (i in 1 until envelope.size - 1) {
            if (envelope[i] < threshold) continue
            if (envelope[i] < envelope[i - 1] || envelope[i] <= envelope[i + 1]) continue
            times.add(flux.time0 + i * flux.dt)
        }

        var count = 0
        var sumN = 0.0
        var sumT = 0.0
        var sumNN = 0.0
        var sumNT = 0.0
        for (time in times) {
            val index = jsRound((time - phase) / period).toDouble()
            val expected = phase + index * period
            if (abs(time - expected) > period * 0.12) continue
            count++
            sumN += index
            sumT += time
            sumNN += index * index
            sumNT += index * time
        }

        val denom = count * sumNN - sumN * sumN
        if (count < 8 || denom == 0.0) return period to phase
        val slope = (count * sumNT - sumN * sumT) / denom
        val intercept = (sumT - slope * sumN) / count
        if (!(slope > period * 0.985 && slope < period * 1.015)) return period to phase
        val wrapped = ((intercept % slope) + slope) % slope
        return slope to wrapped
    }

    private class Aligned(val phase: Double, val beat: Double, val offbeat: Double)

    private fun align(envelope: DoubleArray, period: Double, flux: Flux): Aligned {
        val steps = max(8, jsRound(period / flux.dt))
        var bestPhase = flux.time0
        var bestBeat = Double.NEGATIVE_INFINITY
        var bestOff = 0.0

        for (step in 0 until steps) {
            val offset = (step.toDouble() / steps) * period
            var beat = 0.0
            var offbeat = 0.0
            var hits = 0
            var time = flux.time0 + offset
            val end = flux.time0 + envelope.size * flux.dt
            while (time < end) {
                beat += readEnvelope(envelope, flux, time)
                offbeat += readEnvelope(envelope, flux, time + period / 2.0)
                hits++
                time += period
            }
            if (hits == 0) continue
            beat /= hits
            offbeat /= hits
            if (beat > bestBeat) {
                bestBeat = beat
                bestOff = offbeat
                bestPhase = flux.time0 + offset
            }
        }

        return Aligned(phase = bestPhase, beat = max(0.0, bestBeat), offbeat = max(0.0, bestOff))
    }

    private fun readEnvelope(envelope: DoubleArray, flux: Flux, time: Double): Double {
        val index = (time - flux.time0) / flux.dt
        val i = floor(index).toInt()
        val frac = index - i
        if (i < 0 || i >= envelope.size - 1) return 0.0
        return envelope[i] * (1.0 - frac) + envelope[i + 1] * frac
    }

    private fun decimate(pcm: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= toRate * 1.02) return pcm
        val step = fromRate.toDouble() / toRate
        val length = floor(pcm.size / step).toInt()
        val out = FloatArray(length)
        for (i in 0 until length) {
            val start = floor(i * step).toInt()
            val end = min(pcm.size, floor((i + 1) * step).toInt())
            var sum = 0.0
            for (j in start until end) sum += pcm[j]
            out[i] = (sum / max(1, end - start)).toFloat()
        }
        return out
    }

    private fun clamp01(value: Double): Double {
        if (value < 0.0) return 0.0
        if (value > 1.0) return 1.0
        return value
    }

    /** JS `Math.round`: к ближайшему, ничья к +∞. */
    internal fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        var i = 1
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val swapRe = re[i]
                re[i] = re[j]
                re[j] = swapRe
                val swapIm = im[i]
                im[i] = im[j]
                im[j] = swapIm
            }
            i++
        }

        var length = 2
        while (length <= n) {
            val angle = (-2.0 * PI) / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = length shr 1
                for (jj in 0 until half) {
                    val evenRe = re[start + jj]
                    val evenIm = im[start + jj]
                    val oddRe = re[start + jj + half] * curRe - im[start + jj + half] * curIm
                    val oddIm = re[start + jj + half] * curIm + im[start + jj + half] * curRe
                    re[start + jj] = evenRe + oddRe
                    im[start + jj] = evenIm + oddIm
                    re[start + jj + half] = evenRe - oddRe
                    im[start + jj + half] = evenIm - oddIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    /** Как `transform` в TS; оставлено для старых вызовов. */
    fun fft(re: DoubleArray, im: DoubleArray) = transform(re, im)

    private fun beatTimes(phaseSec: Double, periodSec: Double, duration: Double): DoubleArray {
        if (periodSec <= 1e-6 || duration <= 0.0) return DoubleArray(0)
        val out = ArrayList<Double>()
        var t = positiveMod(phaseSec, periodSec)
        if (t > periodSec * 0.5) t -= periodSec
        while (t < duration - 1e-9) {
            if (t >= -0.04) out.add(t.coerceAtLeast(0.0))
            t += periodSec
        }
        return out.toDoubleArray()
    }

    private fun fallback(duration: Double): Result {
        val bpm = TempoMath.DEFAULT_BPM
        val period = 60.0 / bpm
        return Result(bpm, 0.0, period, beatTimes(0.0, period, duration))
    }

    private fun positiveMod(value: Double, modulus: Double): Double {
        if (modulus <= 0.0 || !value.isFinite()) return 0.0
        val m = value % modulus
        return if (m < 0.0) m + modulus else m
    }
}
