package com.rehearsal.app.audio

import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.player.PlaybackMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Постановка долей как в Stembeat `process_audio.py`:
 * `librosa.onset.onset_strength` + `beat_track(..., tightness=100)` +
 * fold 70–180 + `downbeatOffset` индекс 0..3, не секунды.
 * Не ровная линейка `phase + n*period`.
 */
object Stembeat {
    const val HOP = 512
    const val N_FFT = 2048
    const val N_MELS = 40
    const val FMAX_HZ = 8000.0
    const val TIGHTNESS = 100.0
    const val FOLD_MIN = 70.0
    const val FOLD_MAX = 180.0
    const val START_BPM = 120.0
    const val STD_BPM = 1.0
    /**
     * Середина медленной сетки считается настоящей четвертью, если она
     * почти такой же силы, как доли. Хет баллады тише бочки и ниже порога;
     * удар на каждой четверти 128/160 — нет, темп не складываем.
     */
    const val OCTAVE_OFFBEAT = 0.72
    /** Ниже этого RMS в полосе до 150 Гц низ не считаем пульсом. */
    private const val LOW_BAND_PROBE = 0.08f

    data class Tracked(
        val bpm: Double,
        val beats: DoubleArray,
        val periodSec: Double,
        val downbeatOffset: Int,
        val onset: FloatArray,
    )

    fun analyze(pcm: StereoPcm): TrackAnalysis {
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val samples = pcm.samples
        val duration = samples.toDouble() / sr
        if (samples < sr / 2) {
            return TrackAnalysis.fallbackGrid(samples, sr).copy(kickGridVersion = KickGrid.VERSION)
        }
        val tracked = track(pcm)
        val phase = tracked.beats.firstOrNull() ?: 0.0
        return TrackAnalysis(
            bpm = PlaybackMath.roundToTenth(tracked.bpm.toFloat()),
            beats = tracked.beats,
            downbeatOffset = tracked.downbeatOffset.toDouble(),
            timeSignature = intArrayOf(4, 4),
            duration = duration,
            sampleRate = sr,
            separator = SplitKind.HTDEMUCS_4STEM,
            stems = TrackAnalysis.DEFAULT_STEMS,
            kickPhaseSec = phase,
            kickGridVersion = KickGrid.VERSION,
            periodSec = tracked.periodSec,
            phaseSec = phase,
        )
    }

    fun track(pcm: StereoPcm): Tracked {
        val sr = pcm.sampleRate.coerceAtLeast(1)
        val n = pcm.samples
        val duration = n.toDouble() / sr
        val mono = FloatArray(n) { i -> 0.5f * (pcm.left[i] + pcm.right[i]) }
        val onset = onsetStrength(mono, sr)
        val rawBpm = estimateTempo(onset, sr)
        val noveltyBpm = TempoDetector.estimateBpm(onset, HOP, sr)
        val bpm = resolveOctave(
            octaveProbe(pcm, onset),
            sr,
            foldTempo(preferBalladTempo(rawBpm, noveltyBpm)),
        )
        val frames = beatTrack(onset, sr, bpm)
        val beats = framesToTime(frames, sr)
            .filter { it >= 0.0 && it < duration - 1e-9 }
            .toDoubleArray()
        val period = periodOf(beats, bpm)
        val downbeat = downbeatOffset(onset, beats, sr)
        return Tracked(bpm, beats, period, downbeat, onset)
    }

    /**
     * Крайние оценки в 55–180: выше 180 делим пополам, ниже 55 удваиваем.
     * 55–70 оставляем (ровно 55 тоже: раньше `2× > 110` пропускало 55→110).
     * 110–180 здесь не режем. Иначе 128, 150 и 160 всегда становились
     * балладой. Октаву 65.5→131 и 76→153 решает [resolveOctave].
     */
    fun foldTempo(bpm: Double): Double {
        if (!bpm.isFinite() || bpm <= 0.0) return START_BPM
        var t = bpm
        var guard = 0
        while (t > FOLD_MAX && guard < 8) {
            t /= 2.0
            guard++
        }
        while (t < FOLD_MIN && guard < 16) {
            if (t >= 55.0) break
            t *= 2.0
            guard++
        }
        return t
    }

    /**
     * Для октавы берём низ, если в нём есть удары: хет на 7 кГц в этой
     * полосе не живёт и не рисует ложную четверть. Тихий низ (щелчок
     * синтеза на 2 кГц) — оставляем полную огибающую.
     */
    internal fun octaveProbe(pcm: StereoPcm, onset: FloatArray): FloatArray {
        val energy = KickGrid.lowBandEnergy(pcm, HOP, KickGrid.LOWPASS_HZ)
        var peak = 0f
        for (v in energy) if (v > peak) peak = v
        if (peak < LOW_BAND_PROBE || energy.size < 8) return onset
        return KickGrid.fluxOf(energy)
    }

    /**
     * 110–180 → половина, только если половина в 55–90 и на середине
     * этой сетки нет своей атаки. Пустая середина — это удвоение баллады
     * (131 при ударах на 65.5, 153 при ударах на 76.5). Середина, где
     * стоит такой же удар, — настоящие 120/128/150/160.
     * Если оценка уже пришла как 80, а середина полная, поднимаем до 160.
     * Ненадёжный замер темп не меняет.
     */
    internal fun resolveOctave(onset: FloatArray, sampleRate: Int, bpm: Double): Double {
        if (!bpm.isFinite() || bpm <= 0.0) return START_BPM
        if (bpm in 110.0..FOLD_MAX) {
            val half = bpm / 2.0
            if (half in 55.0..TempoMath.BALLAD_MAX) {
                val off = offbeatRatio(onset, sampleRate, half)
                if (off < 0.0) return bpm
                return if (off < OCTAVE_OFFBEAT) half else bpm
            }
        }
        // Оценка уже села на половину (80 вместо 160). Поднимаем, только если
        // на середине медленной сетки такой же удар, а не тихий хет.
        if (bpm in 55.0..TempoMath.BALLAD_MAX) {
            val fast = bpm * 2.0
            if (fast in 110.0..FOLD_MAX) {
                val off = offbeatRatio(onset, sampleRate, bpm)
                if (off >= OCTAVE_OFFBEAT) return fast
            }
        }
        return bpm
    }

    /**
     * Сила атаки на середине периода [bpm] относительно самой доли.
     * По длинной записи это медиана окон по 12 с: один громкий припев
     * иначе перебивает куплет и оставляет удвоенный темп (Skyfall 151).
     * Отрицательное значение: сетку не разглядеть, октаву не трогаем.
     */
    internal fun offbeatRatio(onset: FloatArray, sampleRate: Int, bpm: Double): Double {
        if (onset.size < 8 || !bpm.isFinite() || bpm <= 0.0) return -1.0
        val sr = sampleRate.coerceAtLeast(1)
        val win = (sr.toDouble() / HOP * 12.0).toInt().coerceAtLeast(32)
        if (onset.size >= win * 2) {
            val hop = (win / 4).coerceAtLeast(1)
            val ratios = ArrayList<Double>()
            var start = 0
            while (start + win <= onset.size) {
                val off = offbeatOfSpan(onset, start, start + win, sr, bpm)
                if (off >= 0.0) ratios.add(off)
                start += hop
            }
            if (ratios.size >= 3) {
                ratios.sort()
                return ratios[ratios.size / 2]
            }
        }
        return offbeatOfSpan(onset, 0, onset.size, sr, bpm)
    }

    private fun offbeatOfSpan(
        onset: FloatArray,
        from: Int,
        to: Int,
        sampleRate: Int,
        bpm: Double,
    ): Double {
        val length = to - from
        if (length < 8) return -1.0
        val period = sampleRate.toDouble() / HOP * 60.0 / bpm
        if (period < 4.0 || period * 4.0 > length) return -1.0
        val steps = period.toInt().coerceIn(8, 256)
        var bestBeat = 0.0
        var bestOff = 0.0
        var found = false
        for (step in 0 until steps) {
            val offset = step.toDouble() / steps * period
            var beat = 0.0
            var off = 0.0
            var hits = 0
            var pos = offset
            while (pos + period / 2.0 < length) {
                beat += readPeak(onset, from + pos)
                off += readPeak(onset, from + pos + period / 2.0)
                hits++
                pos += period
            }
            if (hits < 4) continue
            beat /= hits
            off /= hits
            if (!found || beat > bestBeat) {
                found = true
                bestBeat = beat
                bestOff = off
            }
        }
        if (!found || bestBeat <= 1e-6) return -1.0
        return (bestOff / bestBeat).coerceIn(0.0, 2.0)
    }

    private fun readPeak(x: FloatArray, index: Double): Double {
        val i = floor(index).toInt()
        var peak = 0.0
        for (k in (i - 1)..(i + 1)) {
            if (k in x.indices && x[k] > peak) peak = x[k].toDouble()
        }
        return peak
    }

    private fun preferBalladTempo(a: Double, b: Double): Double {
        val fa = foldTempo(a)
        val fb = foldTempo(b)
        // 65.5 против 131. Рядом с 120 (60 против 120, 64 против 128) не выбираем
        // вслепую: это может быть настоящая четверть, её проверит resolveOctave.
        if (slowBalladAgainstDouble(fa, fb)) return fa
        if (slowBalladAgainstDouble(fb, fa)) return fb
        val aBallad = fa in 70.0..TempoMath.BALLAD_MAX
        val bBallad = fb in 70.0..TempoMath.BALLAD_MAX
        if (bBallad && !aBallad) return fb
        if (aBallad && !bBallad) return fa
        return fa
    }

    private fun slowBalladAgainstDouble(slow: Double, fast: Double): Boolean {
        if (slow !in 55.0..70.0) return false
        if (fast !in 110.0..139.0) return false
        if (TempoMath.relativeClose(fast, START_BPM, 0.08)) return false
        return TempoMath.relativeClose(fast, slow * 2.0, 0.08)
    }

    fun onsetStrength(mono: FloatArray, sampleRate: Int): FloatArray {
        val sr = sampleRate.coerceAtLeast(1)
        val hop = HOP
        val nFft = N_FFT
        val nFrames = max(1, (mono.size + hop - 1) / hop)
        val nBins = nFft / 2 + 1
        val filters = melFilters(sr, nBins)
        val window = hann(nFft)
        val pad = nFft / 2
        val padded = FloatArray(mono.size + 2 * pad)
        mono.copyInto(padded, pad)
        val flux = FloatArray(nFrames)
        val prevMel = DoubleArray(N_MELS)
        val curMel = DoubleArray(N_MELS)
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val power = DoubleArray(nBins)
        for (f in 0 until nFrames) {
            val start = f * hop
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            var i = 0
            while (i < nFft) {
                val idx = start + i
                val s = if (idx in padded.indices) padded[idx].toDouble() else 0.0
                re[i] = s * window[i]
                i++
            }
            TrackTempo.transform(re, im)
            for (k in 0 until nBins) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }
            for (m in 0 until N_MELS) {
                var acc = 0.0
                val filt = filters[m]
                for (k in filt.indices) acc += power[k] * filt[k]
                curMel[m] = ln(1.0 + acc)
            }
            var fluxAcc = 0.0
            for (m in 0 until N_MELS) {
                val prev = if (f > 0) prevMel[m] else curMel[m]
                fluxAcc += max(0.0, curMel[m] - prev)
                prevMel[m] = curMel[m]
            }
            flux[f] = (fluxAcc / N_MELS).toFloat()
        }
        val td = timeDomainOnset(mono, hop)
        var peakMel = 1e-8f
        var peakTd = 1e-8f
        for (v in flux) if (v > peakMel) peakMel = v
        for (v in td) if (v > peakTd) peakTd = v
        val invMel = 1f / peakMel
        val invTd = 1f / peakTd
        val n = min(flux.size, td.size)
        for (i in 0 until n) {
            flux[i] = max(flux[i] * invMel, td[i] * invTd)
        }
        return flux
    }

    private fun timeDomainOnset(mono: FloatArray, hop: Int): FloatArray {
        val frames = max(1, (mono.size + hop - 1) / hop)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val end = min(start + hop, mono.size)
            var sum = 0.0
            for (i in start until end) {
                val prev = if (i > 0) i - 1 else 0
                val hp = mono[i] - mono[prev]
                sum += hp * hp
            }
            energy[f] = sqrt(sum / (end - start).coerceAtLeast(1)).toFloat()
        }
        val flux = FloatArray(frames)
        var prev = 0f
        var peak = 1e-8f
        for (f in 0 until frames) {
            val v = (energy[f] - prev).coerceAtLeast(0f)
            prev = energy[f]
            flux[f] = v
            if (v > peak) peak = v
        }
        val inv = 1f / peak
        for (i in flux.indices) flux[i] *= inv
        return flux
    }

    internal fun estimateTempo(onset: FloatArray, sampleRate: Int): Double {
        if (onset.size < 8) return START_BPM
        val sr = sampleRate.coerceAtLeast(1)
        val minLag = lagForBpm(FOLD_MAX * 2.0, sr).coerceAtLeast(2)
        val maxLag = lagForBpm(40.0, sr).coerceAtMost(onset.size / 2).coerceAtLeast(minLag)
        val ac = autocorr(onset, minLag, maxLag)
        var bestLag = lagForBpm(START_BPM, sr).coerceIn(minLag, maxLag)
        var best = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * sr / (lag.toDouble() * HOP)
            val prior = exp(-0.5 * sq(log2(bpm / START_BPM) / STD_BPM))
            val score = ac[lag].toDouble() * prior
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        val prev = ac.getOrElse(bestLag - 1) { ac[bestLag] }
        val center = ac[bestLag]
        val next = ac.getOrElse(bestLag + 1) { ac[bestLag] }
        val denom = (prev - 2f * center + next).toDouble()
        val delta = if (abs(denom) < 1e-12) 0.0 else 0.5 * (prev - next) / denom
        val lag = (bestLag + delta).coerceAtLeast(1.0)
        return 60.0 * sr / (lag * HOP)
    }

    /**
     * Ellis DP, `tightness=100`, как `librosa.beat.beat_track`.
     */
    internal fun beatTrack(onset: FloatArray, sampleRate: Int, bpm: Double): IntArray {
        if (onset.isEmpty() || bpm <= 0.0) return IntArray(0)
        val sr = sampleRate.coerceAtLeast(1)
        val frameRate = sr.toDouble() / HOP
        val fpb = (frameRate * 60.0 / bpm).roundToInt().coerceAtLeast(2)
        val local = localScore(normalizeOnsets(onset), fpb)
        val (backlink, cumscore) = beatTrackDp(local, fpb, TIGHTNESS)
        val tail = lastBeat(cumscore)
        val flags = BooleanArray(onset.size)
        var n = tail
        var guard = 0
        while (n >= 0 && guard < onset.size + 2) {
            flags[n] = true
            n = backlink[n]
            guard++
        }
        val beforeTrim = flags.count { it }
        trimBeats(local, flags)
        if (beforeTrim > 0 && flags.none { it }) {
            for (i in flags.indices) flags[i] = false
            n = tail
            guard = 0
            while (n >= 0 && guard < onset.size + 2) {
                flags[n] = true
                n = backlink[n]
                guard++
            }
        }
        val picked = ArrayList<Int>()
        for (i in flags.indices) if (flags[i]) picked.add(i)
        val peaks = if (picked.size >= minEllisBeats(onset.size, fpb)) {
            IntArray(0)
        } else {
            peakPick(onset, fpb)
        }
        return selectBeatFrames(picked.toIntArray(), peaks, onset.size, fpb)
    }

    /**
     * Ellis оставляем, когда долей хотя бы три четверти от ожидаемых.
     * Гребёнка в ±1/3 периода садится на хет, поэтому более длинный
     * список пиков сам по себе путь не заменяет. Короткий путь Ellis
     * (примерно половина четвертей, как на ровных щелчках) — уже не
     * готовая сетка, тогда берём гребёнку.
     */
    internal fun selectBeatFrames(
        picked: IntArray,
        peaks: IntArray,
        frameCount: Int,
        framesPerBeat: Int,
    ): IntArray {
        if (picked.size >= minEllisBeats(frameCount, framesPerBeat)) return picked
        return if (peaks.size > picked.size) peaks else picked
    }

    private fun minEllisBeats(frameCount: Int, framesPerBeat: Int): Int {
        val expected = (frameCount / framesPerBeat.coerceAtLeast(1)).coerceAtLeast(2)
        return (expected * 3 / 4).coerceAtLeast(4)
    }

    private fun peakPick(onset: FloatArray, framesPerBeat: Int): IntArray {
        val fpb = framesPerBeat.coerceAtLeast(2)
        if (onset.isEmpty()) return IntArray(0)
        var peak = 1e-8f
        for (v in onset) if (v > peak) peak = v
        val thresh = peak * 0.08f
        var bestOff = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val startLimit = min(fpb, onset.size)
        for (off in 0 until startLimit) {
            var score = 0.0
            var pos = off
            var n = 0
            while (pos < onset.size) {
                score += onset[pos]
                n++
                pos += fpb
            }
            if (n > 0) score /= n
            if (score > bestScore) {
                bestScore = score
                bestOff = off
            }
        }
        val half = (fpb / 3).coerceAtLeast(1)
        val out = ArrayList<Int>()
        var pos = bestOff
        while (pos < onset.size) {
            val from = (pos - half).coerceAtLeast(0)
            val to = (pos + half).coerceAtMost(onset.lastIndex)
            var best = pos.coerceIn(0, onset.lastIndex)
            var bestV = onset[best]
            for (j in from..to) {
                if (onset[j] > bestV) {
                    bestV = onset[j]
                    best = j
                }
            }
            if (bestV >= thresh && (out.isEmpty() || best - out.last() > fpb * 0.4)) {
                out.add(best)
            }
            pos += fpb
        }
        return out.toIntArray()
    }

    fun downbeatOffset(onset: FloatArray, beatsSec: DoubleArray, sampleRate: Int): Int {
        if (beatsSec.size < 4 || onset.isEmpty()) return 0
        val sr = sampleRate.coerceAtLeast(1)
        val scores = DoubleArray(4)
        for (i in beatsSec.indices) {
            val frame = (beatsSec[i] * sr / HOP).roundToInt()
            if (frame in onset.indices) scores[i % 4] += onset[frame].toDouble()
        }
        var best = 0
        for (k in 1..3) if (scores[k] > scores[best]) best = k
        return best
    }

    private fun localScore(onset: FloatArray, framesPerBeat: Int): FloatArray {
        val n = onset.size
        val fpb = framesPerBeat.coerceAtLeast(1)
        val window = FloatArray(2 * fpb + 1) { k ->
            val x = (k - fpb).toDouble() * 32.0 / fpb
            exp(-0.5 * x * x).toFloat()
        }
        val kLen = window.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            val from = max(0, i + kLen / 2 - n + 1)
            val to = min(i + kLen / 2, kLen - 1)
            for (k in from..to) {
                acc += window[k] * onset[i + kLen / 2 - k]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    private fun beatTrackDp(
        localscore: FloatArray,
        framesPerBeat: Int,
        tightness: Double,
    ): Pair<IntArray, FloatArray> {
        val n = localscore.size
        val backlink = IntArray(n) { -1 }
        val cumscore = FloatArray(n)
        if (n == 0) return backlink to cumscore
        var maxLocal = 0f
        for (v in localscore) if (v > maxLocal) maxLocal = v
        val scoreThresh = 0.01f * maxLocal
        var firstBeat = true
        val fpb = framesPerBeat.coerceAtLeast(1)
        val logFpb = ln(fpb.toDouble())
        for (i in 0 until n) {
            var bestScore = Double.NEGATIVE_INFINITY
            var beatLocation = -1
            val searchFrom = i - (fpb / 2.0).roundToInt()
            val searchTo = i - 2 * fpb - 1
            var loc = searchFrom
            while (loc > searchTo) {
                if (loc < 0) break
                val dt = (i - loc).toDouble().coerceAtLeast(1.0)
                val score = cumscore[loc] - tightness * sq(ln(dt) - logFpb)
                if (score > bestScore) {
                    bestScore = score
                    beatLocation = loc
                }
                loc--
            }
            val scoreI = localscore[i]
            cumscore[i] = if (beatLocation >= 0) {
                (scoreI + bestScore).toFloat()
            } else {
                scoreI
            }
            if (firstBeat && scoreI < scoreThresh) {
                backlink[i] = -1
            } else {
                backlink[i] = beatLocation
                firstBeat = false
            }
        }
        return backlink to cumscore
    }

    private fun lastBeat(cumscore: FloatArray): Int {
        if (cumscore.isEmpty()) return -1
        val peaks = ArrayList<Float>()
        for (i in cumscore.indices) {
            if (isLocalMax(cumscore, i)) peaks.add(cumscore[i])
        }
        val median = if (peaks.isEmpty()) {
            var m = cumscore[0]
            for (v in cumscore) if (v > m) m = v
            m
        } else {
            peaks.sorted()[peaks.size / 2]
        }
        val threshold = 0.5f * median
        var n = cumscore.lastIndex
        while (n >= 0) {
            if (isLocalMax(cumscore, n) && cumscore[n] >= threshold) return n
            n--
        }
        return cumscore.lastIndex
    }

    private fun trimBeats(localscore: FloatArray, beats: BooleanArray) {
        val n = localscore.size
        if (n == 0) return
        val selected = ArrayList<Float>()
        for (i in beats.indices) if (beats[i]) selected.add(localscore[i])
        if (selected.isEmpty()) return
        val hann5 = floatArrayOf(0.25f, 0.5f, 1f, 0.5f, 0.25f)
        var energy = 0.0
        for (i in selected.indices) {
            var acc = 0.0
            for (k in hann5.indices) {
                val j = i + k - 2
                val v = selected.getOrElse(j) { 0f }
                acc += hann5[k] * v
            }
            energy += acc * acc
        }
        val threshold = 0.5 * sqrt(energy / selected.size)
        if (threshold <= 1e-9) return
        val copy = beats.copyOf()
        var i = 0
        while (i < n && localscore[i] <= threshold) {
            beats[i] = false
            i++
        }
        i = n - 1
        while (i >= 0 && localscore[i] <= threshold) {
            beats[i] = false
            i--
        }
        if (beats.none { it } && copy.any { it }) {
            for (k in copy.indices) beats[k] = copy[k]
        }
    }

    private fun normalizeOnsets(onsets: FloatArray): FloatArray {
        if (onsets.size < 2) return onsets.copyOf()
        var mean = 0.0
        for (v in onsets) mean += v
        mean /= onsets.size
        var varSum = 0.0
        for (v in onsets) varSum += (v - mean) * (v - mean)
        val std = sqrt(varSum / (onsets.size - 1)).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(onsets.size) { i -> onsets[i] / std }
    }

    private fun framesToTime(frames: IntArray, sampleRate: Int): DoubleArray {
        val sr = sampleRate.coerceAtLeast(1)
        return DoubleArray(frames.size) { i -> frames[i].toDouble() * HOP / sr }
    }

    private fun periodOf(beats: DoubleArray, bpm: Double): Double {
        val dts = ArrayList<Double>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d > 1e-4) dts.add(d)
        }
        if (dts.size >= 2) {
            dts.sort()
            return dts[dts.size / 2]
        }
        return 60.0 / bpm.coerceAtLeast(1.0)
    }

    private fun lagForBpm(bpm: Double, sampleRate: Int): Int {
        val period = 60.0 / bpm.coerceAtLeast(1.0)
        return (period * sampleRate / HOP).roundToInt().coerceAtLeast(1)
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

    private fun melFilters(sampleRate: Int, nBins: Int): Array<DoubleArray> {
        val sr = sampleRate.coerceAtLeast(1)
        val fmax = min(FMAX_HZ, sr / 2.0)
        val mels = DoubleArray(N_MELS + 2) { i ->
            val t = i.toDouble() / (N_MELS + 1)
            melToHz(hzToMel(0.0) + t * (hzToMel(fmax) - hzToMel(0.0)))
        }
        val fftFreqs = DoubleArray(nBins) { k -> k.toDouble() * sr / N_FFT }
        val filters = Array(N_MELS) { DoubleArray(nBins) }
        for (m in 0 until N_MELS) {
            val left = mels[m]
            val center = mels[m + 1]
            val right = mels[m + 2]
            for (k in 0 until nBins) {
                val f = fftFreqs[k]
                val rise = if (center > left) (f - left) / (center - left) else 0.0
                val fall = if (right > center) (right - f) / (right - center) else 0.0
                filters[m][k] = max(0.0, min(rise, fall))
            }
        }
        return filters
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    private fun hann(n: Int): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        return DoubleArray(n) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1))) }
    }

    private fun isLocalMax(x: FloatArray, i: Int): Boolean {
        if (i !in x.indices) return false
        val v = x[i]
        val left = x.getOrElse(i - 1) { v - 1f }
        val right = x.getOrElse(i + 1) { v - 1f }
        return v >= left && v >= right
    }

    private fun log2(x: Double): Double = ln(x.coerceAtLeast(1e-12)) / ln(2.0)

    private fun sq(x: Double): Double = x * x
}
