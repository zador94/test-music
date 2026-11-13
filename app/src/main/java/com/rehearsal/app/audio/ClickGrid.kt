package com.rehearsal.app.audio

import android.util.Log
import com.rehearsal.app.player.PlaybackMath
import java.util.Arrays
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Совместный поиск сетки: одна функция атаки и перебор пар (период, фаза).
 *
 * Атака — полуволновой поток по хопам [HOP] (~4 мс на 44.1 кГц).
 * Из потока вычитается локальная медиана: гул сайдчейна не держит пол.
 * Смесь: drums 0.6 (низ 0.55 / середина 0.30 / щелчок 0.15), bass 0.25, other 0.15.
 * Темп — топ-3 периода автокорреляции в 65–165 BPM, кратности 0.5x / 1x / 2x.
 * Фаза — сдвиг с шагом 2 мс, чья сетка собирает больше энергии в окне σ = 15 мс.
 * Каждый 4-й удар на совместной атаке bass и other получает +30%.
 * Октаву бочки восьмая и половинный темп не подменяют: это решает автокорреляция низа.
 * Фаза уточняется параболой. periodSec — период этой сетки.
 */
object ClickGrid {
    const val VERSION = 35
    const val SAMPLE_RATE = 44100
    /** ~4.4 мс при 44.1 кГц: общий шаг функции атаки. */
    const val HOP = 192
    const val LOWPASS_HZ = 150.0
    /** Выше этого щелчок атаки, ниже — корпус малого. */
    const val BODY_HZ = 800.0

    private const val SEARCH_MIN_BPM = 65.0
    private const val SEARCH_MAX_BPM = 165.0
    private const val PHASE_STEP_SEC = 0.002
    private const val SIGMA_SEC = 0.015
    private const val DOWNBEAT_BONUS = 1.30
    private const val STRONG_PEAK_RATIO = 0.90f
    private const val DRUMS_W = 0.60
    private const val BASS_W = 0.25
    private const val OTHER_W = 0.15
    private const val LOW_W = 0.55
    private const val MID_W = 0.30
    private const val HIGH_W = 0.15
    /**
     * Энергия посередине медленного периода / энергия на его ударах.
     * Ниже — восьмая (короче и слабее четверти). Выше — бочка на каждой четверти,
     * а медленный пик это половинный темп.
     */
    private const val OFFBEAT_IS_BEAT = 0.80
    private const val DIAG = "GRID_DIAG"

    fun isCurrent(analysis: TrackAnalysis?): Boolean {
        if (analysis == null || analysis.kickGridVersion < VERSION) return false
        val bpm = analysis.bpm
        if (!bpm.isFinite() || bpm < MetronomeClickEngine.MIN_BPM || bpm > MetronomeClickEngine.MAX_BPM) {
            return false
        }
        return analysis.phaseSec.isFinite() && analysis.hasClickPeriod()
    }

    /**
     * [hintBpm] — темп микса, только если по атакам периода нет.
     * Уже найденный период hint не удваивает и не замедляет.
     */
    fun fromPcm(pcm: StereoPcm, hintBpm: Float? = null): TrackAnalysis {
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val duration = pcm.samples.toDouble() / sr
        val low = FloatArray(((pcm.samples + HOP - 1) / HOP).coerceAtLeast(1))
        val mid = FloatArray(low.size)
        val high = FloatArray(low.size)
        val split = BandSplit(sr)
        var frame = 0
        var count = 0
        var lowAcc = 0.0
        var midAcc = 0.0
        var highAcc = 0.0
        val n = pcm.samples
        for (i in 0 until n) {
            val x = 0.5f * (pcm.left[i] + pcm.right[i])
            val band = split.step(x)
            lowAcc += band.low * band.low
            midAcc += band.mid * band.mid
            highAcc += band.high * band.high
            count++
            if (count == HOP) {
                low[frame] = sqrt(lowAcc / count).toFloat()
                mid[frame] = sqrt(midAcc / count).toFloat()
                high[frame] = sqrt(highAcc / count).toFloat()
                frame++
                count = 0
                lowAcc = 0.0
                midAcc = 0.0
                highAcc = 0.0
                if (frame >= low.size) break
            }
        }
        if (count > 0 && frame < low.size) {
            low[frame] = sqrt(lowAcc / count).toFloat()
            mid[frame] = sqrt(midAcc / count).toFloat()
            high[frame] = sqrt(highAcc / count).toFloat()
        }
        return fromBands(low, mid, high, sr, duration, hintBpm)
    }

    fun fromEnergy(
        energy: FloatArray,
        sampleRate: Int,
        durationSec: Double,
        hintBpm: Float? = null,
    ): TrackAnalysis = fromBands(energy, null, null, sampleRate, durationSec, hintBpm)

    fun fromBands(
        low: FloatArray,
        mid: FloatArray?,
        high: FloatArray?,
        sampleRate: Int,
        durationSec: Double,
        hintBpm: Float? = null,
        lead: FloatArray? = null,
        bass: FloatArray? = null,
        other: FloatArray? = null,
    ): TrackAnalysis {
        val sr = sampleRate.coerceAtLeast(1)
        val duration = if (durationSec.isFinite() && durationSec > 0.0) {
            durationSec
        } else {
            low.size * HOP.toDouble() / sr
        }
        val hint = hintBpm?.takeIf { it.isFinite() && it > 0f }
        val hopSec = HOP.toDouble() / sr
        val drums = drumsActivation(low, mid, high, hopSec)
        val kick = normalize(onsetFlux(low, hopSec))
        val bassOnset = onsetOf(bass ?: lead, drums.size, hopSec)
        val otherOnset = onsetOf(other, drums.size, hopSec)
        val activation = mixActivation(drums, bassOnset, otherOnset)
        val fit = searchGrid(activation, kick, bassOnset, otherOnset, hopSec, duration)
        val period = fit?.periodSec ?: (60.0 / (hint?.toDouble() ?: MetronomeClickEngine.DEFAULT_BPM.toDouble()))
        val phase = positiveMod(fit?.phaseSec ?: 0.0, period)
        val bpm = PlaybackMath.committedOriginalBpm((60.0 / period).toFloat())
        Log.d(
            DIAG,
            "bpm=${diagNum(bpm.toDouble())} period=${diagNum(period)} phase=${diagNum(phase)} " +
                "kickBpm=${diagNum(fit?.kickBpm)}",
        )
        return TrackAnalysis(
            bpm = bpm,
            beats = TrackAnalysis.quarterBeats(period, duration, phase),
            downbeatOffset = 0.0,
            duration = duration,
            sampleRate = sr,
            kickPhaseSec = phase,
            kickGridVersion = VERSION,
            periodSec = period,
            phaseSec = phase,
        )
    }

    /** Один проход фильтра. Низ, корпус малого, щелчок атаки. */
    class BandSplit(sampleRate: Int) {
        private val aLow = alpha(LOWPASS_HZ, sampleRate)
        private val aBody = alpha(BODY_HZ, sampleRate)
        private var lpLow = 0f
        private var lpBody = 0f

        fun step(mono: Float): Band {
            lpLow += aLow * (mono - lpLow)
            lpBody += aBody * (mono - lpBody)
            return Band(lpLow, lpBody - lpLow, mono - lpBody)
        }

        private fun alpha(cutoffHz: Double, sampleRate: Int): Float {
            val sr = sampleRate.coerceAtLeast(1)
            return (1.0 - exp(-2.0 * PI * cutoffHz / sr)).toFloat()
        }
    }

    class Band(val low: Float, val mid: Float, val high: Float)

    /** Энергия по хопам. Низ — бочка, середина — корпус малого, верх — щелчок. */
    fun hopEnergies(wav: MappedWav): HopEnergies {
        val hop = HOP
        val sr = wav.info.sampleRate.coerceAtLeast(1)
        val n = wav.info.frameCount
        if (n <= 0) return HopEnergies(FloatArray(0), FloatArray(0), FloatArray(0))
        val frames = (n + hop - 1) / hop
        val low = FloatArray(frames)
        val mid = FloatArray(frames)
        val high = FloatArray(frames)
        val split = BandSplit(sr)
        val chunk = 4096
        var pos = 0
        var frame = 0
        var count = 0
        var lowAcc = 0.0
        var midAcc = 0.0
        var highAcc = 0.0
        while (pos < n && frame < frames) {
            val take = minOf(chunk, n - pos)
            val slice = wav.readRange(pos, take)
            val m = minOf(take, slice.samples)
            for (i in 0 until m) {
                val band = split.step(0.5f * (slice.left[i] + slice.right[i]))
                lowAcc += band.low * band.low
                midAcc += band.mid * band.mid
                highAcc += band.high * band.high
                count++
                if (count == hop) {
                    low[frame] = sqrt(lowAcc / count).toFloat()
                    mid[frame] = sqrt(midAcc / count).toFloat()
                    high[frame] = sqrt(highAcc / count).toFloat()
                    frame++
                    count = 0
                    lowAcc = 0.0
                    midAcc = 0.0
                    highAcc = 0.0
                    if (frame >= frames) break
                }
            }
            pos += take
        }
        if (count > 0 && frame < frames) {
            low[frame] = sqrt(lowAcc / count).toFloat()
            mid[frame] = sqrt(midAcc / count).toFloat()
            high[frame] = sqrt(highAcc / count).toFloat()
        }
        return HopEnergies(low, mid, high)
    }

    class HopEnergies(val low: FloatArray, val mid: FloatArray, val high: FloatArray)

    private data class Fit(
        val periodSec: Double,
        val phaseSec: Double,
        val score: Double,
        val kickBpm: Double?,
    )

    private fun searchGrid(
        activation: FloatArray,
        kickOnset: FloatArray,
        bass: FloatArray?,
        other: FloatArray?,
        hopSec: Double,
        duration: Double,
    ): Fit? {
        if (activation.size < 32 || hopSec <= 0.0 || peakOf(activation) < 1e-6f) return null
        val wide = lagRange(hopSec, activation.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM * 2.0)
        if (wide == null) return null
        val corr = autocorr(activation, wide.first, wide.second)
        val kick = kickPeriod(kickOnset, hopSec)
        val periods = withKick(topPeriods(corr, hopSec, wide.first, wide.second), kick)
        if (periods.isEmpty()) return null
        val smooth = gaussianSmooth(activation, hopSec, SIGMA_SEC)
        val smoothBass = bass?.let { gaussianSmooth(it, hopSec, SIGMA_SEC) }
        val smoothOther = other?.let { gaussianSmooth(it, hopSec, SIGMA_SEC) }
        val bassGate = (peakOf(smoothBass) * 0.40f)
        val otherGate = (peakOf(smoothOther) * 0.40f)
        val fits = ArrayList<Fit>(periods.size)
        for (period in periods) {
            val phase = bestPhase(smooth, smoothBass, smoothOther, bassGate, otherGate, period, hopSec, duration)
            fits.add(Fit(period, phase.phase, phase.score, kick))
        }
        return choose(fits, kick)
    }

    /**
     * Сумма попаданий выбирает период, кроме октавы бочки.
     * Восьмая (период бочки / 2) и половинный темп (период × 2) остаются на периоде низа:
     * сумма ударов взяла бы более частую сетку, а громкое тело на 1 и 3 — более редкую.
     */
    private fun choose(fits: List<Fit>, kickBpm: Double?): Fit {
        val best = fits.maxBy { it.score }
        if (kickBpm == null || kickBpm <= 0.0) return best
        val kickPeriod = 60.0 / kickBpm
        val kickFit = fits.minBy { abs(it.periodSec - kickPeriod) }
        if (!near(kickFit.periodSec, kickPeriod, 0.03)) return best
        val octave = near(best.periodSec, kickPeriod / 2.0, 0.03) || near(best.periodSec, kickPeriod * 2.0, 0.03)
        return if (octave) kickFit else best
    }

    private fun kickPeriod(drums: FloatArray, hopSec: Double): Double? {
        if (peakOf(drums) < 1e-6f) return null
        val range = lagRange(hopSec, drums.size, SEARCH_MIN_BPM, SEARCH_MAX_BPM) ?: return null
        val corr = autocorr(drums, range.first, range.second)
        val lag = octaveLag(corr, drums, hopSec, range.first, range.second)
        if (lag < 0 || corr[lag] <= 0f) return null
        val refined = (lag + parabolic(corr, lag, range.first, range.second)) * hopSec
        val bpm = 60.0 / refined
        if (bpm < SEARCH_MIN_BPM || bpm > SEARCH_MAX_BPM) return null
        return bpm
    }

    /** Период низа остаётся в тройке, даже если сумма атак громче на октаве. */
    private fun withKick(periods: List<Double>, kickBpm: Double?): List<Double> {
        if (kickBpm == null || kickBpm <= 0.0) return periods
        val kickPeriod = 60.0 / kickBpm
        if (periods.any { near(it, kickPeriod, 0.03) }) return periods
        val out = ArrayList<Double>(3)
        out.addAll(periods.take(2))
        out.add(kickPeriod)
        return out
    }

    private fun topPeriods(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): List<Double> {
        val found = ArrayList<Pair<Double, Float>>()
        for (lag in (minLag + 1) until maxLag) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val period = (lag + parabolic(corr, lag, minLag, maxLag)) * hopSec
            if (period <= 1e-6) continue
            val bpm = 60.0 / period
            for (factor in doubleArrayOf(0.5, 1.0, 2.0)) {
                val folded = bpm * factor
                if (folded < SEARCH_MIN_BPM || folded > SEARCH_MAX_BPM) continue
                val foldedPeriod = refinePeriod(corr, 60.0 / folded, hopSec, minLag, maxLag)
                val foldedBpm = 60.0 / foldedPeriod
                if (foldedBpm < SEARCH_MIN_BPM || foldedBpm > SEARCH_MAX_BPM) continue
                found.add(foldedPeriod to tempoScore(corr, foldedBpm, hopSec, minLag, maxLag))
            }
        }
        found.sortByDescending { it.second }
        val picked = ArrayList<Double>(3)
        for (item in found) {
            if (picked.any { near(it, item.first, 0.02) }) continue
            picked.add(item.first)
            if (picked.size == 3) break
        }
        return picked
    }

    private fun tempoScore(
        corr: FloatArray,
        bpm: Double,
        hopSec: Double,
        minLag: Int,
        maxLag: Int,
    ): Float {
        fun at(value: Double): Float {
            if (value < 20.0 || value > 400.0) return 0f
            val lag = (60.0 / value / hopSec).roundToInt()
            if (lag !in minLag..maxLag || lag !in corr.indices) return 0f
            return corr[lag].coerceAtLeast(0f)
        }
        return at(bpm) + 0.5f * at(bpm / 2.0) + 0.5f * at(bpm * 2.0)
    }

    private fun refinePeriod(
        corr: FloatArray,
        period: Double,
        hopSec: Double,
        minLag: Int,
        maxLag: Int,
    ): Double {
        val guess = (period / hopSec).roundToInt().coerceIn(minLag, maxLag)
        val window = (guess * 0.02).toInt().coerceAtLeast(1)
        var best = guess
        val from = (guess - window).coerceAtLeast(minLag)
        val to = (guess + window).coerceAtMost(maxLag)
        for (lag in from..to) {
            if (corr[lag] > corr[best]) best = lag
        }
        return (best + parabolic(corr, best, minLag, maxLag)) * hopSec
    }

    private data class PhaseScore(val phase: Double, val score: Double)

    private fun bestPhase(
        activation: FloatArray,
        bass: FloatArray?,
        other: FloatArray?,
        bassGate: Float,
        otherGate: Float,
        period: Double,
        hopSec: Double,
        duration: Double,
    ): PhaseScore {
        if (period <= 1e-6) return PhaseScore(0.0, 0.0)
        val steps = floor(period / PHASE_STEP_SEC).toInt().coerceAtLeast(1)
        val scores = DoubleArray(steps)
        var bestI = 0
        for (step in 0 until steps) {
            val phase = step * PHASE_STEP_SEC
            val score = gridScore(activation, bass, other, bassGate, otherGate, phase, period, hopSec, duration)
            scores[step] = score
            if (score > scores[bestI]) bestI = step
        }
        val prev = scores[(bestI - 1 + steps) % steps]
        val next = scores[(bestI + 1) % steps]
        val delta = parabolic3(prev, scores[bestI], next)
        val phase = positiveMod((bestI + delta) * PHASE_STEP_SEC, period)
        return PhaseScore(phase, scores[bestI])
    }

    private fun gridScore(
        activation: FloatArray,
        bass: FloatArray?,
        other: FloatArray?,
        bassGate: Float,
        otherGate: Float,
        phase: Double,
        period: Double,
        hopSec: Double,
        duration: Double,
    ): Double {
        var energy = 0.0
        var downbeats = 0
        var chords = 0
        var k = 0
        var t = phase
        while (t < duration - 1e-6) {
            energy += sample(activation, t, hopSec)
            if (k % 4 == 0 && bass != null && other != null) {
                downbeats++
                if (sample(bass, t, hopSec) >= bassGate && sample(other, t, hopSec) >= otherGate) {
                    chords++
                }
            }
            k++
            t += period
        }
        if (downbeats >= 4 && chords * 2 >= downbeats) energy *= DOWNBEAT_BONUS
        return energy
    }

    private fun drumsActivation(
        low: FloatArray,
        mid: FloatArray?,
        high: FloatArray?,
        hopSec: Double,
    ): FloatArray {
        val n = low.size
        val parts = ArrayList<Pair<FloatArray, Double>>(3)
        parts.add(normalize(onsetFlux(low, hopSec)) to LOW_W)
        if (mid != null && mid.size == n) parts.add(normalize(onsetFlux(mid, hopSec)) to MID_W)
        if (high != null && high.size == n) parts.add(normalize(onsetFlux(high, hopSec)) to HIGH_W)
        var weight = 0.0
        for (part in parts) weight += part.second
        val out = FloatArray(n)
        if (weight <= 0.0) return out
        for (i in 0 until n) {
            var sum = 0.0
            for (part in parts) sum += part.second * part.first[i]
            out[i] = (sum / weight).toFloat()
        }
        return out
    }

    private fun mixActivation(drums: FloatArray, bass: FloatArray?, other: FloatArray?): FloatArray {
        val d = normalize(drums)
        val b = bass?.takeIf { peakOf(it) >= 1e-6f }?.let { normalize(it) }
        val o = other?.takeIf { peakOf(it) >= 1e-6f }?.let { normalize(it) }
        var wd = DRUMS_W
        var wb = if (b != null) BASS_W else 0.0
        var wo = if (o != null) OTHER_W else 0.0
        if (b == null && o == null) wd = 1.0
        val weight = wd + wb + wo
        return FloatArray(drums.size) { i ->
            val bassV = b?.getOrElse(i) { 0f } ?: 0f
            val otherV = o?.getOrElse(i) { 0f } ?: 0f
            ((wd * d[i] + wb * bassV + wo * otherV) / weight).toFloat()
        }
    }

    private fun onsetOf(energy: FloatArray?, n: Int, hopSec: Double): FloatArray? {
        val fitted = fitLength(energy, n) ?: return null
        val flux = normalize(onsetFlux(fitted, hopSec))
        return if (peakOf(flux) >= 1e-6f) flux else null
    }

    /** Полуволновой поток минус локальная медиана. Пол сайдчейна уходит, удар остаётся. */
    private fun onsetFlux(energy: FloatArray, hopSec: Double): FloatArray {
        val n = energy.size
        val raw = FloatArray(n)
        var prev = 0f
        for (i in 0 until n) {
            val v = energy[i]
            val delta = v - prev
            raw[i] = if (delta > 0f) delta else 0f
            prev = v
        }
        if (n == 0) return raw
        val radius = (0.20 / hopSec).toInt().coerceIn(3, 96)
        val scratch = FloatArray(radius * 2 + 1)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = maxOf(0, i - radius)
            val b = minOf(n - 1, i + radius)
            val m = b - a + 1
            for (k in 0 until m) scratch[k] = raw[a + k]
            Arrays.sort(scratch, 0, m)
            val med = if (m % 2 == 1) {
                scratch[m / 2]
            } else {
                0.5f * (scratch[m / 2 - 1] + scratch[m / 2])
            }
            val kept = raw[i] - med
            out[i] = if (kept > 0f) kept else 0f
        }
        return out
    }

    private fun gaussianSmooth(values: FloatArray, hopSec: Double, sigma: Double): FloatArray {
        if (values.isEmpty() || sigma <= 0.0 || hopSec <= 0.0) return values
        val radius = kotlin.math.ceil(3.0 * sigma / hopSec).toInt().coerceIn(1, 64)
        val kernel = DoubleArray(radius * 2 + 1)
        for (k in -radius..radius) {
            val dt = k * hopSec / sigma
            kernel[k + radius] = exp(-0.5 * dt * dt)
        }
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var sum = 0.0
            for (k in -radius..radius) {
                val j = i + k
                if (j in values.indices) sum += values[j] * kernel[k + radius]
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    private fun sample(values: FloatArray, timeSec: Double, hopSec: Double): Double {
        if (values.isEmpty() || hopSec <= 0.0) return 0.0
        val x = timeSec / hopSec - 0.5
        val i = floor(x).toInt()
        val frac = x - i
        val left = if (i in values.indices) values[i].toDouble() else 0.0
        val right = if (i + 1 in values.indices) values[i + 1].toDouble() else 0.0
        return left * (1.0 - frac) + right * frac
    }

    private fun offbeatRatio(values: FloatArray, period: Double, hopSec: Double): Double {
        if (period <= 1e-6 || values.isEmpty()) return 1.0
        val steps = 32
        var bestPhase = 0.0
        var best = -1.0
        for (s in 0 until steps) {
            val phase = period * s / steps
            val score = meanGrid(values, phase, period, hopSec)
            if (score > best) {
                best = score
                bestPhase = phase
            }
        }
        val on = meanGrid(values, bestPhase, period, hopSec)
        val off = meanGrid(values, bestPhase + period / 2.0, period, hopSec)
        if (on <= 1e-8) return 1.0
        return off / on
    }

    private fun meanGrid(values: FloatArray, phase: Double, period: Double, hopSec: Double): Double {
        val duration = values.size * hopSec
        var t = positiveMod(phase, period)
        var sum = 0.0
        var n = 0
        while (t < duration - 1e-6) {
            sum += sample(values, t, hopSec)
            n++
            t += period
        }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * Два пика через октаву. Доля посередине медленного периода решает, кто из них четверть:
     * слабая — восьмая, почти такая же — бочка, а медленный пик половинный.
     */
    private fun octaveLag(
        corr: FloatArray,
        onset: FloatArray,
        hopSec: Double,
        minLag: Int,
        maxLag: Int,
    ): Int {
        val peaks = ArrayList<Int>()
        for (lag in (minLag + 1) until maxLag) {
            if (corr[lag] <= 0f || corr[lag] < corr[lag - 1] || corr[lag] < corr[lag + 1]) continue
            peaks.add(lag)
        }
        if (peaks.isEmpty()) return -1
        peaks.sortByDescending { corr[it] }
        val best = peaks[0]
        val second = peaks.getOrNull(1) ?: return shortestStrongPeak(corr, best, minLag, maxLag)
        if (corr[second] < STRONG_PEAK_RATIO * corr[best]) {
            return shortestStrongPeak(corr, best, minLag, maxLag)
        }
        val fast = minOf(best, second)
        val slow = maxOf(best, second)
        if (!near(slow.toDouble(), fast * 2.0, 0.04)) {
            return shortestStrongPeak(corr, best, minLag, maxLag)
        }
        val ratio = offbeatRatio(onset, slow * hopSec, hopSec)
        return if (ratio >= OFFBEAT_IS_BEAT) fast else slow
    }

    /**
     * Короткий локальный пик не слабее [STRONG_PEAK_RATIO] от максимума.
     * Лаг около половины лучшего — восьмая, его не берём.
     */
    private fun shortestStrongPeak(corr: FloatArray, best: Int, minLag: Int, maxLag: Int): Int {
        val floor = STRONG_PEAK_RATIO * corr[best]
        val shoulder = (best * 0.85).toInt()
        for (lag in minLag until shoulder) {
            if (lag <= minLag || lag >= maxLag) continue
            if (corr[lag] < corr[lag - 1] || corr[lag] < corr[lag + 1]) continue
            if (corr[lag] < floor) continue
            if (near(lag.toDouble(), best / 2.0, 0.02)) continue
            return lag
        }
        return best
    }

    private fun autocorr(x: FloatArray, minLag: Int, maxLag: Int): FloatArray {
        val n = x.size
        var mean = 0.0
        for (v in x) mean += v
        mean /= n.coerceAtLeast(1)
        val out = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            val count = n - lag
            if (count <= 8) continue
            var sum = 0.0
            for (i in 0 until count) sum += (x[i] - mean) * (x[i + lag] - mean)
            out[lag] = (sum / count).toFloat()
        }
        return out
    }

    private fun parabolic(corr: FloatArray, index: Int, minLag: Int, maxLag: Int): Double {
        if (index <= minLag || index >= maxLag) return 0.0
        return parabolic3(corr[index - 1].toDouble(), corr[index].toDouble(), corr[index + 1].toDouble())
    }

    private fun parabolic3(left: Double, mid: Double, right: Double): Double {
        val den = left - 2.0 * mid + right
        if (abs(den) < 1e-12) return 0.0
        return (0.5 * (left - right) / den).coerceIn(-0.5, 0.5)
    }

    private fun lagRange(hopSec: Double, n: Int, minBpm: Double, maxBpm: Double): Pair<Int, Int>? {
        val minLag = lagFor(maxBpm, hopSec).coerceAtLeast(2)
        val maxLag = lagFor(minBpm, hopSec).coerceAtMost(n / 2)
        if (maxLag <= minLag + 2) return null
        return minLag to maxLag
    }

    private fun lagFor(bpm: Double, hopSec: Double): Int {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        return (period / hopSec).toInt().coerceAtLeast(1)
    }

    private fun fitLength(energy: FloatArray?, n: Int): FloatArray? {
        if (energy == null || n <= 0 || energy.isEmpty()) return null
        if (energy.size == n) return energy
        val out = FloatArray(n)
        val m = minOf(n, energy.size)
        for (i in 0 until m) out[i] = energy[i]
        return out
    }

    private fun normalize(src: FloatArray): FloatArray {
        val max = peakOf(src)
        if (max < 1e-8f) return FloatArray(src.size)
        return FloatArray(src.size) { src[it] / max }
    }

    private fun peakOf(values: FloatArray?): Float {
        if (values == null) return 0f
        var max = 0f
        for (v in values) if (v > max) max = v
        return max
    }

    private fun near(a: Double, b: Double, rel: Double): Boolean {
        val scale = maxOf(abs(a), abs(b), 1e-6)
        return abs(a - b) <= rel * scale
    }

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 1e-9) return 0.0
        var m = value % period
        if (m < 0.0) m += period
        return m
    }

    private fun diagNum(value: Double?): String =
        if (value == null || !value.isFinite()) "null" else String.format(Locale.US, "%.4f", value)
}
