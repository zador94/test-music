package com.rehearsal.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * После [TempoDetector]: не переписывает весь [TrackAnalysis.beats] одним
 * `phaseSec`. BPM не считает, 150→75 не складывает, [TrackTempo] не трогает.
 * Клик в плеере читает `beats[]`, не эту фазу.
 *
 * 0.3.42: каждую уже найденную долю тянет к онсету **микса** (±12% периода),
 * не к телу drums.wav. [periodSec]/[bpm] не меняет. Origin — первая доля.
 */
object DrumPhase {
    /** Атака бочки до тела, которое Demucs часто сажает на ~0.3 с позже. */
    private const val BEATER_LOOKBACK_SEC = 0.40
    private const val BEATER_LEAD_MIN_SEC = 0.12
    /** Кластер бочки vs гребёнка энергии: десятые, не кадр 0.012–0.016 с. */
    private const val MIX_AVOID_SEC = 0.10
    private const val BEATER_MAX_MAD_SEC = 0.040
    private const val BEATER_MIN_FRACTION = 0.45
    /** Позже hop STFT, не зашитые 200 мс. Верх — доля периода. */
    private const val DEMUCS_LAG_MIN_SEC = 0.060
    private const val DEMUCS_LAG_MAX_SEC = 0.45
    private const val DEMUCS_LAG_MAX_PERIOD = 0.55
    private const val DEMUCS_LAG_MAD_SEC = 0.040
    private const val DEMUCS_LAG_MIN_FRACTION = 0.45

    fun phaseInPeriod(drums: StereoPcm, periodSec: Double): Double {
        val period = periodSec.coerceAtLeast(1e-9)
        val sr = drums.sampleRate.coerceAtLeast(1)
        val hop = KickGrid.HOP
        val hopSec = hop.toDouble() / sr
        val energy = KickGrid.lowBandEnergy(drums, hop, KickGrid.LOWPASS_HZ)
        if (energy.isEmpty()) return 0.0
        var peak = 0f
        for (v in energy) if (v > peak) peak = v
        if (peak < 1e-8f) return 0.0
        val flux = KickGrid.fluxOf(energy)
        val mid = KickGrid.bandEnergy(drums, hop, KickGrid.MID_LOW_HZ, KickGrid.MID_HIGH_HZ)
        val hf = TempoDetector.novelty(drums, hop)
        val high = KickGrid.highpassEnergy(drums, hop, KickGrid.LOWPASS_HZ)
        val duration = drums.samples.toDouble() / sr

        val energyComb = combHopCenter(energy, period, hopSec)
        val fluxComb = combHopCenter(flux, period, hopSec)
        val onsets = pickOnsets(energy, flux, hf, hop, sr, period, duration)
        val infos = KickGrid.describeOnsets(onsets, energy, flux, mid, hop, sr)
        val strong = KickGrid.strongKickOnsets(infos).ifEmpty { infos }
        val bpm = 60.0 / period
        val ranked = KickGrid.rankPhases(strong, bpm, hopSec, energy, hop, sr)
        val clusters = clusterOnsets(strong, period, hopSec * 3.0)
        val kickCluster = clusters.maxByOrNull { it.kickWeight }
        val beater = beaterPhase(hf, high, energy, energyComb, period, hop, sr, duration)

        val top = ranked.firstOrNull()
        val kickAway = kickCluster != null &&
            top != null &&
            wrappedAbs(kickCluster.phaseSec - energyComb, period) > MIX_AVOID_SEC &&
            wrappedAbs(kickCluster.phaseSec - top.phaseSec, period) <= MIX_AVOID_SEC
        val beaterAway = beater != null &&
            wrappedAbs(beater - energyComb, period) > MIX_AVOID_SEC
        val topAway = top != null &&
            wrappedAbs(top.phaseSec - energyComb, period) > MIX_AVOID_SEC

        val raw = when {
            kickAway -> kickCluster!!.phaseSec
            beaterAway -> beater!!
            topAway -> top!!.phaseSec
            top != null -> top.phaseSec
            kickCluster != null -> kickCluster.phaseSec
            else -> fluxComb
        }
        return positiveMod(raw, period)
    }

    /**
     * Не переписывает весь [TrackAnalysis.beats] одним phaseSec.
     * [periodSec]/[bpm] микса не меняет. Если есть mix — каждую долю
     * тянет к онсету микса (±12% периода), не к телу drums.wav.
     */
    fun align(
        existing: TrackAnalysis,
        drums: StereoPcm,
        mix: StereoPcm? = null,
    ): TrackAnalysis {
        val period = if (existing.hasClickPeriod()) {
            existing.periodSec
        } else {
            existing.clickPeriodSec()
        }
        val beats = try {
            if (mix != null && existing.beats.isNotEmpty()) {
                snapBeatsToMix(existing.beats, mix, period)
            } else {
                existing.beats
            }
        } catch (_: Throwable) {
            existing.beats
        }
        val originAbs = beats.firstOrNull()
        val origin = if (originAbs != null && period > 1e-9) {
            positiveMod(originAbs, period)
        } else {
            existing.phaseSec
        }
        return existing.copy(
            beats = beats,
            kickPhaseSec = origin,
            phaseSec = origin,
            downbeatOffset = 0.0,
            kickGridVersion = KickGrid.VERSION,
            periodSec = existing.periodSec,
            bpm = existing.bpm,
        )
    }

    internal fun snapBeatsToMix(
        beats: DoubleArray,
        mix: StereoPcm,
        periodSec: Double,
    ): DoubleArray {
        val sr = mix.sampleRate.coerceAtLeast(1)
        val duration = mix.samples.toDouble() / sr
        val nov = TempoDetector.novelty(mix, KickGrid.HOP)
        return TempoDetector.snapTimesToNovelty(
            beats,
            nov,
            periodSec,
            KickGrid.HOP,
            sr,
            duration,
        )
    }

    /**
     * Атака микса в locked-периоде: гребёнка **потока** низкой энергии,
     * не пик тела и не [TrackAnalysis.phaseSec] сид.
     */
    internal fun attackPhase(pcm: StereoPcm, periodSec: Double): Double {
        val period = periodSec.coerceAtLeast(1e-9)
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val hop = KickGrid.HOP
        val hopSec = hop.toDouble() / sr
        val energy = KickGrid.lowBandEnergy(pcm, hop, KickGrid.LOWPASS_HZ)
        if (energy.isEmpty()) return 0.0
        val body = combHopCenter(energy, period, hopSec)
        val peakHop = (body / hopSec).roundToInt().coerceIn(0, energy.lastIndex)
        val peak = localMax(energy, peakHop, 3)
        val maxBack = (period / hopSec * 0.75).roundToInt().coerceAtLeast(2)
        val start = onsetStartIndex(energy, peak, maxBack)
        return positiveMod(KickGrid.hopCenterSec(start, hop, sr), period)
    }

    /**
     * Медианный лаг тела drums после атаки микса. Null — нет стабильной
     * задержки Demucs (не зашитые 200 мс).
     */
    internal fun stableBodyLagSec(
        mix: StereoPcm,
        drums: StereoPcm,
        periodSec: Double,
    ): Double? {
        val period = periodSec.coerceAtLeast(1e-9)
        val sr = min(mix.sampleRate, drums.sampleRate).coerceAtLeast(1)
        val hop = KickGrid.HOP
        val hopSec = hop.toDouble() / sr
        val mixEnergy = KickGrid.lowBandEnergy(mix, hop, KickGrid.LOWPASS_HZ)
        val drumsEnergy = KickGrid.lowBandEnergy(drums, hop, KickGrid.LOWPASS_HZ)
        if (mixEnergy.isEmpty() || drumsEnergy.isEmpty()) return null
        val mixAttack = attackPhase(mix, period)
        val minLag = DEMUCS_LAG_MIN_SEC
        val maxLag = min(DEMUCS_LAG_MAX_SEC, period * DEMUCS_LAG_MAX_PERIOD)
        if (maxLag <= minLag + hopSec) return null
        val duration = min(mix.samples, drums.samples).toDouble() / sr
        val maxBack = (period / hopSec * 0.75).roundToInt().coerceAtLeast(2)
        val leads = ArrayList<Double>()
        var examined = 0
        var t = mixAttack
        if (t < minLag) t += period
        while (t + maxLag < duration) {
            examined++
            val mixHop = KickGrid.hopIndexAt(t, hopSec, mixEnergy.lastIndex)
            val mixBody = localMax(mixEnergy, mixHop, 3)
            val mixOnset = onsetStartIndex(mixEnergy, mixBody, maxBack)
            val mixSec = KickGrid.hopCenterSec(mixOnset, hop, sr)
            val from = KickGrid.hopIndexAt(mixSec + minLag, hopSec, drumsEnergy.lastIndex)
            val to = KickGrid.hopIndexAt(mixSec + maxLag, hopSec, drumsEnergy.lastIndex)
            if (to > from) {
                var best = from
                var top = drumsEnergy[from]
                for (k in from..to) {
                    if (drumsEnergy[k] > top) {
                        top = drumsEnergy[k]
                        best = k
                    }
                }
                val atMix = drumsEnergy.getOrElse(mixOnset) { 0f }
                if (top >= atMix * 1.15f && top > 1e-6f) {
                    val bodySec = KickGrid.hopCenterSec(best, hop, sr)
                    val lead = bodySec - mixSec
                    if (lead >= minLag - hopSec && lead <= maxLag + hopSec) {
                        leads.add(lead)
                    }
                }
            }
            t += period
        }
        if (examined < 4 || leads.size < 4) return null
        if (leads.size.toDouble() / examined < DEMUCS_LAG_MIN_FRACTION) return null
        val values = leads.toDoubleArray()
        val med = median(values)
        if (mad(values, med) > DEMUCS_LAG_MAD_SEC) return null
        if (med < minLag || med > maxLag) return null
        return med
    }

    private fun phaseFromMixOrDrums(
        mix: StereoPcm,
        drums: StereoPcm,
        period: Double,
    ): Double {
        val mixAttack = attackPhase(mix, period)
        val lag = stableBodyLagSec(mix, drums, period)
        val raw = if (lag != null) mixAttack else phaseInPeriod(drums, period)
        return positiveMod(raw, period)
    }

    private fun localMax(series: FloatArray, around: Int, radius: Int): Int {
        if (series.isEmpty()) return 0
        val center = around.coerceIn(0, series.lastIndex)
        val from = (center - radius).coerceAtLeast(0)
        val to = (center + radius).coerceAtMost(series.lastIndex)
        var best = center
        var top = series[center]
        for (i in from..to) {
            if (series[i] > top) {
                top = series[i]
                best = i
            }
        }
        return best
    }

    /**
     * Гребёнка низкой энергии в locked-периоде — то, что 0.3.38 писал как
     * 0.576 / 0.076. Не фаза бочки: тело удара и bleed микса.
     */
    internal fun energyCombPhase(drums: StereoPcm, periodSec: Double): Double {
        val period = periodSec.coerceAtLeast(1e-9)
        val sr = drums.sampleRate.coerceAtLeast(1)
        val hopSec = KickGrid.HOP.toDouble() / sr
        val energy = KickGrid.lowBandEnergy(drums, KickGrid.HOP, KickGrid.LOWPASS_HZ)
        return combHopCenter(energy, period, hopSec)
    }

    private fun pickOnsets(
        energy: FloatArray,
        flux: FloatArray,
        hf: FloatArray,
        hop: Int,
        sampleRate: Int,
        periodSec: Double,
        duration: Double,
    ): DoubleArray {
        if (energy.isEmpty() || flux.isEmpty()) return DoubleArray(0)
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val maxBack = (periodSec / hopSec * 0.75).roundToInt().coerceAtLeast(2)
        val hfLook = (BEATER_LOOKBACK_SEC / hopSec).roundToInt().coerceAtLeast(2)
        var peak = 1e-8f
        for (v in energy) if (v > peak) peak = v
        val sorted = energy.copyOf()
        sorted.sort()
        val med = sorted[sorted.size / 2]
        val energyThresh = maxOf(med * 4f, peak * KickGrid.ENERGY_START_FRAC)
        val minGap = 8
        val times = ArrayList<Double>()
        var last = -minGap
        val lastIndex = min(energy.lastIndex, flux.lastIndex)
        for (i in 1 until lastIndex) {
            if (i - last < minGap) continue
            val f = flux[i]
            if (f < 0.12f) continue
            if (f < flux[i - 1] || f < flux[i + 1]) continue
            if (energy[i] < energyThresh * 0.35f) continue
            val onset = onsetIndexAt(energy, hf, i, maxBack, hfLook)
            times.add(KickGrid.hopCenterSec(onset, hop, sampleRate).coerceIn(0.0, duration))
            last = i
        }
        return times.toDoubleArray()
    }

    private fun onsetIndexAt(
        energy: FloatArray,
        hf: FloatArray,
        energyHit: Int,
        maxBack: Int,
        hfLook: Int,
    ): Int {
        if (energy.isEmpty()) return 0
        val hit = energyHit.coerceIn(0, energy.lastIndex)
        val last = (hit - maxBack.coerceAtLeast(1)).coerceAtLeast(0)
        val searchEnd = (hit + 8).coerceAtMost(energy.lastIndex)
        var energyPeak = hit
        var topE = energy[hit]
        for (k in last..searchEnd) {
            if (energy[k] > topE) {
                topE = energy[k]
                energyPeak = k
            }
        }
        val energyStart = onsetStartIndex(energy, energyPeak, maxBack)
        val hfClick = hfClickIndex(energy, hf, energyStart, energyPeak, topE, hfLook)
        if (hfClick != null && energyStart - hfClick >= KickGrid.HF_LEAD_HOPS) {
            return hfClick.coerceIn(0, energy.lastIndex)
        }
        return energyStart
    }

    private fun hfClickIndex(
        energy: FloatArray,
        hf: FloatArray,
        energyStart: Int,
        energyPeak: Int,
        topE: Float,
        hfLook: Int,
    ): Int? {
        if (hf.isEmpty()) return null
        val from = (energyStart - hfLook.coerceAtLeast(1)).coerceAtLeast(0)
        val to = energyPeak.coerceAtMost(hf.lastIndex)
        if (to < from) return null
        var trackPeak = 1e-8f
        for (v in hf) if (v > trackPeak) trackPeak = v
        var localPeak = 1e-8f
        for (k in from..to) {
            val v = hf[k]
            if (v > localPeak) localPeak = v
        }
        val thresh = maxOf(trackPeak * KickGrid.HF_ONSET_FRAC, localPeak * 0.35f)
        val quiet = topE * KickGrid.ENERGY_QUIET_FRAC
        for (k in from..to) {
            val v = hf[k]
            if (v < thresh) continue
            val prev = hf.getOrElse(k - 1) { 0f }
            val next = hf.getOrElse(k + 1) { 0f }
            val energyHere = energy.getOrElse(k) { 0f }
            if (v >= prev && v >= next && energyHere < quiet) {
                return k
            }
        }
        return null
    }

    private fun onsetStartIndex(energy: FloatArray, peak: Int, maxBack: Int): Int {
        if (energy.isEmpty()) return 0
        var i = peak.coerceIn(0, energy.lastIndex)
        val last = (i - maxBack.coerceAtLeast(1)).coerceAtLeast(0)
        var top = energy[i]
        for (k in last..i) if (energy[k] > top) top = energy[k]
        val thresh = top * KickGrid.ENERGY_START_FRAC
        while (i > last && energy[i - 1] >= thresh) {
            i--
        }
        return i
    }

    private data class Cluster(
        val phaseSec: Double,
        val kickWeight: Double,
        val count: Int,
    )

    private fun clusterOnsets(
        infos: List<KickGrid.OnsetInfo>,
        period: Double,
        window: Double,
    ): List<Cluster> {
        if (infos.isEmpty() || period <= 0.0) return emptyList()
        val used = BooleanArray(infos.size)
        val out = ArrayList<Cluster>()
        for (i in infos.indices) {
            if (used[i]) continue
            val base = positiveMod(infos[i].timeSec, period)
            val members = ArrayList<Int>()
            for (j in infos.indices) {
                if (used[j]) continue
                val p = positiveMod(infos[j].timeSec, period)
                if (wrappedAbs(p - base, period) <= window) {
                    used[j] = true
                    members.add(j)
                }
            }
            var sinSum = 0.0
            var cosSum = 0.0
            var wSum = 0.0
            for (j in members) {
                val w = infos[j].kickWeight.toDouble().coerceAtLeast(0.05)
                val ang = 2.0 * PI * (positiveMod(infos[j].timeSec, period) / period)
                sinSum += w * sin(ang)
                cosSum += w * cos(ang)
                wSum += w
            }
            val n = wSum.coerceAtLeast(1e-9)
            var turns = atan2(sinSum / n, cosSum / n) / (2.0 * PI)
            if (turns < 0.0) turns += 1.0
            out.add(Cluster(turns * period, wSum, members.size))
        }
        return out
    }

    /**
     * HF/highpass щелчок 0.12–0.40 с до гребёнки энергии (тело Demucs).
     * Не [KickGrid.detectEarlyClicks]: там окно 80–220 мс, Skyfall ~300 мс.
     */
    private fun beaterPhase(
        hf: FloatArray,
        high: FloatArray,
        energy: FloatArray,
        bodyPhase: Double,
        period: Double,
        hop: Int,
        sampleRate: Int,
        duration: Double,
    ): Double? {
        if (hf.isEmpty() || high.isEmpty() || energy.isEmpty()) return null
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        var hfPeak = 1e-8f
        for (v in hf) if (v > hfPeak) hfPeak = v
        var highPeak = 1e-8f
        for (v in high) if (v > highPeak) highPeak = v
        val leads = ArrayList<Double>()
        var examined = 0
        var t = bodyPhase
        if (t < BEATER_LOOKBACK_SEC) t += period
        while (t < duration) {
            examined++
            val bodyHop = KickGrid.hopIndexAt(t, hopSec, energy.lastIndex)
            val bodyEnergy = energy.getOrElse(bodyHop) { 0f }
            val lead = beaterLeadAt(
                bodySec = t,
                bodyEnergy = bodyEnergy,
                hf = hf,
                high = high,
                energy = energy,
                hfPeak = hfPeak,
                highPeak = highPeak,
                hop = hop,
                sampleRate = sampleRate,
                period = period,
            )
            if (lead != null) leads.add(lead)
            t += period
        }
        if (examined < 4) return null
        val fraction = leads.size.toDouble() / examined
        if (leads.size < 4 || fraction < BEATER_MIN_FRACTION) return null
        val medianLead = median(leads.toDoubleArray())
        val spread = mad(leads.toDoubleArray(), medianLead)
        if (spread > BEATER_MAX_MAD_SEC) return null
        if (medianLead < BEATER_LEAD_MIN_SEC - hopSec) return null
        if (medianLead > BEATER_LOOKBACK_SEC + hopSec) return null
        return positiveMod(bodyPhase - medianLead, period)
    }

    private fun beaterLeadAt(
        bodySec: Double,
        bodyEnergy: Float,
        hf: FloatArray,
        high: FloatArray,
        energy: FloatArray,
        hfPeak: Float,
        highPeak: Float,
        hop: Int,
        sampleRate: Int,
        period: Double,
    ): Double? {
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val sixteenth = period / 4.0
        val last = min(hf.lastIndex, min(high.lastIndex, energy.lastIndex))
        val from = KickGrid.hopIndexAt(bodySec - BEATER_LOOKBACK_SEC, hopSec, last)
        val to = KickGrid.hopIndexAt(bodySec - BEATER_LEAD_MIN_SEC, hopSec, last)
        if (to < from) return null
        var winHigh = 1e-8f
        for (k in from..to) {
            if (k <= high.lastIndex && high[k] > winHigh) winHigh = high[k]
        }
        val highThresh = maxOf(highPeak * 0.12f, winHigh * 0.40f)
        val quiet = bodyEnergy * 0.40f
        var bestK = -1
        var bestScore = 0f
        for (k in from..to) {
            val clickSec = KickGrid.hopCenterSec(k, hop, sampleRate)
            val lead = bodySec - clickSec
            if (lead < BEATER_LEAD_MIN_SEC - hopSec || lead > BEATER_LOOKBACK_SEC + hopSec) continue
            if (KickGrid.nearSixteenthLead(lead, sixteenth, hopSec)) continue
            if (energy.getOrElse(k) { 0f } >= quiet) continue
            val hiv = high.getOrElse(k) { 0f }
            val hfv = hf.getOrElse(k) { 0f }
            if (hiv < highThresh) continue
            val localHi = hiv >= high.getOrElse(k - 1) { 0f } && hiv >= high.getOrElse(k + 1) { 0f }
            if (!localHi) continue
            var pre = 0f
            var preN = 0
            for (d in 2..5) {
                pre += high.getOrElse(k - d) { 0f }
                preN++
            }
            if (preN > 0 && pre / preN > 0.40f * hiv) continue
            val score = hiv / (highPeak + 1e-8f) + 0.35f * hfv / (hfPeak + 1e-8f)
            if (score > bestScore) {
                bestScore = score
                bestK = k
            }
        }
        if (bestK < 0) return null
        return bodySec - KickGrid.hopCenterSec(bestK, hop, sampleRate)
    }

    private fun combHopCenter(series: FloatArray, periodSec: Double, hopSec: Double): Double {
        if (series.isEmpty() || periodSec <= 0.0 || hopSec <= 0.0) return 0.0
        val periodHops = (periodSec / hopSec).coerceAtLeast(2.0)
        val steps = periodHops.roundToInt().coerceAtLeast(2)
        var bestOff = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (off in 0 until steps) {
            var score = 0.0
            var count = 0
            var pos = off.toDouble()
            while (pos < series.size) {
                val i = pos.roundToInt()
                if (i in series.indices) {
                    score += series[i]
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
        return (bestOff + 0.5) * hopSec
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    private fun mad(values: DoubleArray, center: Double): Double {
        if (values.isEmpty()) return 0.0
        val d = DoubleArray(values.size) { abs(values[it] - center) }
        return median(d)
    }

    private fun wrappedAbs(delta: Double, period: Double): Double {
        val mod = positiveMod(delta, period)
        return min(mod, period - mod)
    }

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 0.0) return 0.0
        val m = value % period
        return if (m < 0.0) m + period else m
    }
}
