package com.rehearsal.app.audio

import android.util.Log
import com.rehearsal.app.player.PlaybackMath
import java.util.Arrays
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Совместный поиск сетки: одна функция атаки и перебор пар (период, фаза).
 *
 * Атака — полуволновой поток по хопам [HOP] (~4 мс на 44.1 кГц).
 * Из потока вычитается локальная медиана: гул сайдчейна не держит пол.
 * Ударные: низ 0.55 / середина 0.30 / щелчок 0.15 — по ним выбирается период. Фаза φ — только по бочке (низ).
 * Bass и other в кривую не входят: каждый 4-й удар сетки на их совместной атаке получает +30%.
 * Темп — топ-3 периода автокорреляции в 65–165 BPM, кратности 0.5x / 1x / 2x.
 * Пик выше 165 до 210 входит, если он настоящий и бочка на нём тише 0.65 медленной доли.
 * Октава бочки берёт её удвоенный период и целое в пределах 0.5. Расколотый фундаментальный пик склеивается.
 * Кратность 1.5x добавляется в 90–145 BPM, если её подтверждает пик withSnare.
 * Оценка этой кратности — по автокорреляции ударных: withSnare только разрешает период.
 * Фаза — сдвиг с шагом 2 мс, чья сетка собирает больше энергии в окне σ = 15 мс.
 * Октаву > 140 BPM не берём, если пик малого на 70–85 BPM сильнее быстрого.
 * Фаза уточняется параболой. periodSec — период этой сетки.
 * Период бочки перебивает родственного лучшего кандидата: октаву — если бочка сама бьёт на быстрой доле
 * и настоящий малый не бьёт на обеих половинах медленной сетки, 3:2 — если лучший сильнее меньше чем в 1.4 раза.
 * Потом период ±0.5% и фаза уточняются по бочке на всём треке: одна сетка не уходит от долей к концу песни.
 * Фаза сдвигается на полдоли, если там малый бьёт через удар (2 и 4), а не на каждой точке.
 * Если автокорреляция ударных ниже пола на всём окне от 32.5 BPM, период ищется по басу и other,
 * но только когда у них есть один явный период, а не гребёнка близких пиков.
 * Если и атаки пустые, период берётся из сглаженной громкости баса и other: аккорд баллады держится, а не бьёт.
 */
object ClickGrid {
    const val VERSION = 53
    const val SAMPLE_RATE = 44100
    /** ~4.4 мс при 44.1 кГц: общий шаг функции атаки. */
    const val HOP = 192
    const val LOWPASS_HZ = 150.0
    /** Выше этого щелчок атаки, ниже — корпус малого. */
    const val BODY_HZ = 800.0

    private const val SEARCH_MIN_BPM = 65.0
    private const val SEARCH_MAX_BPM = 165.0
    /** Настоящий пик смеси выше потолка: 200, не удвоение пустой восьмой у песни на 105. */
    private const val FAST_SEARCH_MAX = 210.0
    private const val FAST_PEAK_RATIO = 0.40f
    /**
     * Бочка на быстрой доле громче этого — пик выше 165 это восьмая, не четверть.
     * Kids: пик 200 равен 0.50 пика 100. Восьмая с бочкой 0.5 у песни на 105 громче.
     */
    private const val FAST_KICK_ON_EIGHTH = 0.65
    /** 47.5 × 2 = 95 лежит между расколотыми 94 и 96. */
    private const val FUNDAMENTAL_PULL_BPM = 2.0
    /** 105 против 94 выиграл 58/53. Явный чужой темп по-прежнему забирает счёт. */
    private const val UNRELATED_WIN_RATIO = 1.15
    /** 77.74 × 2 = 155.47 → 155. Окно 0.03 этого не делает, и к 154.34 оно ближе к 154. */
    private const val OCTAVE_INTEGER_SNAP = 0.5
    /** Skyfall: все пики ~0.0004. Земфира и Марионетки держатся около 0.0013. */
    private const val DRUM_CORR_FLOOR = 0.0008f
    /** Соседний некратный пик громче этого — у сигнала нет одного периода. Skyfall-бас: 0.75. */
    private const val DOMINANT_RIVAL_RATIO = 0.72f
    /** Огибающая аккорда: окно около 0.1 с, уже чем медиана атаки 0.2 с. */
    private const val ENVELOPE_SIGMA = 0.04
    /**
     * Бочка собрана в долю. 75 в логе даёт около 0.5, ровные 80 — около 0.12.
     * Октава того же удара прибавляет около 0.06, поэтому порог выше этого зазора.
     */
    private const val KICK_BEAT_CONTRAST = 0.30
    private const val KICK_CONTRAST_MARGIN = 0.15
    private const val PHASE_STEP_SEC = 0.002
    private const val SIGMA_SEC = 0.015
    private const val DOWNBEAT_BONUS = 1.30
    /** Студийный трек на целом BPM: дробный хвост параболы к концу песни набегает в сдвиг. */
    private const val INTEGER_BPM_SNAP = 0.03
    private const val STRONG_PEAK_RATIO = 0.90f
    private const val LOW_W = 0.55
    private const val MID_W = 0.30
    private const val HIGH_W = 0.15
    /**
     * Энергия посередине медленного периода / энергия на его ударах.
     * Ниже — восьмая (короче и слабее четверти). Выше — бочка на каждой четверти,
     * а медленный пик это половинный темп.
     * Быстрый кандидат > 140 BPM при более сильном пике малого на 70–85 BPM — тоже восьмая.
     */
    private const val OFFBEAT_IS_BEAT = 0.80
    /**
     * 3:2: период бочки перебивает лучшего кандидата, только если тот сильнее меньше чем в 1.4 раза.
     * Замер: пунктир 1.05 (бочка права), «Фонари» 1.80 и 3+3+2 на 102 — 2.33 (лучший прав).
     */
    private const val OVERRIDE_SCORE_RATIO = 1.4
    /**
     * Октава: период бочки остаётся, если бочка сама бьёт и на быстрой доле (>= 0.7).
     * Замер: восьмые бочки 0.98, восьмые-акценты 1.06 (бочка права), бэкбит 155 — 0.44 (лучший прав).
     */
    private const val FAST_PULSE_RATIO = 0.7
    /**
     * Даже при бочке на быстрой доле быстрый темп верен, если настоящий малый бьёт на обеих половинах медленной сетки.
     * Середина / низ >= 0.45: у восьмых-акцентов 0.38 (середина — только утечка бочки), у бэкбита 0.58, у восьмых бочки 0.79.
     * Слабая / сильная половина >= 0.5: восьмые бочки 0.35 (малый только на чётных долях), 31-я весна 0.64, малый на каждой доле 0.64.
     */
    private const val MID_PRESENT_SHARE = 0.45
    private const val MID_BOTH_HALVES = 0.5
    private const val REFINE_SPAN = 0.005
    private const val REFINE_DRIFT_SEC = 0.002
    private const val REFINE_MAX_STEPS = 600
    private const val REFINE_START_SEC = 0.012
    private const val REFINE_RIDGE_SEC = 0.004
    private const val REFINE_ANCHOR_STEP_SEC = 0.001
    private const val MID_WINDOW_SEC = 0.015
    private const val HALF_SHIFT_MID_RATIO = 1.05
    /** Слабая половина точек / сильная. Ниже — бэкбит 2 и 4, выше — малый на каждой восьмой. */
    private const val BACKBEAT_ALTERNATION = 0.75
    /**
     * Верх на сдвинутой половине сильнее, чем на текущей; щелчок (середина + верх) чередуется по долям
     * ([BACKBEAT_ALTERNATION]: малый на каждой доле даёт ≈0.9, бэкбит 0.6); бочка не бьёт строго на текущей.
     * Середина не годится для первого условия: она в 3-4 раза больше верха и у 31-й весны на обеих половинах равна.
     * Верх сдвинутая / текущая: 31-я весна 1.54 (сдвиг), «Фонари» 0.63, «Титаник» 0.24.
     * Бочка текущая / сдвинутая: 31-я весна 1.4, бас на восьмой и бэкбит 1.67 (сдвиг), малый на «и» над бочкой 2.0 (без сдвига).
     */
    private const val CRACK_SHIFT_RATIO = 1.15
    private const val KICK_ON_BEAT_RATIO = 1.8
    private const val DIAG = "GRID_DIAG"
    private const val ACF_PEAKS = 10
    private const val PROFILE_BINS = 16
    /** Четверть, которую слушатель считает долей, и удвоенный период огибающей. Только для лога. */
    private val HYPOTHESIS_BPMS = doubleArrayOf(75.0, 80.0)

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
        val midFlux = onsetOf(mid, drums.size, hopSec)
        val bassOnset = onsetOf(bass ?: lead, drums.size, hopSec)
        val otherOnset = onsetOf(other, drums.size, hopSec)
        val midShare = if (mid == null) 0.0 else mid.sum().toDouble() / low.sum().toDouble().coerceAtLeast(1e-9)
        val highFlux = onsetOf(high, drums.size, hopSec)
        val fit = searchGrid(
            drums, kick, midFlux, highFlux, midShare, bassOnset, otherOnset, hopSec, duration,
            rawBass = bass ?: lead,
            rawOther = other,
            lowEnergy = low,
        )
        val period = fit?.periodSec?.let(::snapToIntegerBpm) ?: (60.0 / (hint?.toDouble() ?: MetronomeClickEngine.DEFAULT_BPM.toDouble()))
        val phase = positiveMod(fit?.phaseSec ?: 0.0, period)
        val bpm = PlaybackMath.committedOriginalBpm((60.0 / period).toFloat())
        Log.d(
            DIAG,
            "bpm=${diagNum(bpm.toDouble())} period=${diagNum(period)} phase=${diagNum(phase)} " +
                "kickBpm=${diagNum(fit?.kickBpm)}",
        )
        Log.d(
            DIAG,
            "profile 16 bins per 2 beats, bin0 = grid point, bin8 = next beat, bin4 = eighth after bin0: " +
                diagProfile("low", low, hopSec, period, phase) + " " +
                diagProfile("mid", mid, hopSec, period, phase) + " " +
                diagProfile("high", high, hopSec, period, phase),
        )
        for (hypothesis in HYPOTHESIS_BPMS) {
            Log.d(DIAG, diagHypothesis(hypothesis, low, mid, high, hopSec))
        }
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
        midFlux: FloatArray?,
        highFlux: FloatArray?,
        midShare: Double,
        bass: FloatArray?,
        other: FloatArray?,
        hopSec: Double,
        duration: Double,
        allowFallback: Boolean = true,
        rawBass: FloatArray? = null,
        rawOther: FloatArray? = null,
        lowEnergy: FloatArray? = null,
    ): Fit? {
        if (activation.size < 32 || hopSec <= 0.0) return null
        if (peakOf(activation) < 1e-6f) {
            if (!allowFallback) return null
            val body = bodyActivation(bass, other, activation.size)
            val bodyRead = if (body == null) null else dominantReadOf(body, hopSec)
            if (body != null && isDominant(bodyRead)) {
                return searchGrid(body, body, null, null, 0.0, null, null, hopSec, duration, allowFallback = false)
            }
            if (body != null) Log.d(DIAG, "bass/other has no dominant period ${formatContest(bodyRead)}")
            // Ударных нет. Штрихи без аккорда не становятся темпом, а держащийся бас остаётся.
            return envelopeFit(rawBass, rawOther, body, activation.size, hopSec, duration, keepHeldChord = true).fit
        }
        val wide = lagRange(hopSec, activation.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM * 2.0)
        if (wide == null) return null
        val corr = autocorr(activation, wide.first, wide.second)
        // 47.5 у Земфиры сильнее всего, что лежит в 65–210. Пол без этой октавы уводит поиск в бас.
        val musical = maxCorrBetween(corr, hopSec, SEARCH_MIN_BPM / 2.0, FAST_SEARCH_MAX)
        var heldEnvelope: Fit? = null
        if (allowFallback && musical < DRUM_CORR_FLOOR) {
            val body = bodyActivation(bass, other, activation.size)
            val bodyRead = if (body == null) null else dominantReadOf(body, hopSec)
            val corrText = diagNum(musical.toDouble())
            if (body != null && isDominant(bodyRead)) {
                Log.d(DIAG, "drums corr=$corrText -> bass/other")
                val retry = searchGrid(
                    body, body, null, null, 0.0, null, null, hopSec, duration, allowFallback = false,
                )
                if (retry != null) return retry
                Log.d(DIAG, "sub-floor drums c=$corrText envelope=skipped, continuing")
            } else {
                if (body != null) {
                    Log.d(DIAG, "drums corr=$corrText bass/other has no dominant period ${formatContest(bodyRead)}")
                }
                val envelope = envelopeFit(rawBass, rawOther, body, activation.size, hopSec, duration)
                if (envelope.fit != null) {
                    heldEnvelope = envelope.fit
                    Log.d(DIAG, "sub-floor drums c=$corrText envelope=held, checking kick")
                } else {
                    Log.d(DIAG, "sub-floor drums c=$corrText envelope=${envelope.miss}, continuing")
                }
            }
        }
        Log.d(DIAG, diagAcf(corr, hopSec, wide.first, wide.second))
        Log.d(DIAG, "kick " + diagAcf(autocorr(kickOnset, wide.first, wide.second), hopSec, wide.first, wide.second))
        val withSnare = autocorr(withSnareOnset(kickOnset, midFlux), wide.first, wide.second)
        val fundamental = doubledFundamentalBpm(corr, hopSec, wide.first, wide.second)
        val kick = pullBpm(kickPeriod(kickOnset, midFlux, hopSec), fundamental)
        val periods = withKick(
            distinctPeriods(
                topPeriods(corr, withSnare, kickOnset, hopSec, wide.first, wide.second)
                    .map { pullPeriod(it, fundamental) },
            ),
            kick,
        )
        if (periods.isEmpty()) return heldEnvelope
        val smooth = gaussianSmooth(activation, hopSec, SIGMA_SEC)
        val smoothKick = if (peakOf(kickOnset) >= 1e-6f) gaussianSmooth(kickOnset, hopSec, SIGMA_SEC) else smooth
        val smoothBass = bass?.let { gaussianSmooth(it, hopSec, SIGMA_SEC) }
        val smoothOther = other?.let { gaussianSmooth(it, hopSec, SIGMA_SEC) }
        val bassGate = (peakOf(smoothBass) * 0.40f)
        val otherGate = (peakOf(smoothOther) * 0.40f)
        val fits = ArrayList<Fit>(periods.size)
        for (period in periods) {
            val phase = bestPhase(smoothKick, smoothBass, smoothOther, bassGate, otherGate, period, hopSec, duration)
            val score = gridScore(smooth, smoothBass, smoothOther, bassGate, otherGate, phase.phase, period, hopSec, duration)
            fits.add(Fit(period, phase.phase, score, kick))
        }
        Log.d(
            DIAG,
            "candidates kickBpm=${diagNum(kick)} " + fits.joinToString(" | ") {
                "bpm=${diagNum(60.0 / it.periodSec)} period=${diagNum(it.periodSec)} " +
                    "phase=${diagNum(it.phaseSec)} score=${diagNum(it.score)}"
            },
        )
        // Счёт любит 152: точек вдвое больше. Ниже пола доля видна в громкости низа, не в этом счёте.
        // 75 в свёртке первое, а 153 на onset даёт c=0.23 — почти так же плоско, как огибающая 78.7.
        val beatPeriod = heldEnvelope?.let {
            beatPeriodOverEnvelope(it.periodSec, fits.map { fit -> fit.periodSec }, lowEnergy, hopSec)
        }
        val chosen = if (beatPeriod != null) {
            fits.minBy { abs(it.periodSec - beatPeriod) }
        } else {
            choose(fits, kick, kickOnset, midFlux, midShare, hopSec, duration)
        }
        val locked = kick != null && isWholeBpm(chosen.periodSec) &&
            near(chosen.periodSec, (60.0 / kick) / 2.0, 0.03)
        val refined = refineGrid(
            smoothKick,
            chosen.periodSec,
            chosen.phaseSec,
            hopSec,
            duration,
            lockPeriod = locked,
        )
        val drum = chosen.copy(
            periodSec = refined.periodSec,
            phaseSec = backbeatPhase(midFlux, highFlux, kickOnset, refined.phaseSec, refined.periodSec, hopSec, duration),
        )
        if (beatPeriod != null) return drum
        val held = heldEnvelope ?: return drum
        if (near(held.periodSec, drum.periodSec, 0.03) ||
            kickBeatsEnvelope(held.periodSec, drum.periodSec, kickOnset, hopSec)
        ) {
            return drum
        }
        Log.d(
            DIAG,
            "envelope kept over kick envBpm=${diagNum(60.0 / held.periodSec)} " +
                "drumBpm=${diagNum(60.0 / drum.periodSec)}",
        )
        return held
    }

    private class Grid(val periodSec: Double, val phaseSec: Double)

    private class AnchorFit(val anchorSec: Double, val score: Double)

    /**
     * Второй проход: период ±[REFINE_SPAN] мелким шагом вместе с фазой по средней энергии бочки на всём треке.
     * Фаза держится на якоре в середине трека: сдвиг периода не уводит середину, а концы сдвигаются симметрично.
     * Шаг периода такой, что концы трека сдвигаются не больше [REFINE_DRIFT_SEC] за шаг, поэтому
     * якорь на соседнем периоде лежит рядом с якорем на предыдущем (хребет, окно [REFINE_RIDGE_SEC]).
     * Целый BPM (см. [snapToIntegerBpm]) применяется здесь, чтобы фаза пересчиталась под уже округлённый период.
     */
    private fun refineGrid(
        kick: FloatArray,
        period: Double,
        phase: Double,
        hopSec: Double,
        duration: Double,
        lockPeriod: Boolean = false,
    ): Grid {
        if (period <= 1e-6 || kick.isEmpty() || duration <= 0.0) return Grid(period, phase)
        val anchor0 = phase + round((duration / 2.0 - phase) / period) * period
        val startRadius = if (lockPeriod) period / 2.0 else REFINE_START_SEC
        val start = anchorFit(kick, period, anchor0, startRadius, hopSec)
        if (lockPeriod) {
            Log.d(
                DIAG,
                "refine period=${diagNum(period)}->${diagNum(period)} " +
                    "phase=${diagNum(positiveMod(phase, period))}->${diagNum(positiveMod(start.anchorSec, period))} " +
                    "steps=0",
            )
            return Grid(period, positiveMod(start.anchorSec, period))
        }
        val beatsFromMid = (duration / (2.0 * period)).coerceAtLeast(1.0)
        val span = REFINE_SPAN * period
        val steps = Math.ceil(span * beatsFromMid / REFINE_DRIFT_SEC).toInt().coerceIn(1, REFINE_MAX_STEPS)
        val dp = span / steps
        var best = start
        var bestPeriod = period
        for (dir in intArrayOf(-1, 1)) {
            var anchor = start.anchorSec
            for (i in 1..steps) {
                val p = period + dir * i * dp
                val fit = anchorFit(kick, p, anchor, REFINE_RIDGE_SEC, hopSec)
                anchor = fit.anchorSec
                if (fit.score > best.score) {
                    best = fit
                    bestPeriod = p
                }
            }
        }
        val snapped = snapToIntegerBpm(bestPeriod)
        if (snapped != bestPeriod) {
            val radius = beatsFromMid * abs(snapped - bestPeriod) + REFINE_RIDGE_SEC
            best = anchorFit(kick, snapped, best.anchorSec, radius, hopSec)
            bestPeriod = snapped
        }
        Log.d(
            DIAG,
            "refine period=${diagNum(period)}->${diagNum(bestPeriod)} " +
                "phase=${diagNum(positiveMod(phase, period))}->${diagNum(positiveMod(best.anchorSec, bestPeriod))} " +
                "steps=$steps",
        )
        return Grid(bestPeriod, positiveMod(best.anchorSec, bestPeriod))
    }

    /** Лучший якорь в ±[radius] вокруг [center]: шаг 1 мс, потом парабола по трём соседним. */
    private fun anchorFit(kick: FloatArray, period: Double, center: Double, radius: Double, hopSec: Double): AnchorFit {
        val n = Math.ceil(radius / REFINE_ANCHOR_STEP_SEC).toInt().coerceAtLeast(1)
        val scores = DoubleArray(2 * n + 1) { meanGrid(kick, center + (it - n) * REFINE_ANCHOR_STEP_SEC, period, hopSec) }
        var bestI = 0
        for (i in scores.indices) if (scores[i] > scores[bestI]) bestI = i
        val delta = if (bestI in 1 until scores.lastIndex) {
            parabolic3(scores[bestI - 1], scores[bestI], scores[bestI + 1])
        } else {
            0.0
        }
        return AnchorFit(center + (bestI - n + delta) * REFINE_ANCHOR_STEP_SEC, scores[bestI])
    }

    /**
     * Сайдчейн-бас ниже 150 Гц перебивает бочку и ставит φ на восьмую.
     * Малый в 4/4 на 2 и 4: середина через точку сетки, сдвинутой на полдоли.
     * Середина на каждой точке сдвинутой сетки — малый на офбите, φ остаётся.
     */
    private fun backbeatPhase(
        midFlux: FloatArray?,
        highFlux: FloatArray?,
        kickOnset: FloatArray,
        phase: Double,
        period: Double,
        hopSec: Double,
        duration: Double,
    ): Double {
        if (midFlux == null || period <= 1e-6) return phase
        val shifted = positiveMod(phase + period / 2.0, period)
        val on = midByParity(midFlux, phase, period, hopSec, duration)
        val off = midByParity(midFlux, shifted, period, hopSec, duration)
        val weak = minOf(off[0], off[1])
        val strong = maxOf(off[0], off[1])
        Log.d(
            DIAG,
            "on=${diagNum(on[0] + on[1])} off=${diagNum(off[0] + off[1])} " +
                "weak=${diagNum(weak)} strong=${diagNum(strong)} " +
                "onParity=${diagNum(on[0])}/${diagNum(on[1])} offParity=${diagNum(off[0])}/${diagNum(off[1])}",
        )
        val landed = if (off[0] + off[1] > (on[0] + on[1]) * HALF_SHIFT_MID_RATIO &&
            weak < strong * BACKBEAT_ALTERNATION
        ) {
            shifted
        } else {
            val highOn = parity(highFlux, phase, period, hopSec, duration).sum()
            val highOff = parity(highFlux, shifted, period, hopSec, duration)
            val crackOff0 = off[0] + highOff[0]
            val crackOff1 = off[1] + highOff[1]
            val kickOn = parity(kickOnset, phase, period, hopSec, duration).sum()
            val kickOff = parity(kickOnset, shifted, period, hopSec, duration).sum()
            val crackAlternation = minOf(crackOff0, crackOff1) / maxOf(crackOff0, crackOff1).coerceAtLeast(1e-9)
            Log.d(
                DIAG,
                "high on=${diagNum(highOn)} off=${diagNum(highOff.sum())} alternation=${diagNum(crackAlternation)} " +
                    "kick on=${diagNum(kickOn)} off=${diagNum(kickOff)}",
            )
            if (highOff.sum() > highOn * CRACK_SHIFT_RATIO && crackAlternation < BACKBEAT_ALTERNATION &&
                kickOn < kickOff * KICK_ON_BEAT_RATIO
            ) {
                shifted
            } else {
                phase
            }
        }
        return landed
    }

    private fun parity(flux: FloatArray?, phase: Double, period: Double, hopSec: Double, duration: Double): DoubleArray =
        if (flux == null) DoubleArray(2) else midByParity(flux, phase, period, hopSec, duration)

    /** Сумма середины в ±[MID_WINDOW_SEC] вокруг чётных и нечётных точек сетки. */
    private fun midByParity(
        midFlux: FloatArray,
        phase: Double,
        period: Double,
        hopSec: Double,
        duration: Double,
    ): DoubleArray {
        val out = DoubleArray(2)
        val radius = (MID_WINDOW_SEC / hopSec).roundToInt().coerceAtLeast(1)
        var k = 0
        var t = phase
        while (t < duration - 1e-6) {
            val center = floor(t / hopSec).toInt()
            for (i in center - radius..center + radius) {
                if (i in midFlux.indices) out[k % 2] += midFlux[i]
            }
            k++
            t += period
        }
        return out
    }

    /**
     * Сумма попаданий выбирает период, кроме октавы и полиритма 3:2 бочки.
     * Восьмая (период / 2), половинный темп (× 2), 2:3 и 3:2 могут остаться на периоде низа:
     * сумма ударов взяла бы более частую сетку, а пунктир/синкопа — ложный 3:2.
     * Октаву решает [kickPulse] и середина: отношение очков для неё не разделяет случаи (сетка вдвое чаще
     * набирает почти вдвое больше). Бочка на быстрой доле остаётся на медленном темпе, если только
     * настоящий малый не бьёт на обеих половинах медленной сетки ([midHalvesRatio]).
     * 3:2 решает отношение очков: пунктир слабее [OVERRIDE_SCORE_RATIO].
     */
    private fun choose(
        fits: List<Fit>,
        kickBpm: Double?,
        kickOnset: FloatArray,
        midFlux: FloatArray?,
        midShare: Double,
        hopSec: Double,
        duration: Double,
    ): Fit {
        val best = fits.maxBy { it.score }
        if (kickBpm == null || kickBpm <= 0.0) return best
        val kickPeriod = 60.0 / kickBpm
        val kickFit = fits.minBy { abs(it.periodSec - kickPeriod) }
        if (!near(kickFit.periodSec, kickPeriod, 0.03)) return best
        val octave = near(best.periodSec, kickPeriod / 2.0, 0.03) ||
            near(best.periodSec, kickPeriod * 2.0, 0.03)
        val threeTwo = near(best.periodSec, kickPeriod * 2.0 / 3.0, 0.03) ||
            near(best.periodSec, kickPeriod * 3.0 / 2.0, 0.03)
        val pulse = if (octave) kickPulse(kickOnset, best.periodSec, kickFit.periodSec, hopSec) else null
        val halves = if (pulse != null && pulse.ratio >= FAST_PULSE_RATIO) {
            midHalvesRatio(midFlux, if (best.periodSec > kickFit.periodSec) best else kickFit, hopSec, duration)
        } else {
            null
        }
        val realMidOnBothHalves = halves != null && midShare >= MID_PRESENT_SHARE && halves >= MID_BOTH_HALVES
        // 1.09 и половины 0.57 при корреляции 0.0006 — это ровный шум, не малый на каждой доле.
        val octaveBelowFloor = pulse != null && pulse.slow > 0f && pulse.slow < DRUM_CORR_FLOOR
        val choice = choiceDiag(
            octave, threeTwo, pulse?.ratio, realMidOnBothHalves, best.score, kickFit.score, octaveBelowFloor,
        )
        val picked = if (choice.keepKick) kickFit else best
        val kickLock = diagOnOff(kickOnset, picked.phaseSec, picked.periodSec, hopSec, duration)
        val midLock = if (midFlux == null) {
            "null"
        } else {
            diagOnOff(midFlux, picked.phaseSec, picked.periodSec, hopSec, duration)
        }
        Log.d(
            DIAG,
            "choose best=${diagNum(60.0 / best.periodSec)} kickFit=${diagNum(60.0 / kickFit.periodSec)} " +
                "related=${octave || threeTwo} ratio=${diagNum(best.score / kickFit.score)} " +
                "fastPulse=${diagNum(pulse?.ratio)} slowC=${diagNum(pulse?.slow?.toDouble())} " +
                "midShare=${diagNum(midShare)} midHalves=${diagNum(halves)} " +
                "${choice.text} lock kick=$kickLock mid=$midLock",
        )
        return alignFastOctave(picked, kickBpm)
    }

    /** Быстрая октава садится на удвоенную бочку, не на соседний пик смеси. 155.47 → 155. */
    private fun alignFastOctave(picked: Fit, kickBpm: Double): Fit {
        val kickPeriod = 60.0 / kickBpm
        if (!near(picked.periodSec, kickPeriod / 2.0, 0.03)) return picked
        val doubled = kickBpm * 2.0
        if (doubled > FAST_SEARCH_MAX) return picked
        val whole = round(doubled)
        val period = if (abs(doubled - whole) <= OCTAVE_INTEGER_SNAP) 60.0 / whole else kickPeriod / 2.0
        return if (period == picked.periodSec) picked else picked.copy(periodSec = period)
    }

    /**
     * Середина (малый) на двух половинах медленной сетки: слабая / сильная. 0 — середины нет.
     * Берём слабую к сильной, а не «на долях / между»: фаза бочки на ровных ударах может сесть на любую половину.
     */
    private fun midHalvesRatio(midFlux: FloatArray?, slow: Fit, hopSec: Double, duration: Double): Double {
        if (midFlux == null) return 0.0
        val a = midByParity(midFlux, slow.phaseSec, slow.periodSec, hopSec, duration)
        val shifted = positiveMod(slow.phaseSec + slow.periodSec / 2.0, slow.periodSec)
        val b = midByParity(midFlux, shifted, slow.periodSec, hopSec, duration)
        val first = a[0] + a[1]
        val second = b[0] + b[1]
        val strong = maxOf(first, second)
        return if (strong <= 1e-9) 0.0 else minOf(first, second) / strong
    }

    private class KickPulse(val ratio: Double, val slow: Float)

    /**
     * Автокорреляция атак бочки на быстром лаге / на медленном. Близко к 1 — бочка бьёт и на быстрой доле,
     * быстрая сетка тогда восьмые. Мало — на быстрых долях бьёт малый, быстрая сетка это четверть.
     * [KickPulse.slow] ниже пола — отношение само шум: 1.09 не отличает восьмые от пустой сетки.
     */
    private fun kickPulse(kickOnset: FloatArray, periodA: Double, periodB: Double, hopSec: Double): KickPulse {
        val fastLag = (minOf(periodA, periodB) / hopSec).roundToInt()
        val slowLag = (maxOf(periodA, periodB) / hopSec).roundToInt()
        val minLag = (fastLag * 0.9).toInt()
        val maxLag = (slowLag * 1.1).toInt()
        if (minLag < 1 || maxLag >= kickOnset.size / 2) return KickPulse(0.0, Float.POSITIVE_INFINITY)
        val corr = autocorr(kickOnset, minLag, maxLag)
        val slow = peakNear(corr, slowLag, minLag, maxLag)
        if (slow <= 0f) return KickPulse(0.0, Float.POSITIVE_INFINITY)
        return KickPulse((peakNear(corr, fastLag, minLag, maxLag) / slow).toDouble(), slow)
    }

    private fun kickPeriod(drums: FloatArray, midFlux: FloatArray?, hopSec: Double): Double? {
        if (peakOf(drums) < 1e-6f) return null
        val wide = lagRange(hopSec, drums.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM) ?: return null
        val corr = autocorr(drums, wide.first, wide.second)
        val range = lagRange(hopSec, drums.size, SEARCH_MIN_BPM, SEARCH_MAX_BPM) ?: return null
        val lag = octaveLag(corr, drums, midFlux, hopSec, range.first, range.second)
        val inRange = if (lag < 0 || corr[lag] <= 0f) {
            null
        } else {
            val refined = (lag + parabolic(corr, lag, range.first, range.second)) * hopSec
            val bpm = 60.0 / refined
            if (bpm < SEARCH_MIN_BPM || bpm > SEARCH_MAX_BPM) null else bpm
        }
        if (inRange != null && corr[lag] >= DRUM_CORR_FLOOR) return inRange
        return doubledFundamentalBpm(corr, hopSec, wide.first, wide.second) ?: inRange
    }

    /**
     * Самый сильный пик ниже 65 BPM, удвоенный в окно поиска.
     * 47.5 → 95. Пик уже внутри 65–165 не трогаем, если он выше пола: это и есть темп.
     * Ниже пола один всплеск половины равен шуму внутри окна (0.0007 против 0.0006), и порог 1.2 его съедает.
     */
    private fun doubledFundamentalBpm(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): Double? {
        if (hopSec <= 0.0 || maxLag <= minLag + 1) return null
        var bestBelow = -1
        var bestBelowC = 0f
        var bestInRange = 0f
        val last = minOf(maxLag, corr.lastIndex - 1)
        for (lag in (minLag + 1) until last) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val bpm = 60.0 / ((lag + parabolic(corr, lag, minLag, maxLag)) * hopSec)
            if (bpm >= SEARCH_MIN_BPM) {
                if (c > bestInRange) bestInRange = c
            } else if (c > bestBelowC) {
                bestBelow = lag
                bestBelowC = c
            }
        }
        if (bestBelow < 0 || !halfNoteBeatsInRange(bestBelowC, bestInRange)) return null
        var bpm = 60.0 / ((bestBelow + parabolic(corr, bestBelow, minLag, maxLag)) * hopSec)
        while (bpm < SEARCH_MIN_BPM) bpm *= 2.0
        if (bpm > SEARCH_MAX_BPM) return null
        // 41 × 2 = 82 при настоящих 123: это 2/3, не расколотый фундаментальный пик.
        val threeTwo = bpm * 1.5
        if (threeTwo in SEARCH_MIN_BPM..SEARCH_MAX_BPM) {
            val lag = (60.0 / threeTwo / hopSec).roundToInt()
            if (peakNear(corr, lag, minLag, maxLag) >= bestBelowC * 0.5f) return null
        }
        return bpm
    }

    /**
     * Половина перебивает пик внутри 65–165.
     * Выше пола нужен запас 1.2: 47.5 громче 95. Ниже пола высоты равны шуму, и 1.17 уже достаточно.
     */
    internal fun halfNoteBeatsInRange(below: Float, inRange: Float): Boolean {
        if (below <= 0f) return false
        if (inRange < DRUM_CORR_FLOOR) return below >= inRange
        return below >= inRange * 1.2f
    }

    private fun pullBpm(bpm: Double?, fundamentalBpm: Double?): Double? {
        if (bpm == null || fundamentalBpm == null) return bpm
        return if (abs(bpm - fundamentalBpm) <= FUNDAMENTAL_PULL_BPM) fundamentalBpm else bpm
    }

    private fun pullPeriod(period: Double, fundamentalBpm: Double?): Double {
        if (fundamentalBpm == null || period <= 1e-9) return period
        val bpm = 60.0 / period
        return if (abs(bpm - fundamentalBpm) <= FUNDAMENTAL_PULL_BPM) 60.0 / fundamentalBpm else period
    }

    private fun distinctPeriods(periods: List<Double>): List<Double> {
        val out = ArrayList<Double>(periods.size)
        for (period in periods) {
            if (out.any { near(it, period, 0.03) }) continue
            out.add(period)
        }
        return out
    }

    private fun isWholeBpm(periodSec: Double): Boolean {
        if (periodSec <= 1e-9) return false
        val bpm = 60.0 / periodSec
        return abs(bpm - round(bpm)) <= 1e-6
    }

    /**
     * Громкость баса и other, когда атаки периода не дают.
     * Берём пик, только если он один. Фаза — на громком месте огибающей.
     */
    private class EnvelopeHit(val fit: Fit?, val miss: String)

    private fun envelopeFit(
        bass: FloatArray?,
        other: FloatArray?,
        onsets: FloatArray?,
        n: Int,
        hopSec: Double,
        duration: Double,
        keepHeldChord: Boolean = false,
    ): EnvelopeHit {
        val raw = bodyActivation(fitLength(bass, n), fitLength(other, n), n) ?: return EnvelopeHit(null, "none")
        val env = normalize(gaussianSmooth(raw, hopSec, ENVELOPE_SIGMA))
        val wide = lagRange(hopSec, env.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM * 2.0)
            ?: return EnvelopeHit(null, "none")
        val corr = autocorr(env, wide.first, wide.second)
        Log.d(DIAG, "envelope " + diagAcf(corr, hopSec, wide.first, wide.second))
        val bpm = dominantBpm(corr, hopSec, wide.first, wide.second)
        if (bpm == null) {
            Log.d(DIAG, "envelope has no dominant period")
            return EnvelopeHit(null, "no-period")
        }
        val folded = foldIntoSearch(bpm)
        if (folded == null) {
            Log.d(DIAG, "envelope bpm=${diagNum(bpm)} outside search")
            return EnvelopeHit(null, "out-of-range")
        }
        val repeat = blurredAttack(onsets, folded, hopSec)
        if (repeat != null) {
            val ratio = repeat.corr / repeat.bestC
            Log.d(
                DIAG,
                "envelope bpm=${diagNum(folded)} repeats an attack " +
                    "rivalBpm=${diagNum(repeat.bpm)} c=${diagNum(repeat.corr.toDouble())} " +
                    "bestC=${diagNum(repeat.bestC.toDouble())} ratio=${diagNum(ratio.toDouble())}",
            )
            if (!(keepHeldChord && chordHolds(raw))) return EnvelopeHit(null, "rejected")
        }
        val period = 60.0 / folded
        val phase = bestPhase(env, null, null, 0f, 0f, period, hopSec, duration).phase
        Log.d(DIAG, "envelope bpm=${diagNum(folded)} period=${diagNum(period)} phase=${diagNum(phase)}")
        return EnvelopeHit(Fit(period, phase, 0.0, null), "ok")
    }

    /**
     * Аккорд держит звук между атаками: медиана громкости не у пола.
     * Гребёнка шипов имеет медиану около нуля, даже если сглаживание нарисовало период.
     */
    private fun chordHolds(raw: FloatArray): Boolean {
        val peak = peakOf(raw)
        if (raw.isEmpty() || peak < 1e-6f) return false
        val sorted = raw.copyOf()
        Arrays.sort(sorted)
        return sorted[sorted.size / 2] >= peak * 0.20f
    }

    /** 84 у гребёнки шипов — это половина атак на 168. Такой пик огибающая не приносит. */
    private fun blurredAttack(onsets: FloatArray?, envelopeBpm: Double, hopSec: Double): AttackRepeat? {
        if (onsets == null || envelopeBpm <= 0.0) return null
        val wide = lagRange(hopSec, onsets.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM * 2.0) ?: return null
        val corr = autocorr(onsets, wide.first, wide.second)
        val peaks = ArrayList<Pair<Double, Float>>()
        val last = minOf(wide.second, corr.lastIndex - 1)
        for (lag in (wide.first + 1) until last) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val period = (lag + parabolic(corr, lag, wide.first, wide.second)) * hopSec
            if (period <= 1e-6) continue
            peaks.add(60.0 / period to c)
        }
        return repeatingAttack(peaks, envelopeBpm)
    }

    private class AttackRepeat(val bpm: Double, val corr: Float, val bestC: Float)

    /** Только удвоение и учетверение: огибающая повторила смазанные атаки. Совпадение периода — это сам аккорд. */
    private fun repeatingAttack(peaks: List<Pair<Double, Float>>, envelopeBpm: Double): AttackRepeat? {
        if (envelopeBpm <= 0.0) return null
        var bestC = 0f
        for ((_, c) in peaks) if (c > bestC) bestC = c
        if (bestC <= 0f) return null
        var rivalBpm = 0.0
        var rivalC = 0f
        for ((bpm, c) in peaks) {
            if (c < bestC * 0.80f) continue
            if (near(bpm, envelopeBpm * 2.0, 0.03) || near(bpm, envelopeBpm * 4.0, 0.03)) {
                if (c > rivalC) {
                    rivalC = c
                    rivalBpm = bpm
                }
            }
        }
        if (rivalC <= 0f) return null
        return AttackRepeat(rivalBpm, rivalC, bestC)
    }

    /**
     * Кандидат, на котором низ складывается в долю, против плоской огибающей.
     * Быстрая октава того же удара отбрасывается: ниже пола это восьмые, не темп.
     * Пустой или ровный низ — огибающую нечем заменить.
     */
    internal fun beatPeriodOverEnvelope(
        envelopePeriod: Double,
        periods: List<Double>,
        low: FloatArray?,
        hopSec: Double,
    ): Double? {
        if (low == null || hopSec <= 0.0 || envelopePeriod <= 1e-6 || peakOf(low) < 1e-6f) return null
        val envC = pulseContrast(profileAligned(low, hopSec, 60.0 / envelopePeriod))
        val ranked = ArrayList<Pair<Double, Double>>()
        for (period in periods) {
            if (period <= 1e-6 || near(period, envelopePeriod, 0.03)) continue
            ranked.add(period to pulseContrast(profileAligned(low, hopSec, 60.0 / period)))
        }
        val qualified = ranked.filter { (_, c) ->
            c >= KICK_BEAT_CONTRAST && c >= envC + KICK_CONTRAST_MARGIN
        }
        Log.d(
            DIAG,
            "beat envBpm=${diagNum(60.0 / envelopePeriod)} c=${diagNum(envC)} " +
                ranked.joinToString(" | ") { (period, c) ->
                    "bpm=${diagNum(60.0 / period)} c=${diagNum(c)}"
                },
        )
        if (qualified.isEmpty()) return null
        val quarters = qualified.filter { (period, _) ->
            qualified.none { (other, _) -> near(period, other / 2.0, 0.03) }
        }
        return quarters.maxBy { it.second }.first
    }

    /**
     * Огибающую оставляем, пока бочка на темпе ударных не собрана заметно лучше.
     * Пустая бочка — песня на одном басу, огибающая и есть доля.
     */
    internal fun kickBeatsEnvelope(
        envelopePeriod: Double,
        drumPeriod: Double,
        kick: FloatArray,
        hopSec: Double,
    ): Boolean {
        if (envelopePeriod <= 1e-6 || drumPeriod <= 1e-6 || hopSec <= 0.0) return false
        if (near(envelopePeriod, drumPeriod, 0.03)) return false
        if (peakOf(kick) < 1e-6f) return false
        val envC = pulseContrast(profileAligned(kick, hopSec, 60.0 / envelopePeriod))
        val drumC = pulseContrast(profileAligned(kick, hopSec, 60.0 / drumPeriod))
        Log.d(
            DIAG,
            "envelope vs kick envBpm=${diagNum(60.0 / envelopePeriod)} c=${diagNum(envC)} " +
                "drumBpm=${diagNum(60.0 / drumPeriod)} c=${diagNum(drumC)}",
        )
        return drumC >= KICK_BEAT_CONTRAST && drumC >= envC + KICK_CONTRAST_MARGIN
    }

    /** 1 − среднее/пик. Ровная полоса около 0.1, удары с паузами около 0.5 и выше. */
    internal fun pulseContrast(bins: DoubleArray): Double {
        if (bins.isEmpty()) return 0.0
        var max = 0.0
        var sum = 0.0
        for (v in bins) {
            if (v > max) max = v
            sum += v
        }
        if (max <= 1e-9) return 0.0
        return 1.0 - (sum / bins.size) / max
    }

    /** Пик ниже 65 удваивается, пока не войдёт в окно. 37.5 → 75. Быстрее 165 не делим: это смазанные атаки. */
    private fun foldIntoSearch(bpm: Double): Double? {
        var value = bpm
        var guard = 0
        while (value < SEARCH_MIN_BPM && guard++ < 4) value *= 2.0
        return if (value in SEARCH_MIN_BPM..SEARCH_MAX_BPM) value else null
    }

    /**
     * Один период, а не гребёнка. Соседний пик, не кратный лучшему, должен быть тише [DOMINANT_RIVAL_RATIO].
     * Кратности 1/3, 1/2, 2/3, 3/2, 2 и 3 — гармоники того же темпа.
     */
    private class DominantRead(
        val bestBpm: Double,
        val bestC: Float,
        val rivalBpm: Double?,
        val rivalC: Float,
    )

    private fun dominantReadOf(activation: FloatArray, hopSec: Double): DominantRead? {
        val wide = lagRange(hopSec, activation.size, SEARCH_MIN_BPM / 2.0, SEARCH_MAX_BPM * 2.0) ?: return null
        return readDominant(autocorr(activation, wide.first, wide.second), hopSec, wide.first, wide.second)
    }

    /** Соседний некратный пик тише [DOMINANT_RIVAL_RATIO] от лучшего. */
    private fun isDominant(read: DominantRead?): Boolean =
        read != null && read.rivalC < read.bestC * DOMINANT_RIVAL_RATIO

    private fun dominantBpm(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): Double? {
        val read = readDominant(corr, hopSec, minLag, maxLag) ?: return null
        return if (isDominant(read)) read.bestBpm else null
    }

    private fun readDominant(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): DominantRead? {
        var bestBpm = 0.0
        var bestC = 0f
        val peaks = ArrayList<Pair<Double, Float>>()
        val last = minOf(maxLag, corr.lastIndex - 1)
        for (lag in (minLag + 1) until last) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val period = (lag + parabolic(corr, lag, minLag, maxLag)) * hopSec
            if (period <= 1e-6) continue
            val bpm = 60.0 / period
            peaks.add(bpm to c)
            if (c > bestC) {
                bestC = c
                bestBpm = bpm
            }
        }
        if (bestC <= 0f) return null
        var rival = 0f
        var rivalBpm: Double? = null
        for ((bpm, c) in peaks) {
            if (harmonicOf(bpm, bestBpm)) continue
            if (c > rival) {
                rival = c
                rivalBpm = bpm
            }
        }
        return DominantRead(bestBpm, bestC, rivalBpm, rival)
    }

    private fun harmonicOf(bpm: Double, fundamental: Double): Boolean {
        if (fundamental <= 1e-6) return false
        val ratio = bpm / fundamental
        return doubleArrayOf(1.0 / 3.0, 0.5, 2.0 / 3.0, 1.0, 1.5, 2.0, 3.0).any { near(ratio, it, 0.04) }
    }

    /** Бас и other, когда в ударных нет периода. Оба уже onset, пик на 1. */
    private fun bodyActivation(bass: FloatArray?, other: FloatArray?, n: Int): FloatArray? {
        val parts = ArrayList<FloatArray>(2)
        if (bass != null && bass.size == n && peakOf(bass) >= 1e-6f) parts.add(bass)
        if (other != null && other.size == n && peakOf(other) >= 1e-6f) parts.add(other)
        if (parts.isEmpty()) return null
        val out = FloatArray(n)
        for (part in parts) {
            for (i in 0 until n) out[i] += part[i]
        }
        return normalize(out)
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

    private fun topPeriods(
        corr: FloatArray,
        withSnare: FloatArray,
        kickOnset: FloatArray,
        hopSec: Double,
        minLag: Int,
        maxLag: Int,
    ): List<Double> {
        val found = ArrayList<Pair<Double, Float>>()
        val fastPeaks = ArrayList<Pair<Double, Float>>()
        var bestInRange = 0f
        for (lag in (minLag + 1) until maxLag) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val period = (lag + parabolic(corr, lag, minLag, maxLag)) * hopSec
            if (period <= 1e-6) continue
            val bpm = 60.0 / period
            if (bpm in SEARCH_MIN_BPM..SEARCH_MAX_BPM && c > bestInRange) bestInRange = c
            // Восьмая с тихим низом (малый на «и» у 105) тоже даёт пик около 210.
            // Пускаем его только если бочка на этой доле почти не бьёт — иначе это не четверть.
            if (bpm > SEARCH_MAX_BPM && bpm <= FAST_SEARCH_MAX && c >= DRUM_CORR_FLOOR &&
                kickPulse(kickOnset, period, period * 2.0, hopSec).ratio < FAST_KICK_ON_EIGHTH
            ) {
                fastPeaks.add(period to c)
            }
            for (factor in doubleArrayOf(0.5, 1.0, 1.5, 2.0)) {
                val folded = bpm * factor
                if (folded < SEARCH_MIN_BPM || folded > SEARCH_MAX_BPM) continue
                val dotted = factor == 1.5
                if (dotted && !withSnarePeak(withSnare, folded, hopSec, minLag, maxLag)) continue
                val source = if (dotted) withSnare else corr
                val foldedPeriod = refinePeriod(source, 60.0 / folded, hopSec, minLag, maxLag)
                val foldedBpm = 60.0 / foldedPeriod
                if (foldedBpm < SEARCH_MIN_BPM || foldedBpm > SEARCH_MAX_BPM) continue
                if (dotted && (foldedBpm < 90.0 || foldedBpm > 145.0)) continue
                found.add(foldedPeriod to tempoScore(corr, foldedBpm, hopSec, minLag, maxLag))
            }
        }
        if (bestInRange > 0f) {
            for ((period, height) in fastPeaks) {
                if (height < FAST_PEAK_RATIO * bestInRange) continue
                val foldedBpm = 60.0 / period
                found.add(period to tempoScore(corr, foldedBpm, hopSec, minLag, maxLag))
            }
        }
        found.sortByDescending { it.second }
        val ranked = ArrayList<Pair<Double, Float>>(found.size)
        for (item in found) {
            if (ranked.any { near(it.first, item.first, 0.03) }) continue
            ranked.add(item)
        }
        Log.d(DIAG, diagFolds(ranked))
        return ranked.take(4).map { it.first }
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

    /** Бочка и малый поровну: пик четверти виден, даже если в смеси ударных его глушит бочка. */
    private fun withSnareOnset(kick: FloatArray, midFlux: FloatArray?): FloatArray {
        if (midFlux == null) return kick
        val n = kick.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            val snare = if (i < midFlux.size) midFlux[i] else 0f
            out[i] = kick[i] + snare
        }
        return out
    }

    /** Кратность 1.5 только в 90..145 BPM и только на локальном пике withSnare. */
    private fun withSnarePeak(
        withSnare: FloatArray,
        bpm: Double,
        hopSec: Double,
        minLag: Int,
        maxLag: Int,
    ): Boolean {
        if (bpm < 90.0 || bpm > 145.0) return false
        val lag = (60.0 / bpm / hopSec).roundToInt()
        if (lag <= minLag || lag >= maxLag) return false
        val window = (lag * 0.04).toInt().coerceAtLeast(2)
        val from = (lag - window).coerceAtLeast(minLag + 1)
        val to = (lag + window).coerceAtMost(maxLag - 1)
        for (i in from..to) {
            if (withSnare[i] > 0f && withSnare[i] >= withSnare[i - 1] && withSnare[i] >= withSnare[i + 1]) {
                return true
            }
        }
        return false
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
        midFlux: FloatArray?,
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
        if (ratio >= OFFBEAT_IS_BEAT && !midFluxHoldsSlow(midFlux, fast, slow, hopSec)) return fast
        return slow
    }

    /**
     * Восьмые бочки: быстрый кандидат > 140, а пик малого на медленном периоде 70..85
     * сильнее пика на быстром. Удвоение тогда запрещено.
     */
    private fun midFluxHoldsSlow(
        midFlux: FloatArray?,
        fastLag: Int,
        slowLag: Int,
        hopSec: Double,
    ): Boolean {
        if (midFlux == null || hopSec <= 0.0 || fastLag <= 0 || slowLag <= 0) return false
        val fastBpm = 60.0 / (fastLag * hopSec)
        val slowBpm = 60.0 / (slowLag * hopSec)
        if (fastBpm <= 140.0 || slowBpm < 70.0 || slowBpm > 85.0) return false
        val range = lagRange(hopSec, midFlux.size, SEARCH_MIN_BPM, SEARCH_MAX_BPM) ?: return false
        val corr = autocorr(midFlux, range.first, range.second)
        return peakNear(corr, slowLag, range.first, range.second) >
            peakNear(corr, fastLag, range.first, range.second)
    }

    private fun peakNear(corr: FloatArray, lag: Int, minLag: Int, maxLag: Int): Float {
        if (lag !in corr.indices) return 0f
        val window = (lag * 0.04).toInt().coerceAtLeast(1)
        val from = (lag - window).coerceAtLeast(minLag).coerceAtLeast(0)
        val to = (lag + window).coerceAtMost(maxLag).coerceAtMost(corr.lastIndex)
        var best = 0f
        for (i in from..to) if (corr[i] > best) best = corr[i]
        return best
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

    private fun maxCorrBetween(corr: FloatArray, hopSec: Double, minBpm: Double, maxBpm: Double): Float {
        if (hopSec <= 0.0) return 0f
        val from = lagFor(maxBpm, hopSec).coerceIn(0, corr.lastIndex)
        val to = lagFor(minBpm, hopSec).coerceIn(from, corr.lastIndex)
        var best = 0f
        for (lag in from..to) if (corr[lag] > best) best = corr[lag]
        return best
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

    private fun snapToIntegerBpm(periodSec: Double): Double {
        if (periodSec <= 1e-9) return periodSec
        val bpm = 60.0 / periodSec
        val whole = round(bpm)
        return if (abs(bpm - whole) <= INTEGER_BPM_SNAP) 60.0 / whole else periodSec
    }

    private fun positiveMod(value: Double, period: Double): Double {
        if (period <= 1e-9) return 0.0
        var m = value % period
        if (m < 0.0) m += period
        return m
    }

    private fun diagNum(value: Double?): String =
        if (value == null || !value.isFinite()) "null" else String.format(Locale.US, "%.4f", value)

    internal class ChoiceDiag(val keepKick: Boolean, val text: String)

    /** Та же развилка, что [choose]: строка лога не может разойтись с тем, кого оставили. */
    internal fun choiceDiag(
        octave: Boolean,
        threeTwo: Boolean,
        pulse: Double?,
        realMidOnBothHalves: Boolean,
        bestScore: Double,
        kickScore: Double,
        octaveBelowFloor: Boolean = false,
    ): ChoiceDiag {
        val keepUnrelated = !octave && !threeTwo && bestScore < kickScore * UNRELATED_WIN_RATIO
        val keepKick = octaveBelowFloor ||
            (pulse != null && pulse >= FAST_PULSE_RATIO && !realMidOnBothHalves) ||
            (threeTwo && bestScore < kickScore * OVERRIDE_SCORE_RATIO) ||
            keepUnrelated
        val reason = when {
            octave -> "octave"
            threeTwo -> "threeTwo"
            else -> "unrelated"
        }
        val who = if (keepKick) "kick" else "best"
        return ChoiceDiag(keepKick, "keep=$who reason=$reason")
    }

    private fun diagOnOff(
        flux: FloatArray,
        phase: Double,
        period: Double,
        hopSec: Double,
        duration: Double,
    ): String {
        val on = midByParity(flux, phase, period, hopSec, duration).sum()
        val off = midByParity(flux, positiveMod(phase + period / 2.0, period), period, hopSec, duration).sum()
        return if (off <= 1e-9) "inf" else diagNum(on / off)
    }

    internal fun diagFolds(ranked: List<Pair<Double, Float>>): String {
        if (ranked.isEmpty()) return "fold none"
        return "fold " + ranked.take(ACF_PEAKS).joinToString(" | ") { (period, score) ->
            "bpm=${diagNum(60.0 / period)} s=${String.format(Locale.US, "%.6f", score)}"
        }
    }

    private fun formatContest(read: DominantRead?): String {
        if (read == null) return "best=null"
        val ratio = if (read.rivalBpm == null || read.bestC <= 1e-12f) {
            null
        } else {
            read.rivalC.toDouble() / read.bestC
        }
        return "best=${diagNum(read.bestBpm)} c=${diagNum(read.bestC.toDouble())} " +
            "rival=${diagNum(read.rivalBpm)} c=${diagNum(read.rivalC.toDouble())} ratio=${diagNum(ratio)}"
    }

    internal fun diagContest(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): String =
        formatContest(readDominant(corr, hopSec, minLag, maxLag))

    internal fun contestIsDominant(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): Boolean =
        isDominant(readDominant(corr, hopSec, minLag, maxLag))

    internal fun diagAttackRepeat(peaks: List<Pair<Double, Float>>, envelopeBpm: Double): String? {
        val repeat = repeatingAttack(peaks, envelopeBpm) ?: return null
        val ratio = repeat.corr / repeat.bestC
        return "rivalBpm=${diagNum(repeat.bpm)} c=${diagNum(repeat.corr.toDouble())} " +
            "bestC=${diagNum(repeat.bestC.toDouble())} ratio=${diagNum(ratio.toDouble())}"
    }

    private fun diagHypothesis(
        bpm: Double,
        low: FloatArray,
        mid: FloatArray?,
        high: FloatArray?,
        hopSec: Double,
    ): String {
        val period = 60.0 / bpm
        val shift = profileShift(low, hopSec, bpm)
        return "hypothesis bpm=${diagNum(bpm)} bin0=strongest low: " +
            diagBand("low", low, hopSec, period, shift) + " " +
            diagBand("mid", mid, hopSec, period, shift) + " " +
            diagBand("high", high, hopSec, period, shift)
    }

    private fun diagBand(
        name: String,
        energy: FloatArray?,
        hopSec: Double,
        period: Double,
        shift: Int,
    ): String {
        if (energy == null) return "$name=null"
        val turned = rotateProfile(profile(energy, hopSec, period, 0.0), shift)
        return "$name=" + turned.joinToString(" ", "[", "]") { String.format(Locale.US, "%.2f", it) }
    }

    /** bin0 — самая сильная корзина этого BPM, а не фаза уже выбранной сетки. */
    internal fun profileAligned(energy: FloatArray, hopSec: Double, bpm: Double): DoubleArray {
        val period = if (bpm <= 1e-6) 0.0 else 60.0 / bpm
        return rotateProfile(profile(energy, hopSec, period, 0.0), profileShift(energy, hopSec, bpm))
    }

    private fun profileShift(energy: FloatArray, hopSec: Double, bpm: Double): Int {
        if (bpm <= 1e-6 || hopSec <= 0.0) return 0
        val raw = profile(energy, hopSec, 60.0 / bpm, 0.0)
        var shift = 0
        for (i in 1 until raw.size) if (raw[i] > raw[shift]) shift = i
        return if (raw[shift] <= 0.0) 0 else shift
    }

    private fun rotateProfile(raw: DoubleArray, shift: Int): DoubleArray {
        if (raw.isEmpty() || shift == 0) return raw
        val n = raw.size
        val k = ((shift % n) + n) % n
        return DoubleArray(n) { raw[(it + k) % n] }
    }

    /**
     * Сильнейшие локальные максимумы автокорреляции ударных.
     * BPM — период самого лага, без кратностей 0.5 / 1 / 1.5 / 2 и без окна 65–165:
     * в кандидатах этих пиков уже нет, здесь видно, что автокорреляция нашла до отбора.
     */
    internal fun diagAcf(corr: FloatArray, hopSec: Double, minLag: Int, maxLag: Int): String {
        if (hopSec <= 0.0 || corr.size < 3 || maxLag <= minLag + 1) return "acf none"
        val peaks = ArrayList<Pair<Double, Float>>()
        val last = minOf(maxLag, corr.lastIndex)
        for (lag in (minLag + 1) until last) {
            val c = corr[lag]
            if (c <= 0f || c < corr[lag - 1] || c < corr[lag + 1]) continue
            val period = (lag + parabolic(corr, lag, minLag, maxLag)) * hopSec
            if (period <= 1e-6) continue
            peaks.add(60.0 / period to c)
        }
        if (peaks.isEmpty()) return "acf none"
        peaks.sortByDescending { it.second }
        return "acf " + peaks.take(ACF_PEAKS).joinToString(" | ") { (bpm, c) ->
            "bpm=${diagNum(bpm)} c=${diagNum(c.toDouble())}"
        }
    }

    private fun diagProfile(name: String, energy: FloatArray?, hopSec: Double, period: Double, phase: Double): String {
        if (energy == null) return "$name=null"
        return "$name=" + profile(energy, hopSec, period, phase)
            .joinToString(" ", "[", "]") { String.format(Locale.US, "%.2f", it) }
    }

    /**
     * Средний рост энергии по 16 позициям на две доли: позиция 0 — точка сетки, 8 — следующая доля,
     * 4 — восьмая после нуля. Нормирован на максимум; пустой или беззвучный вход даёт нули.
     */
    internal fun profile(energy: FloatArray, hopSec: Double, period: Double, phase: Double): DoubleArray {
        val sums = DoubleArray(PROFILE_BINS)
        val counts = IntArray(PROFILE_BINS)
        if (energy.isEmpty() || hopSec <= 0.0 || period <= 1e-6) return sums
        val binSec = period * 2.0 / PROFILE_BINS
        var prev = 0f
        for (i in energy.indices) {
            val rise = (energy[i] - prev).coerceAtLeast(0f)
            prev = energy[i]
            val rel = positiveMod((i + 0.5) * hopSec - phase, period * 2.0)
            val bin = (rel / binSec + 0.5).toInt() % PROFILE_BINS
            sums[bin] += rise.toDouble()
            counts[bin]++
        }
        var peak = 0.0
        for (b in 0 until PROFILE_BINS) {
            sums[b] = if (counts[b] > 0) sums[b] / counts[b] else 0.0
            if (sums[b] > peak) peak = sums[b]
        }
        if (peak < 1e-12) return DoubleArray(PROFILE_BINS)
        for (b in 0 until PROFILE_BINS) sums[b] /= peak
        return sums
    }
}
