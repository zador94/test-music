package com.rehearsal.app.audio

import com.rehearsal.app.player.PlaybackMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Единственный разбор доли.
 *
 * Темп бочки — автокорреляция низа в 55–180 BPM. Октаву решает бочка.
 * В 4/4 бочка и малый совпадают или относятся как 2, не как 1.5.
 * Самый короткий лаг не подменяет четверть восьмой.
 * Наклон цепочки может поправить цифру не больше чем на 1.5 BPM.
 *
 * Фаза — атака drumsPhase. Окно баса за 165–230 мс до тела выключено:
 * оно сдвигало клик на −180 мс. Горб низа позже атаки — не дальше 80 мс.
 * Список долей — те же равные четверти. Клик их не читает.
 */
object ClickGrid {
    const val VERSION = 33
    const val SAMPLE_RATE = 44100
    const val HOP = 256
    const val LOWPASS_HZ = 150.0
    /** Выше этого щелчок атаки, ниже — корпус малого. */
    const val BODY_HZ = 800.0

    private const val SEARCH_MIN_BPM = 55.0
    private const val SEARCH_MAX_BPM = 180.0
    private const val STRONG_PEAK_RATIO = 0.90f
    private const val CHAIN_WINDOW_BPM = 1.5
    private const val MIN_CHAIN = 8

    fun isCurrent(analysis: TrackAnalysis?): Boolean {
        if (analysis == null || analysis.kickGridVersion < VERSION) return false
        val bpm = analysis.bpm
        if (!bpm.isFinite() || bpm < MetronomeClickEngine.MIN_BPM || bpm > MetronomeClickEngine.MAX_BPM) {
            return false
        }
        return analysis.phaseSec.isFinite() && analysis.hasClickPeriod()
    }

    /**
     * [hintBpm] — темп микса, только если по ударным темпа нет.
     * Уже найденный темп бочки hint не удваивает.
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

    @Suppress("UNUSED_PARAMETER")
    fun fromBands(
        low: FloatArray,
        mid: FloatArray?,
        high: FloatArray?,
        sampleRate: Int,
        durationSec: Double,
        hintBpm: Float? = null,
        lead: FloatArray? = null,
    ): TrackAnalysis {
        val sr = sampleRate.coerceAtLeast(1)
        val duration = if (durationSec.isFinite() && durationSec > 0.0) {
            durationSec
        } else {
            low.size * HOP.toDouble() / sr
        }
        val hint = hintBpm?.takeIf { it.isFinite() && it > 0f }
        val lowFlux = fluxOf(low)
        val kickTempo = estimateBpm(lowFlux, sr)
        val snareTempo = if (mid != null && mid.size == low.size) {
            val both = mixFlux(lowFlux, fluxOf(mid))
            estimateBpm(both, sr)
        } else {
            null
        }
        val merged = mergeTempo(kickTempo, snareTempo)
        val chosen = chooseTempo(merged, hint)
        val refineFlux = if (snareTempo != null && merged != null && snareTempo == merged && mid != null) {
            mixFlux(lowFlux, fluxOf(mid))
        } else {
            lowFlux
        }
        val chained = if (chosen != null) refineBpmByChain(refineFlux, sr, chosen) else null
        val bpm = PlaybackMath.committedOriginalBpm(
            (chained ?: chosen ?: MetronomeClickEngine.DEFAULT_BPM.toDouble()).toFloat(),
        )
        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        val drumsPhase = positiveMod(attackPhase(lowFlux, sr, bpm), period)
        val phase = positiveMod(
            refinePhase(low, drumsEnergy(low, mid, high), lead, sr, bpm, drumsPhase),
            period,
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

    private fun fluxOf(energy: FloatArray): FloatArray {
        val flux = FloatArray(energy.size)
        var prev = 0f
        for (i in energy.indices) {
            flux[i] = (energy[i] - prev).coerceAtLeast(0f)
            prev = energy[i]
        }
        return flux
    }

    /** Null — в сигнале нет ударов. */
    private fun estimateBpm(flux: FloatArray, sampleRate: Int): Double? {
        var peak = 0f
        for (v in flux) if (v > peak) peak = v
        if (peak < 1e-8f || flux.size < 32) return null
        val hopSec = HOP.toDouble() / sampleRate.coerceAtLeast(1)
        val minLag = lagFor(SEARCH_MAX_BPM, hopSec).coerceAtLeast(2)
        val maxLag = lagFor(SEARCH_MIN_BPM, hopSec).coerceAtMost(flux.size / 2)
        if (maxLag <= minLag + 1) return null
        val corr = autocorr(flux, minLag, maxLag)
        var best = minLag
        for (lag in minLag..maxLag) {
            if (corr[lag] > corr[best]) best = lag
        }
        if (corr[best] <= 0f) return null
        best = shortestStrongPeak(corr, best, minLag, maxLag)
        val lag = best + parabolic(corr, best, minLag, maxLag)
        val period = lag * hopSec
        if (period <= 1e-6) return null
        return (60.0 / period).coerceIn(SEARCH_MIN_BPM, SEARCH_MAX_BPM)
    }

    private fun mixFlux(low: FloatArray, mid: FloatArray): FloatArray {
        val n = minOf(low.size, mid.size)
        var lowMax = 1e-8f
        var midMax = 1e-8f
        for (i in 0 until n) {
            if (low[i] > lowMax) lowMax = low[i]
            if (mid[i] > midMax) midMax = mid[i]
        }
        return FloatArray(n) { i -> low[i] / lowMax + mid[i] / midMax }
    }

    /**
     * Малый двигает темп только если он ровно вдвое чаще бочки:
     * бочка на 1 и 3, малый на 2 и 4. Отношения 1.5 в 4/4 нет.
     * Громкое тело на каждой второй доле (77.5 поверх 155) четверть не замедляет.
     */
    private fun mergeTempo(kick: Double?, withSnare: Double?): Double? {
        if (kick == null) return withSnare
        if (withSnare == null) return kick
        if (ratioClose(withSnare, kick * 2.0)) return withSnare
        return kick
    }

    /** Темп ударных уже есть — hint микса его не подменяет, в том числе удвоением. */
    private fun chooseTempo(free: Double?, hintBpm: Float?): Double? {
        if (free == null) return hintBpm?.toDouble()
        return free
    }

    private fun ratioClose(a: Double, b: Double): Boolean {
        val scale = maxOf(abs(a), abs(b), 1.0)
        return abs(a - b) <= 0.015 * scale
    }

    /**
     * Короткий локальный пик не слабее [STRONG_PEAK_RATIO] от максимума.
     * Лаг около половины лучшего — восьмая (темп ×2), его не берём: четверть остаётся на [best].
     */
    private fun shortestStrongPeak(corr: FloatArray, best: Int, minLag: Int, maxLag: Int): Int {
        val floor = STRONG_PEAK_RATIO * corr[best]
        val shoulder = (best * 0.85).toInt()
        for (lag in minLag until shoulder) {
            if (lag <= minLag || lag >= maxLag) continue
            if (corr[lag] < corr[lag - 1] || corr[lag] < corr[lag + 1]) continue
            if (corr[lag] < floor) continue
            if (ratioClose(lag.toDouble(), best / 2.0)) continue
            return lag
        }
        return best
    }

    private fun refineBpmByChain(flux: FloatArray, sampleRate: Int, bpm: Double): Double {
        val hopSec = HOP.toDouble() / sampleRate.coerceAtLeast(1)
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val chain = longestChain(peakTimes(flux, period, hopSec), period) ?: return bpm
        val line = fit(chain)
        if (line.period <= 1e-6) return bpm
        val next = 60.0 / line.period
        if (!next.isFinite() || abs(next - bpm) > CHAIN_WINDOW_BPM) return bpm
        return next
    }

    /**
     * Фаза остаётся на атаке. Окно баса/other за 165–230 мс до тела выключено.
     * Горб низа: не дальше 80 мс после атаки, и только если он в пределах 120 мс.
     */
    private fun drumsEnergy(low: FloatArray, mid: FloatArray?, high: FloatArray?): FloatArray {
        val n = low.size
        return FloatArray(n) { i ->
            low[i] + (mid?.getOrElse(i) { 0f } ?: 0f) + (high?.getOrElse(i) { 0f } ?: 0f)
        }
    }

    private fun refinePhase(
        lowEnergy: FloatArray,
        drumsEnergy: FloatArray,
        lead: FloatArray?,
        sampleRate: Int,
        bpm: Float,
        drumsPhase: Double,
    ): Double {
        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        val hopSec = HOP.toDouble() / sampleRate.coerceAtLeast(1)
        val early = earlierInWindow(fluxOf(drumsEnergy), lead, period, hopSec, drumsPhase)
        if (early != null) return early
        val body = attackPhase(lowEnergy, sampleRate, bpm)
        val late = positiveMod(body - drumsPhase, period)
        if (late !in 0.060..0.120) return drumsPhase
        return positiveMod(drumsPhase + minOf(late, 0.080), period)
    }

    /** Сдвиг −180 мс выключен: фаза остаётся на атаке drumsPhase, не на басе в этом окне. */
    @Suppress("UNUSED_PARAMETER")
    private fun earlierInWindow(
        lowFlux: FloatArray,
        lead: FloatArray?,
        period: Double,
        hopSec: Double,
        drumsPhase: Double,
    ): Double? = null

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

    /** Сдвиг внутри периода, где атаки низа складываются громче всего. */
    private fun attackPhase(flux: FloatArray, sampleRate: Int, bpm: Float): Double {
        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        val hopSec = HOP.toDouble() / sampleRate.coerceAtLeast(1)
        if (flux.size < 8 || hopSec <= 0.0) return 0.0
        var bestPhase = 0.0
        var best = -1.0
        var phase = 0.0
        while (phase < period) {
            val score = meanAt(flux, phase, period, hopSec)
            if (score > best) {
                best = score
                bestPhase = phase
            }
            phase += hopSec
        }
        return if (best > 0.0) bestPhase else 0.0
    }

    private fun meanAt(values: FloatArray, phaseSec: Double, periodSec: Double, hopSec: Double): Double {
        val duration = values.size * hopSec
        var t = positiveMod(phaseSec, periodSec)
        if (t < hopSec) t += periodSec
        var sum = 0.0
        var n = 0
        while (t + hopSec < duration) {
            val i = kotlin.math.floor(t / hopSec).toInt()
            if (i in values.indices) {
                sum += values[i].toDouble()
                n++
            }
            t += periodSec
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun peakTimes(flux: FloatArray, periodSec: Double, hopSec: Double): DoubleArray {
        var max = 1e-8f
        for (v in flux) if (v > max) max = v
        val thresh = max * 0.28f
        val minGap = (0.72 * periodSec / hopSec).toInt().coerceAtLeast(2)
        val peaks = ArrayList<Int>()
        for (f in 1 until flux.size - 1) {
            if (flux[f] < thresh || flux[f] < flux[f - 1] || flux[f] < flux[f + 1]) continue
            if (peaks.isNotEmpty() && f - peaks.last() < minGap) {
                if (flux[f] > flux[peaks.last()]) peaks[peaks.lastIndex] = f
            } else {
                peaks.add(f)
            }
        }
        return DoubleArray(peaks.size) { i -> (peaks[i] + 0.5) * hopSec }
    }

    private fun longestChain(times: DoubleArray, periodSec: Double): DoubleArray? {
        var best = DoubleArray(0)
        var start = 0
        for (i in 1..times.size) {
            val broken = i == times.size || run {
                val gap = times[i] - times[i - 1]
                gap <= periodSec * 0.90 || gap >= periodSec * 1.10
            }
            if (!broken) continue
            val length = i - start
            if (length >= MIN_CHAIN && length > best.size) {
                best = times.copyOfRange(start, i)
            }
            start = i
        }
        return best.takeIf { it.size >= MIN_CHAIN }
    }

    private data class Line(val period: Double, val intercept: Double)

    private fun fit(times: DoubleArray): Line {
        val m = times.size
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until m) {
            val x = i.toDouble()
            sx += x
            sy += times[i]
            sxx += x * x
            sxy += x * times[i]
        }
        val den = m * sxx - sx * sx
        if (den <= 1e-9) return Line(0.0, 0.0)
        val period = (m * sxy - sx * sy) / den
        val intercept = (sy - period * sx) / m
        return Line(period, intercept)
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
        val left = corr[index - 1].toDouble()
        val mid = corr[index].toDouble()
        val right = corr[index + 1].toDouble()
        val den = left - 2.0 * mid + right
        if (abs(den) < 1e-9) return 0.0
        return (0.5 * (left - right) / den).coerceIn(-0.5, 0.5)
    }

    private fun lagFor(bpm: Double, hopSec: Double): Int {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        return (period / hopSec).toInt().coerceAtLeast(1)
    }

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 1e-9) return 0.0
        var m = value % period
        if (m < 0.0) m += period
        return m
    }
}
