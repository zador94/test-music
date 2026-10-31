package com.rehearsal.app.audio

import com.rehearsal.app.player.PlaybackMath
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Разбор ударных. BPM и `periodSec` клика — [TrackTempo] по миксу.
 * [alignPhase]/[refine] → [DrumPhase.align]: snap `beats[]` к миксу,
 * не новая сетка из одного drums `phaseSec`. VERSION не поднимаем.
 */
object KickGrid {
    const val VERSION = 12
    const val HOP = TempoDetector.HOP
    const val LOWPASS_HZ = 150.0
    const val MID_LOW_HZ = 100.0
    const val MID_HIGH_HZ = 250.0
    /** Не дальше ~230 мс влево от пика потока — хвост предыдущей доли. */
    const val MAX_BACKTRACK_HOPS = 20
    const val HF_LOOKBACK_HOPS = 10
    /** Сколько hops HF-щелчок должен опережать низ, чтобы заменить 12%-онсет. */
    const val HF_LEAD_HOPS = 2
    const val ENERGY_START_FRAC = 0.12f
    const val HF_ONSET_FRAC = 0.25f
    const val ENERGY_QUIET_FRAC = 0.20f
    /** Щелчок атаки до тела бочки (Demucs часто сажает низ на ~170 мс позже). */
    const val PRECLICK_MIN_SEC = 0.080
    const val PRECLICK_MAX_SEC = 0.220
    const val PRECLICK_MIN_FRACTION = 0.55
    const val PRECLICK_MAX_MAD_SEC = 0.025
    private const val TAG = "KickGrid"

    data class Result(
        val bpm: Float,
        val kickPhaseSec: Double,
        val onsetsSec: DoubleArray,
        val onsetPeakSpreadSec: Double = 0.0,
        val onsetInfos: List<OnsetInfo> = emptyList(),
        val phaseCandidates: List<PhaseCandidate> = emptyList(),
        val onsetsOnSixteenth: Int = 0,
        val onsetsOffSixteenth: Int = 0,
        val earlyClickLeadSec: Double = 0.0,
        val earlyClickHits: Int = 0,
        val earlyClickExamined: Int = 0,
        val earlyClickUsed: Boolean = false,
    )

    data class EarlyClick(
        val onsetSec: Double,
        val clickSec: Double,
        val leadSec: Double,
    )

    data class EarlyClickStats(
        val hits: Int,
        val examined: Int,
        val medianLeadSec: Double,
        val spreadSec: Double,
        val used: Boolean,
        val clicks: List<EarlyClick> = emptyList(),
    )

    data class QuarterSnapshot(
        val index: Int,
        val gridTimeSec: Double,
        val startSec: Double,
        val peakSec: Double,
        val clickSec: Double? = null,
    )

    data class OnsetInfo(
        val timeSec: Double,
        val lowEnergy: Float,
        val sharpness: Float,
        val attackHops: Int,
        val midSustain: Float,
        val kickWeight: Float,
    )

    data class PhaseCandidate(
        val phaseSec: Double,
        val score: Double,
    )

    fun hopSec(sampleRate: Int): Double = HOP.toDouble() / sampleRate.coerceAtLeast(1)

    /**
     * RMS-кадр [i·hop, (i+1)·hop). Метка — **центр** окна.
     * Начало окна (i·hop) ставило удар на 80 мс в 69.7 мс (−10 мс).
     * Конец окна дал бы до +hop поздно. Центральная метка: ошибка ≤ hop/2.
     */
    fun hopCenterSec(index: Int, hop: Int, sampleRate: Int): Double {
        val sr = sampleRate.coerceAtLeast(1)
        val h = hop.coerceAtLeast(1)
        return (index + 0.5) * h / sr
    }

    fun hopIndexAt(timeSec: Double, hopSec: Double, lastIndex: Int): Int {
        if (hopSec <= 0.0 || lastIndex < 0) return 0
        return kotlin.math.floor(timeSec / hopSec).toInt().coerceIn(0, lastIndex)
    }

    fun needsRefresh(analysis: TrackAnalysis?): Boolean =
        analysis == null || analysis.kickGridVersion < VERSION

    private class DrumOnsets(
        val sampleRate: Int,
        val duration: Double,
        val energy: FloatArray,
        val flux: FloatArray,
        val hf: FloatArray,
        val high: FloatArray,
        val onsets: DoubleArray,
        val infos: List<OnsetInfo>,
    )

    private fun collectOnsets(drums: StereoPcm): DrumOnsets? {
        val sr = drums.sampleRate.coerceAtLeast(1)
        val samples = drums.samples
        val duration = samples.toDouble() / sr
        if (samples < sr / 2) return null
        val energy = lowBandEnergy(drums, HOP, LOWPASS_HZ)
        var peak = 0f
        for (v in energy) if (v > peak) peak = v
        if (peak < 1e-8f) return null
        val flux = fluxOf(energy)
        val mid = bandEnergy(drums, HOP, MID_LOW_HZ, MID_HIGH_HZ)
        val hf = TempoDetector.novelty(drums, HOP)
        val high = highpassEnergy(drums, HOP, LOWPASS_HZ)
        val onsets = pickEnergyOnsets(energy, flux, hf, HOP, sr, duration)
        val infos = describeOnsets(onsets, energy, flux, mid, HOP, sr)
        return DrumOnsets(sr, duration, energy, flux, hf, high, onsets, infos)
    }

    fun analyze(drums: StereoPcm): Result {
        val bundle = collectOnsets(drums) ?: return Result(
            MetronomeClickEngine.DEFAULT_BPM,
            0.0,
            DoubleArray(0),
        )
        val sr = bundle.sampleRate
        val duration = bundle.duration
        val energy = bundle.energy
        val onsets = bundle.onsets
        val infos = bundle.infos
        val hf = bundle.hf
        val high = bundle.high
        val rawBpm = TempoDetector.estimateBpm(bundle.flux, HOP, sr)
        val seed = TempoMath.foldDoubleBallad(TempoMath.clampHalfDouble(rawBpm))
        val chosen = foldBalladGrid(
            chooseGrid(infos, energy, seed, duration, HOP, sr, rawBpm),
            infos,
            energy,
            HOP,
            sr,
        )
        val spread = onsetPeakSpreadSec(energy, onsets, HOP, sr)
        val roundedBpm = PlaybackMath.roundToTenth(chosen.bpm.toFloat())
        val period = 60.0 / roundedBpm.toDouble().coerceAtLeast(1.0)
        val early = detectEarlyClicks(
            onsets = quarterOnsets(strongKickOnsets(infos).ifEmpty { infos }, chosen.phaseSec, roundedBpm.toDouble()),
            hf = hf,
            high = high,
            energy = energy,
            bpm = roundedBpm.toDouble(),
            hop = HOP,
            sampleRate = sr,
        )
        val phaseSec = if (early.used) {
            positiveMod(chosen.phaseSec - early.medianLeadSec, period)
        } else {
            chosen.phaseSec
        }
        val membership = sixteenthMembership(onsets, roundedBpm.toDouble(), phaseSec, hopSec(sr))
        logDiagnostics(
            bpm = roundedBpm,
            phaseSec = phaseSec,
            spreadSec = spread,
            durationSec = duration,
            hopSec = hopSec(sr),
            infos = infos,
            candidates = chosen.candidates,
            onsetsOn = membership.first,
            onsetsOff = membership.second,
            early = early,
        )
        return Result(
            bpm = roundedBpm,
            kickPhaseSec = phaseSec,
            onsetsSec = onsets,
            onsetPeakSpreadSec = spread,
            onsetInfos = infos,
            phaseCandidates = chosen.candidates,
            onsetsOnSixteenth = membership.first,
            onsetsOffSixteenth = membership.second,
            earlyClickLeadSec = early.medianLeadSec,
            earlyClickHits = early.hits,
            earlyClickExamined = early.examined,
            earlyClickUsed = early.used,
        )
    }

    fun phaseInPeriod(drums: StereoPcm, periodSec: Double): Double =
        DrumPhase.phaseInPeriod(drums, periodSec)

    fun alignPhase(
        existing: TrackAnalysis,
        drums: StereoPcm,
        mix: StereoPcm? = null,
    ): TrackAnalysis = DrumPhase.align(existing, drums, mix)

    /** Не путь плеера. Плеер зовёт [DrumPhase.align], не этот метод. */
    fun refine(
        existing: TrackAnalysis,
        drums: StereoPcm,
        mix: StereoPcm? = null,
    ): TrackAnalysis = DrumPhase.align(existing, drums, mix)

    internal fun lowBandEnergy(pcm: StereoPcm, hop: Int, cutoffHz: Double): FloatArray {
        val n = pcm.samples
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val frames = ((n + hop - 1) / hop).coerceAtLeast(1)
        val alpha = (1.0 - exp(-2.0 * PI * cutoffHz / sr)).toFloat()
        var lp = 0f
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val end = min(start + hop, n)
            var sum = 0.0
            for (i in start until end) {
                val x = 0.5f * (pcm.left[i] + pcm.right[i])
                lp += alpha * (x - lp)
                sum += lp * lp
            }
            val count = (end - start).coerceAtLeast(1)
            energy[f] = sqrt(sum / count).toFloat()
        }
        return energy
    }

    /** RMS разницы двух однополюсных LPF: полоса [lowHz, highHz]. */
    internal fun bandEnergy(pcm: StereoPcm, hop: Int, lowHz: Double, highHz: Double): FloatArray {
        val n = pcm.samples
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val frames = ((n + hop - 1) / hop).coerceAtLeast(1)
        val aHi = (1.0 - exp(-2.0 * PI * highHz / sr)).toFloat()
        val aLo = (1.0 - exp(-2.0 * PI * lowHz / sr)).toFloat()
        var lpHi = 0f
        var lpLo = 0f
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val end = min(start + hop, n)
            var sum = 0.0
            for (i in start until end) {
                val x = 0.5f * (pcm.left[i] + pcm.right[i])
                lpHi += aHi * (x - lpHi)
                lpLo += aLo * (x - lpLo)
                val band = lpHi - lpLo
                sum += band * band
            }
            val count = (end - start).coerceAtLeast(1)
            energy[f] = sqrt(sum / count).toFloat()
        }
        return energy
    }

    /** RMS остатка после LPF: полная полоса минус низ, щелчок/тарелки. */
    internal fun highpassEnergy(pcm: StereoPcm, hop: Int, cutoffHz: Double): FloatArray {
        val n = pcm.samples
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val frames = ((n + hop - 1) / hop).coerceAtLeast(1)
        val alpha = (1.0 - exp(-2.0 * PI * cutoffHz / sr)).toFloat()
        var lp = 0f
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val end = min(start + hop, n)
            var sum = 0.0
            for (i in start until end) {
                val x = 0.5f * (pcm.left[i] + pcm.right[i])
                lp += alpha * (x - lp)
                val hp = x - lp
                sum += hp * hp
            }
            val count = (end - start).coerceAtLeast(1)
            energy[f] = sqrt(sum / count).toFloat()
        }
        return energy
    }

    internal fun lowBandFlux(pcm: StereoPcm, hop: Int, cutoffHz: Double): FloatArray =
        fluxOf(lowBandEnergy(pcm, hop, cutoffHz))

    internal fun fluxOf(energy: FloatArray): FloatArray {
        val frames = energy.size
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

    /**
     * Локальный максимум потока низкой энергии (новая атака), не тело предыдущей доли.
     * minGap ~93 мс: 16-е при 104.6 проходят, повтор того же удара — нет.
     */
    internal fun pickEnergyOnsets(
        energy: FloatArray,
        flux: FloatArray,
        hf: FloatArray,
        hop: Int,
        sampleRate: Int,
        duration: Double,
    ): DoubleArray {
        if (energy.isEmpty() || flux.isEmpty()) return DoubleArray(0)
        var peak = 1e-8f
        for (v in energy) if (v > peak) peak = v
        val sorted = energy.copyOf()
        sorted.sort()
        val med = sorted[sorted.size / 2]
        val energyThresh = maxOf(med * 4f, peak * ENERGY_START_FRAC)
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
            val onset = onsetIndexAt(energy, hf, i)
            times.add(hopCenterSec(onset, hop, sampleRate).coerceIn(0.0, duration))
            last = i
        }
        return times.toDoubleArray()
    }

    internal data class GridChoice(
        val bpm: Double,
        val phaseSec: Double,
        val candidates: List<PhaseCandidate>,
    )

    internal fun chooseGrid(
        infos: List<OnsetInfo>,
        energy: FloatArray,
        seedBpm: Double,
        duration: Double,
        hop: Int,
        sampleRate: Int,
        rawBpm: Double = seedBpm,
    ): GridChoice {
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val times = infos.map { it.timeSec }.toDoubleArray()
        val candidatesBpm = LinkedHashSet<Double>()
        fun addFamily(center: Double) {
            val clamped = TempoMath.clampHalfDouble(center)
            val tenth = kotlin.math.round(clamped * 10.0) / 10.0
            for (d in -12..12) {
                val c = tenth + d / 10.0
                if (c >= TempoMath.MIN_BPM && c <= TempoMath.MAX_BPM) candidatesBpm.add(c)
            }
            val half = clamped / 2.0
            val dbl = clamped * 2.0
            if (half >= TempoMath.MIN_BPM) candidatesBpm.add(kotlin.math.round(half * 10.0) / 10.0)
            if (dbl <= TempoMath.MAX_BPM) candidatesBpm.add(kotlin.math.round(dbl * 10.0) / 10.0)
            val eightNinths = clamped * 8.0 / 9.0
            val nineEighths = clamped * 9.0 / 8.0
            if (eightNinths >= TempoMath.MIN_BPM) {
                candidatesBpm.add(kotlin.math.round(eightNinths * 10.0) / 10.0)
            }
            if (nineEighths <= TempoMath.MAX_BPM) {
                candidatesBpm.add(kotlin.math.round(nineEighths * 10.0) / 10.0)
            }
        }
        addFamily(seedBpm)
        addFamily(rawBpm)
        ioiBpm(times)?.let { addFamily(it) }
        ioiBpm(strongKickOnsets(infos).map { it.timeSec }.toDoubleArray())?.let { addFamily(it) }

        val scoredOnsets = strongKickOnsets(infos).ifEmpty { infos }
        val onsetTimes = scoredOnsets.map { it.timeSec }.toDoubleArray()
        var bestBpm = seedBpm
        var bestPhase = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestOccupancy = 0.0
        var bestCandidates: List<PhaseCandidate> = emptyList()
        for (bpm in candidatesBpm) {
            val ranked = rankPhases(scoredOnsets, bpm, hopSec, energy, hop, sampleRate)
            val top = ranked.firstOrNull() ?: continue
            val occupancy = kickMatchScore(
                onsetTimes,
                bpm,
                top.phaseSec,
                duration,
                hop,
                sampleRate,
            )
            val combined = top.score * (0.45 + 0.55 * occupancy)
            val close = bestScore.isFinite() &&
                abs(combined - bestScore) <= 0.10 * maxOf(abs(combined), abs(bestScore), 1.0)
            val better = combined > bestScore + 1e-4 ||
                (close && occupancy > bestOccupancy + 0.08) ||
                (close && abs(occupancy - bestOccupancy) <= 0.08 && bpm < bestBpm - 0.2) ||
                (abs(combined - bestScore) <= 1e-4 && closerTempo(bpm, seedBpm, bestBpm))
            if (better) {
                bestScore = combined
                bestOccupancy = occupancy
                bestBpm = bpm
                bestPhase = top.phaseSec
                bestCandidates = ranked
            }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) {
            val period = 60.0 / seedBpm.coerceAtLeast(1.0)
            val phase = if (times.size >= 3) {
                circularMeanPhase(times, period)
            } else {
                combPhase(energy, period, hopSec)
            }
            return GridChoice(seedBpm, phase, emptyList())
        }
        return GridChoice(bestBpm, bestPhase, bestCandidates.take(8))
    }

    internal fun foldBalladGrid(
        chosen: GridChoice,
        infos: List<OnsetInfo>,
        energy: FloatArray,
        hop: Int,
        sampleRate: Int,
    ): GridChoice {
        val folded = TempoMath.foldDoubleBallad(chosen.bpm)
        if (abs(folded - chosen.bpm) <= 0.2) return chosen
        val scored = strongKickOnsets(infos).ifEmpty { infos }
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val ranked = rankPhases(scored, folded, hopSec, energy, hop, sampleRate)
        return GridChoice(
            bpm = folded,
            phaseSec = ranked.firstOrNull()?.phaseSec ?: chosen.phaseSec,
            candidates = ranked.take(8),
        )
    }

    internal fun rankPhases(
        infos: List<OnsetInfo>,
        bpm: Double,
        hopSec: Double,
        energy: FloatArray,
        hop: Int,
        sampleRate: Int,
    ): List<PhaseCandidate> {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        if (infos.isEmpty()) {
            val phase = combPhase(energy, period, hop.toDouble() / sampleRate.coerceAtLeast(1))
            return listOf(PhaseCandidate(phase, 0.0))
        }
        val phases = LinkedHashSet<Double>()
        for (info in infos) {
            val base = positiveMod(info.timeSec, period)
            for (k in 0..3) {
                phases.add(positiveMod(base - k * sixteenth, period))
            }
        }
        if (infos.size >= 3) {
            phases.add(circularMeanPhase(infos.map { it.timeSec }.toDoubleArray(), period))
        }
        val raw = ArrayList<PhaseCandidate>(phases.size)
        for (phase in phases) {
            raw.add(PhaseCandidate(phase, scoreQuarterPhase(infos, bpm, phase, hopSec)))
        }
        raw.sortByDescending { it.score }
        return dedupePhases(raw, period, hopSec)
    }

    /**
     * Четверти (bin 0) важнее 8-х; 16-е (e / a) — штраф, чтобы не сесть на синкопу.
     */
    internal fun scoreQuarterPhase(
        infos: List<OnsetInfo>,
        bpm: Double,
        phaseSec: Double,
        hopSec: Double,
    ): Double {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        val window = hopSec
        if (infos.isEmpty() || sixteenth <= 1e-9) return 0.0
        var peakLow = 1e-8f
        for (info in infos) if (info.lowEnergy > peakLow) peakLow = info.lowEnergy
        var score = 0.0
        var onQuarter = 0.0
        var onSixteenth = 0.0
        for (info in infos) {
            val rel = positiveMod(info.timeSec - phaseSec, period)
            val steps = rel / sixteenth
            val nearest = steps.roundToInt()
            val dist = abs(steps - nearest) * sixteenth
            val w = info.kickWeight.toDouble().coerceAtLeast(0.05)
            val bass = (info.lowEnergy / peakLow).toDouble()
            if (dist > window) {
                score -= 0.20 * w
                continue
            }
            onSixteenth += w
            val bin = ((nearest % 4) + 4) % 4
            when (bin) {
                0 -> {
                    score += w * (1.0 + 1.6 * bass)
                    onQuarter += w
                }
                2 -> { }
                else -> score -= 0.70 * w
            }
        }
        val total = infos.sumOf { it.kickWeight.toDouble().coerceAtLeast(0.05) }
        if (total > 1e-9) {
            score += 0.50 * (onQuarter / total)
            score += 0.08 * (onSixteenth / total)
        }
        return score
    }

    internal fun strongKickOnsets(infos: List<OnsetInfo>): List<OnsetInfo> {
        if (infos.size <= 4) return infos
        var maxW = 1e-8f
        var peakLow = 1e-8f
        for (o in infos) {
            if (o.kickWeight > maxW) maxW = o.kickWeight
            if (o.lowEnergy > peakLow) peakLow = o.lowEnergy
        }
        val thresh = maxW * 0.50f
        val strong = infos.filter { o ->
            o.kickWeight >= thresh && o.lowEnergy >= 0.40f * peakLow
        }
        if (strong.size >= 4) return strong
        return infos.sortedByDescending { it.kickWeight }.take((infos.size + 1) / 2)
    }

    internal fun describeOnsets(
        onsets: DoubleArray,
        energy: FloatArray,
        flux: FloatArray,
        mid: FloatArray,
        hop: Int,
        sampleRate: Int,
    ): List<OnsetInfo> {
        if (onsets.isEmpty() || energy.isEmpty()) return emptyList()
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        var peakLow = 1e-8f
        var peakMid = 1e-8f
        var peakSharp = 1e-8f
        val raw = ArrayList<OnsetInfo>(onsets.size)
        for (t in onsets) {
            val i = hopIndexAt(t, hopSec, energy.lastIndex)
            val peakEnd = (i + 8).coerceAtMost(energy.lastIndex)
            var low = energy[i]
            var peakAt = i
            for (k in i..peakEnd) {
                if (energy[k] > low) {
                    low = energy[k]
                    peakAt = k
                }
            }
            val sharp = flux.getOrElse(i) { 0f }
            val attackHops = (peakAt - i).coerceAtLeast(0)
            val midFrom = (i + 3).coerceAtMost(mid.lastIndex.coerceAtLeast(0))
            val midTo = (i + 12).coerceAtMost(mid.lastIndex.coerceAtLeast(0))
            var midSum = 0.0
            var midN = 0
            if (mid.isNotEmpty()) {
                for (k in midFrom..midTo) {
                    midSum += mid[k]
                    midN++
                }
            }
            val midSustain = if (midN == 0) 0f else (midSum / midN).toFloat()
            if (low > peakLow) peakLow = low
            if (midSustain > peakMid) peakMid = midSustain
            if (sharp > peakSharp) peakSharp = sharp
            raw.add(
                OnsetInfo(
                    timeSec = t,
                    lowEnergy = low,
                    sharpness = sharp,
                    attackHops = attackHops,
                    midSustain = midSustain,
                    kickWeight = 0f,
                ),
            )
        }
        peakMid = peakMid.coerceAtLeast(1e-8f)
        peakSharp = peakSharp.coerceAtLeast(1e-8f)
        return raw.map { info ->
            val lowN = info.lowEnergy / peakLow
            val sharpN = info.sharpness / peakSharp
            val shortAttack = 1f / (1f + info.attackHops / 3f)
            val sustainPen = 1f / (1f + 8f * (info.midSustain / peakMid))
            val weight = (0.62f * lowN + 0.23f * sharpN + 0.15f * shortAttack) * sustainPen
            info.copy(kickWeight = weight)
        }
    }

    internal fun bestPhaseSec(
        onsets: DoubleArray,
        energy: FloatArray,
        bpm: Double,
        hop: Int,
        sampleRate: Int,
    ): Double {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        if (onsets.size >= 3) {
            return circularMeanPhase(onsets, period)
        }
        return combPhase(energy, period, hopSec)
    }

    internal fun circularMeanPhase(timesSec: DoubleArray, periodSec: Double): Double {
        val period = periodSec.coerceAtLeast(1e-9)
        if (timesSec.isEmpty()) return 0.0
        var sinSum = 0.0
        var cosSum = 0.0
        for (t in timesSec) {
            val ang = 2.0 * PI * (positiveMod(t, period) / period)
            sinSum += sin(ang)
            cosSum += cos(ang)
        }
        val n = timesSec.size.toDouble()
        var turns = atan2(sinSum / n, cosSum / n) / (2.0 * PI)
        if (turns < 0.0) turns += 1.0
        return turns * period
    }

    internal fun combPhase(energy: FloatArray, periodSec: Double, hopSec: Double): Double {
        if (energy.isEmpty() || periodSec <= 0.0 || hopSec <= 0.0) return 0.0
        val periodHops = (periodSec / hopSec).coerceAtLeast(2.0)
        val steps = periodHops.roundToInt().coerceAtLeast(2)
        var bestOff = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (off in 0 until steps) {
            var score = 0.0
            var count = 0
            var pos = off.toDouble()
            while (pos < energy.size) {
                val i = pos.roundToInt()
                if (i in energy.indices) {
                    score += energy[i]
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
        return (onsetStartIndex(energy, bestOff) + 0.5) * hopSec
    }

    internal fun ioiBpm(onsets: DoubleArray): Double? {
        if (onsets.size < 8) return null
        val intervals = ArrayList<Double>(onsets.size)
        for (i in 1 until onsets.size) {
            val d = onsets[i] - onsets[i - 1]
            if (d.isFinite() && d > 0.22 && d < 2.05) intervals.add(d)
        }
        if (intervals.size < 4) return null
        intervals.sort()
        val median = intervals[intervals.size / 2]
        if (median <= 1e-6) return null
        return TempoMath.clampHalfDouble(60.0 / median)
    }

    internal fun kickMatchScore(
        onsets: DoubleArray,
        bpm: Double,
        phaseSec: Double,
        duration: Double,
        hop: Int,
        sampleRate: Int,
    ): Double {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val window = hopSec
        if (onsets.isEmpty() || duration <= 0.0) return 0.0
        var gridCount = 0
        var matchedGrid = 0
        var t = phaseSec
        if (t < 0.0) t += period
        while (t < duration) {
            gridCount++
            var hit = false
            for (onset in onsets) {
                val dist = wrappedDistance(onset - t, period)
                if (dist <= window) {
                    hit = true
                    break
                }
            }
            if (hit) matchedGrid++
            t += period
        }
        var matchedOnsets = 0
        for (onset in onsets) {
            val dist = wrappedDistance(onset - phaseSec, period)
            if (dist <= window) matchedOnsets++
        }
        val precision = if (gridCount == 0) 0.0 else matchedGrid.toDouble() / gridCount
        val recall = matchedOnsets.toDouble() / onsets.size
        val denom = precision + recall
        return if (denom <= 0.0) 0.0 else 2.0 * precision * recall / denom
    }

    internal fun onsetPeakSpreadSec(
        energy: FloatArray,
        onsets: DoubleArray,
        hop: Int,
        sampleRate: Int,
    ): Double {
        if (energy.isEmpty() || onsets.isEmpty()) return 0.0
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        for (t in onsets) {
            val start = hopIndexAt(t, hopSec, energy.lastIndex)
            val end = (start + MAX_BACKTRACK_HOPS).coerceAtMost(energy.lastIndex)
            var peak = start
            var top = energy[start]
            for (k in start..end) {
                if (energy[k] > top) {
                    top = energy[k]
                    peak = k
                }
            }
            sum += (peak - start) * hopSec
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    internal fun sixteenthMembership(
        onsets: DoubleArray,
        bpm: Double,
        phaseSec: Double,
        hopSec: Double,
    ): Pair<Int, Int> {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        val window = hopSec
        if (onsets.isEmpty() || sixteenth <= 1e-9) return 0 to 0
        var on = 0
        var off = 0
        for (t in onsets) {
            val rel = positiveMod(t - phaseSec, period)
            val steps = rel / sixteenth
            val dist = abs(steps - steps.roundToInt()) * sixteenth
            if (dist <= window) on++ else off++
        }
        return on to off
    }

    private fun dedupePhases(
        ranked: List<PhaseCandidate>,
        period: Double,
        hopSec: Double,
    ): List<PhaseCandidate> {
        val out = ArrayList<PhaseCandidate>()
        val window = hopSec + 0.004
        for (c in ranked) {
            if (out.any { wrappedDistance(it.phaseSec - c.phaseSec, period) <= window }) continue
            out.add(c)
        }
        return out
    }

    private fun wrappedDistance(delta: Double, period: Double): Double {
        val mod = positiveMod(delta, period)
        return min(mod, period - mod)
    }

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 0.0) return 0.0
        val m = value % period
        return if (m < 0.0) m + period else m
    }

    /**
     * От пика энергии бочки назад до порога ~12% — начало онсета, не тело удара.
     */
    internal fun onsetStartIndex(energy: FloatArray, peak: Int): Int {
        if (energy.isEmpty()) return 0
        var i = peak.coerceIn(0, energy.lastIndex)
        val last = (i - MAX_BACKTRACK_HOPS).coerceAtLeast(0)
        var top = energy[i]
        for (k in last..i) if (energy[k] > top) top = energy[k]
        val thresh = top * ENERGY_START_FRAC
        while (i > last && energy[i - 1] >= thresh) {
            i--
        }
        return i
    }

    /**
     * energyHit — первый кадр пробега низкой энергии (≥12%).
     * HF-щелчок атаки берём только если он явно раньше тела (не шум на 1 hop).
     */
    internal fun onsetIndexAt(
        energy: FloatArray,
        hf: FloatArray,
        energyHit: Int,
    ): Int {
        if (energy.isEmpty()) return 0
        val hit = energyHit.coerceIn(0, energy.lastIndex)
        val last = (hit - MAX_BACKTRACK_HOPS).coerceAtLeast(0)
        val searchEnd = (hit + 8).coerceAtMost(energy.lastIndex)
        var energyPeak = hit
        var topE = energy[hit]
        for (k in last..searchEnd) {
            if (energy[k] > topE) {
                topE = energy[k]
                energyPeak = k
            }
        }
        val energyStart = onsetStartIndex(energy, energyPeak)
        val hfClick = hfClickIndex(energy, hf, energyStart, energyPeak, topE)
        if (hfClick != null && energyStart - hfClick >= HF_LEAD_HOPS) {
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
    ): Int? {
        if (hf.isEmpty()) return null
        val from = (energyStart - HF_LOOKBACK_HOPS).coerceAtLeast(0)
        val to = energyPeak.coerceAtMost(hf.lastIndex)
        if (to < from) return null
        var trackPeak = 1e-8f
        for (v in hf) if (v > trackPeak) trackPeak = v
        var localPeak = 1e-8f
        for (k in from..to) {
            val v = hf[k]
            if (v > localPeak) localPeak = v
        }
        val thresh = maxOf(trackPeak * HF_ONSET_FRAC, localPeak * 0.35f)
        val quiet = topE * ENERGY_QUIET_FRAC
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

    internal fun quarterOnsets(
        infos: List<OnsetInfo>,
        phaseSec: Double,
        bpm: Double,
    ): List<OnsetInfo> {
        // Не fallback на все онсеты: у фанка сильный малый на 2/4, щелчок «до него» — бочка.
        return infos.filter { sixteenthBin(it.timeSec, phaseSec, bpm) == 0 }
    }

    /**
     * Короткий HF / >150 Гц всплеск за 80–220 мс до низкого онсета.
     * Берём фазу по щелчку только если он есть на большинстве четвертей
     * и свинец не 16-я сетки (шляпка на «е»/«а»).
     */
    internal fun detectEarlyClicks(
        onsets: List<OnsetInfo>,
        hf: FloatArray,
        high: FloatArray,
        energy: FloatArray,
        bpm: Double,
        hop: Int,
        sampleRate: Int,
    ): EarlyClickStats {
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        if (onsets.isEmpty() || hf.isEmpty()) {
            return EarlyClickStats(0, 0, 0.0, 0.0, used = false)
        }
        var hfPeak = 1e-8f
        for (v in hf) if (v > hfPeak) hfPeak = v
        var highPeak = 1e-8f
        for (v in high) if (v > highPeak) highPeak = v
        val clicks = ArrayList<EarlyClick>()
        for (onset in onsets) {
            val found = earlyClickAt(
                onsetSec = onset.timeSec,
                onsetEnergy = onset.lowEnergy,
                hf = hf,
                high = high,
                energy = energy,
                hfPeak = hfPeak,
                highPeak = highPeak,
                hop = hop,
                sampleRate = sampleRate,
                sixteenth = sixteenth,
            ) ?: continue
            clicks.add(found)
        }
        val leads = DoubleArray(clicks.size) { clicks[it].leadSec }
        val medianLead = median(leads)
        val spread = mad(leads, medianLead)
        val fraction = if (onsets.isEmpty()) 0.0 else clicks.size.toDouble() / onsets.size
        val onSixteenth = nearSixteenthLead(medianLead, sixteenth, hopSec)
        val used = onsets.size >= 8 &&
            clicks.size >= 8 &&
            fraction >= PRECLICK_MIN_FRACTION &&
            spread <= PRECLICK_MAX_MAD_SEC &&
            medianLead >= PRECLICK_MIN_SEC - hopSec &&
            medianLead <= PRECLICK_MAX_SEC + hopSec &&
            !onSixteenth
        return EarlyClickStats(
            hits = clicks.size,
            examined = onsets.size,
            medianLeadSec = medianLead,
            spreadSec = spread,
            used = used,
            clicks = clicks,
        )
    }

    internal fun earlyClickAt(
        onsetSec: Double,
        onsetEnergy: Float,
        hf: FloatArray,
        high: FloatArray,
        energy: FloatArray,
        hfPeak: Float,
        highPeak: Float,
        hop: Int,
        sampleRate: Int,
        sixteenth: Double,
    ): EarlyClick? {
        val hopSec = hop.toDouble() / sampleRate.coerceAtLeast(1)
        val last = min(hf.lastIndex, min(high.lastIndex, energy.lastIndex))
        val from = hopIndexAt(onsetSec - PRECLICK_MAX_SEC, hopSec, last)
        val to = hopIndexAt(onsetSec - PRECLICK_MIN_SEC, hopSec, last)
        if (to < from) return null
        var winHigh = 1e-8f
        for (k in from..to) {
            if (k <= high.lastIndex && high[k] > winHigh) winHigh = high[k]
        }
        val highThresh = maxOf(highPeak * 0.12f, winHigh * 0.40f)
        val quiet = onsetEnergy * 0.40f
        var bestK = -1
        var bestScore = 0f
        for (k in from..to) {
            val t = hopCenterSec(k, hop, sampleRate)
            val lead = onsetSec - t
            if (lead < PRECLICK_MIN_SEC - hopSec || lead > PRECLICK_MAX_SEC + hopSec) continue
            if (nearSixteenthLead(lead, sixteenth, hopSec)) continue
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
            val later = (
                high.getOrElse(k + 1) { 0f } +
                    high.getOrElse(k + 2) { 0f } +
                    high.getOrElse(k + 3) { 0f }
                ) / 3f
            if (later >= 0.55f * hiv) continue
            val score = hiv / (highPeak + 1e-8f) + 0.35f * hfv / (hfPeak + 1e-8f)
            if (score > bestScore) {
                bestScore = score
                bestK = k
            }
        }
        if (bestK < 0) return null
        val clickSec = hopCenterSec(bestK, hop, sampleRate)
        return EarlyClick(
            onsetSec = onsetSec,
            clickSec = clickSec,
            leadSec = onsetSec - clickSec,
        )
    }

    internal fun quarterSnapshots(
        phaseSec: Double,
        period: Double,
        infos: List<OnsetInfo>,
        hopSec: Double,
        clicks: List<EarlyClick> = emptyList(),
        count: Int = 8,
    ): List<QuarterSnapshot> {
        val p = period.coerceAtLeast(1e-9)
        val out = ArrayList<QuarterSnapshot>(count)
        for (n in 0 until count) {
            val t = phaseSec + n * p
            val nearest = infos.minByOrNull { info -> abs(info.timeSec - t) }
                ?.takeIf { abs(it.timeSec - t) <= p * 0.45 }
            val start = nearest?.timeSec ?: t
            val peak = if (nearest == null) t else nearest.timeSec + nearest.attackHops * hopSec
            val click = if (nearest == null) {
                null
            } else {
                clicks.minByOrNull { c -> abs(c.onsetSec - nearest.timeSec) }
                    ?.takeIf { abs(it.onsetSec - nearest.timeSec) <= hopSec * 2.0 }
                    ?.clickSec
            }
            out.add(
                QuarterSnapshot(
                    index = n,
                    gridTimeSec = t,
                    startSec = start,
                    peakSec = peak,
                    clickSec = click,
                ),
            )
        }
        return out
    }

    internal fun formatQuarters(snapshots: List<QuarterSnapshot>): String {
        fun ms(sec: Double): String = String.format(Locale.US, "%+.0fms", sec * 1000.0)
        return snapshots.joinToString("; ") { q ->
            val click = q.clickSec?.let { " click=${ms(it)}" } ?: ""
            String.format(
                Locale.US,
                "q%d t=%s start=%s peak=%s%s",
                q.index,
                ms(q.gridTimeSec),
                ms(q.startSec),
                ms(q.peakSec),
                click,
            )
        }
    }

    internal fun nearSixteenthLead(leadSec: Double, sixteenth: Double, hopSec: Double): Boolean {
        if (sixteenth <= 1e-9) return false
        val steps = leadSec / sixteenth
        val nearest = steps.roundToInt()
        if (nearest < 1 || nearest > 3) return false
        return abs(leadSec - nearest * sixteenth) <= hopSec + 0.008
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

    internal fun sixteenthBin(timeSec: Double, phaseSec: Double, bpm: Double): Int {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        val sixteenth = period / 4.0
        if (sixteenth <= 1e-9) return 0
        val rel = positiveMod(timeSec - phaseSec, period)
        val nearest = (rel / sixteenth).roundToInt()
        return ((nearest % 4) + 4) % 4
    }

    private fun binName(bin: Int): String = when (bin) {
        0 -> "Q"
        1 -> "e"
        2 -> "&"
        3 -> "a"
        else -> "?"
    }

    private fun logDiagnostics(
        bpm: Float,
        phaseSec: Double,
        spreadSec: Double,
        durationSec: Double,
        hopSec: Double,
        infos: List<OnsetInfo>,
        candidates: List<PhaseCandidate>,
        onsetsOn: Int,
        onsetsOff: Int,
        early: EarlyClickStats,
    ) {
        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
        fun ms(sec: Double): String = String.format(Locale.US, "%+.0fms", sec * 1000.0)
        val firstStart = infos.firstOrNull()?.timeSec ?: 0.0
        val firstPeak = infos.firstOrNull()?.let { it.timeSec + it.attackHops * hopSec } ?: 0.0
        logInfo(
            String.format(
                Locale.US,
                "bpm=%.1f phase=%.4fs (%s) spread=%.1fms version=%d duration=%.1fs firstPeak=%s energyStart=%s",
                bpm,
                phaseSec,
                ms(phaseSec),
                spreadSec * 1000.0,
                VERSION,
                durationSec,
                ms(firstPeak),
                ms(firstStart),
            ),
        )
        logInfo(
            String.format(
                Locale.US,
                "earlyClick hits=%d/%d medianLead=%s spread=%.1fms used=%s",
                early.hits,
                early.examined,
                ms(-early.medianLeadSec),
                early.spreadSec * 1000.0,
                early.used,
            ),
        )
        val quarters = formatQuarters(
            quarterSnapshots(phaseSec, period, infos, hopSec, early.clicks, count = 8),
        )
        logInfo("quarters: $quarters")
        if (candidates.isNotEmpty()) {
            val top = candidates.take(5).joinToString("; ") { c ->
                val delta = wrappedSigned(c.phaseSec - phaseSec, period)
                String.format(
                    Locale.US,
                    "%s score=%.2f (Δ%s)",
                    ms(c.phaseSec),
                    c.score,
                    ms(delta),
                )
            }
            logInfo("top-5 phases: $top")
        }
        logInfo(
            String.format(
                Locale.US,
                "onsets total=%d on16th=%d off16th=%d strong=%d",
                infos.size,
                onsetsOn,
                onsetsOff,
                strongKickOnsets(infos).size,
            ),
        )
        val logged = infos.take(24)
        for ((index, info) in logged.withIndex()) {
            val bin = binName(sixteenthBin(info.timeSec, phaseSec, bpm.toDouble()))
            logInfo(
                String.format(
                    Locale.US,
                    "onset[%d] t=%.3fs bin=%s low=%.3f sharp=%.3f attackHops=%d mid=%.3f kick=%.2f",
                    index,
                    info.timeSec,
                    bin,
                    info.lowEnergy,
                    info.sharpness,
                    info.attackHops,
                    info.midSustain,
                    info.kickWeight,
                ),
            )
        }
    }

    private fun closerTempo(candidate: Double, seed: Double, current: Double): Boolean {
        val hint = TempoMath.clampHalfDouble(seed)
        return abs(candidate - hint) < abs(current - hint) - 0.05
    }

    private fun wrappedSigned(delta: Double, period: Double): Double {
        val mod = positiveMod(delta, period)
        return if (mod > period * 0.5) mod - period else mod
    }

    private fun logInfo(message: String) {
        println("$TAG $message")
        try {
            android.util.Log.i(TAG, message)
        } catch (_: Throwable) {
            // JVM stub Log; println above still shows the line.
        }
    }
}
