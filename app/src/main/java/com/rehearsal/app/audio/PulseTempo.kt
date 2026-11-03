package com.rehearsal.app.audio

import com.rehearsal.app.player.PlaybackMath
import kotlin.math.abs

/**
 * Один темп по цепочке ударов, а не по ширине спектра.
 * Спектр на этой записи даёт 104.96, и ровный клик за две минуты
 * отстаёт от бочки примерно на 50 мс. Наклон длинной цепочки ударов — 105.00,
 * и та же сетка две минуты остаётся на ударе. Октаву не ищем.
 */
data class PulseGrid(
    val bpm: Float,
    /** Щелчок доли внутри периода, секунды. Та же прямая, что и темп. */
    val phaseSec: Double,
)

object PulseTempo {
    private const val WINDOW_BPM = 1.5
    internal const val HOP = 64
    private const val MIN_CHAIN = 16

    fun nearest(pcm: StereoPcm, hintBpm: Float): Float =
        grid(pcm, hintBpm)?.bpm ?: PlaybackMath.committedOriginalBpm(hintBpm)

    /**
     * Темп — наклон длинной цепочки низких ударов. Фаза — щелчок атаки
     * на 80–220 мс раньше тела, если он устойчивый и это не шляпа на 16-ю.
     * Без такого щелчка фаза остаётся на теле.
     */
    fun grid(pcm: StereoPcm, hintBpm: Float): PulseGrid? {
        val hint = hintBpm.toDouble()
        if (!hint.isFinite() || hint < MetronomeClickEngine.MIN_BPM || hint > MetronomeClickEngine.MAX_BPM) {
            return null
        }
        val energy = KickGrid.lowBandEnergy(pcm, HOP, KickGrid.LOWPASS_HZ)
        val base = gridFromEnergy(energy, pcm.sampleRate, HOP, hintBpm) ?: return null
        val chain = bodyChain(energy, pcm.sampleRate, HOP, hint) ?: return base
        val lead = earlyClickLead(pcm, chain, base.bpm) ?: 0.0
        val clickPeriod = 60.0 / base.bpm.toDouble().coerceAtLeast(1.0)
        return PulseGrid(base.bpm, positiveMod(base.phaseSec - lead, clickPeriod))
    }

    /** Темп и фаза тела по уже посчитанной низкой энергии. Щелчок атаки сюда не входит. */
    internal fun gridFromEnergy(
        energy: FloatArray,
        sampleRate: Int,
        hop: Int,
        hintBpm: Float,
    ): PulseGrid? {
        val hint = hintBpm.toDouble()
        if (!hint.isFinite() || hint < MetronomeClickEngine.MIN_BPM || hint > MetronomeClickEngine.MAX_BPM) {
            return null
        }
        var peak = 0f
        for (v in energy) if (v > peak) peak = v
        if (peak < 1e-8f || energy.size < 32) return null
        val flux = KickGrid.fluxOf(energy)
        val hintPeriod = 60.0 / hint
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val times = peakTimes(flux, hintPeriod, hopSec)
        val chain = longestChain(times, hintPeriod) ?: return null
        val line = fit(chain)
        if (line.period <= 1e-6) return null
        val rawBpm = 60.0 / line.period
        if (!rawBpm.isFinite() || abs(rawBpm - hint) > WINDOW_BPM) return null
        val bpm = PlaybackMath.committedOriginalBpm(rawBpm.toFloat())
        val clickPeriod = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        return PulseGrid(bpm, positiveMod(line.intercept, clickPeriod))
    }

    private fun bodyChain(energy: FloatArray, sampleRate: Int, hop: Int, hintBpm: Double): DoubleArray? {
        val hintPeriod = 60.0 / hintBpm
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val flux = KickGrid.fluxOf(energy)
        return longestChain(peakTimes(flux, hintPeriod, hopSec), hintPeriod)
    }

    /**
     * Demucs часто кладёт низ тела на ~80–220 мс позже щелчка атаки.
     * Громкая шляпа на 16-ю не считается: у 105 BPM это ~143 мс, не 170.
     */
    private fun earlyClickLead(pcm: StereoPcm, body: DoubleArray, bpm: Float): Double? {
        if (body.size < 8) return null
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val energy = KickGrid.lowBandEnergy(pcm, HOP, KickGrid.LOWPASS_HZ)
        val high = KickGrid.highpassEnergy(pcm, HOP, KickGrid.LOWPASS_HZ)
        val hf = KickGrid.fluxOf(high)
        var hfPeak = 1e-8f
        for (v in hf) if (v > hfPeak) hfPeak = v
        var highPeak = 1e-8f
        for (v in high) if (v > highPeak) highPeak = v
        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        val hopSec = HOP.toDouble() / sr
        val leads = ArrayList<Double>()
        for (t in body) {
            val hop = (t / hopSec).toInt().coerceIn(0, energy.lastIndex)
            val found = KickGrid.earlyClickAt(
                onsetSec = t,
                onsetEnergy = energy[hop],
                hf = hf,
                high = high,
                energy = energy,
                hfPeak = hfPeak,
                highPeak = highPeak,
                hop = HOP,
                sampleRate = sr,
                sixteenth = sixteenth,
            ) ?: continue
            leads.add(found.leadSec)
        }
        if (leads.size < 8 || leads.size < body.size * KickGrid.PRECLICK_MIN_FRACTION) return null
        val sorted = leads.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = leads.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        if (mad > KickGrid.PRECLICK_MAX_MAD_SEC) return null
        if (median < KickGrid.PRECLICK_MIN_SEC || median > KickGrid.PRECLICK_MAX_SEC) return null
        if (KickGrid.nearSixteenthLead(median, sixteenth, hopSec)) return null
        return median
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

    /** t = intercept + i * period. Одинаковый лаг атаки наклон и фазу не ломает. */
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

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 1e-9) return 0.0
        var m = value % period
        if (m < 0.0) m += period
        return m
    }
}
