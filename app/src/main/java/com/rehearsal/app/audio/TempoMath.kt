package com.rehearsal.app.audio

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Half/double в коридор ~60–180.
 * Баллады 60–70 не удваиваем: 65.5 иначе становится 131 (Skyfall ~75).
 * 51→102, 40→80, 240→120. 60 остаётся 60.
 */
object TempoMath {
    const val MIN_BPM = 60.0
    const val MAX_BPM = 180.0
    const val DEFAULT_BPM = 120.0
    /** 140–180 с половиной 70–90 — октава баллады (Skyfall 76.5→153). */
    const val DOUBLE_BALLAD_MIN = 140.0
    const val BALLAD_MAX = 90.0

    fun clampHalfDouble(bpm: Double, minBpm: Double = MIN_BPM, maxBpm: Double = MAX_BPM): Double {
        if (!bpm.isFinite() || bpm <= 0.0) return DEFAULT_BPM
        var tempo = bpm
        var guard = 0
        while (tempo < minBpm && guard < 8) {
            tempo *= 2.0
            guard++
        }
        while (tempo > maxBpm && guard < 16) {
            tempo /= 2.0
            guard++
        }
        if (tempo < minBpm || tempo > maxBpm) {
            tempo = tempo.coerceIn(minBpm, maxBpm)
        }
        return tempo
    }

    fun relativeClose(a: Double, b: Double, rel: Double = 0.08): Boolean {
        val scale = maxOf(abs(a), abs(b), 1.0)
        return abs(a - b) <= rel * scale
    }

    /**
     * Mix часто держит пульс песни (Skyfall ~75). KickGrid по drums может
     * сесть на 2× или на 2×·8/9 (65.5×2 = 131). Фазу всё равно считаем по drums.
     */
    fun preferMixIfDrumsOctave(mixBpm: Float, drumsBpm: Float): Float {
        if (!mixBpm.isFinite() || mixBpm <= 0f) return foldDoubleBallad(drumsBpm)
        if (!drumsBpm.isFinite() || drumsBpm <= 0f) return foldDoubleBallad(mixBpm)
        val mix = foldDoubleBallad(mixBpm.toDouble())
        val drums = foldDoubleBallad(drumsBpm.toDouble())
        if (relativeClose(drums, mix, 0.08)) return drums.toFloat()
        val ballad = mix in 60.0..110.0
        if (ballad && relativeClose(drums, mix * 2.0, 0.08)) return mix.toFloat()
        if (ballad && relativeClose(drums, mix * 8.0 / 9.0, 0.07) && drums >= MIN_BPM) return mix.toFloat()
        if (ballad && relativeClose(drums, mix * 9.0 / 8.0, 0.07)) return mix.toFloat()
        if (ballad && relativeClose(drums, mix * 16.0 / 9.0, 0.07)) return mix.toFloat()
        if (ballad && relativeClose(drums, mix * 2.0 * 8.0 / 9.0, 0.07)) return mix.toFloat()
        if (relativeClose(drums, mix * 0.5, 0.08) && drums >= MIN_BPM) return drums.toFloat()
        return drums.toFloat()
    }

    /**
     * 153 = 76.5×2, 150 = 75×2. Если и mix, и drums уже удвоены,
     * [preferMixIfDrumsOctave] их не разведёт — складываем сами.
     * 104.6 / 120 не трогаем.
     */
    fun foldDoubleBallad(bpm: Double): Double {
        if (!bpm.isFinite() || bpm <= 0.0) return bpm
        if (bpm >= DOUBLE_BALLAD_MIN && bpm <= MAX_BPM + 0.05) {
            val half = bpm / 2.0
            if (half >= MIN_BPM && half <= BALLAD_MAX) return half
        }
        return bpm
    }

    fun foldDoubleBallad(bpm: Float): Float = foldDoubleBallad(bpm.toDouble()).toFloat()

    /**
     * Drums — октава (или 8/9-октава баллады) от файла. Тогда BPM файла не трогаем.
     * 120 vs 104.6 не октава: fallback 120 не должен блокировать фанк.
     */
    fun isOctaveFamily(fileBpm: Double, drumsBpm: Double): Boolean {
        if (!fileBpm.isFinite() || fileBpm <= 0.0) return false
        if (!drumsBpm.isFinite() || drumsBpm <= 0.0) return false
        if (relativeClose(drumsBpm, fileBpm * 2.0, 0.08)) return true
        if (relativeClose(drumsBpm, fileBpm * 0.5, 0.08) && drumsBpm >= MIN_BPM) return true
        val ballad = fileBpm in 60.0..110.0
        if (!ballad) return false
        if (relativeClose(drumsBpm, fileBpm * 8.0 / 9.0, 0.07) && drumsBpm >= MIN_BPM) return true
        if (relativeClose(drumsBpm, fileBpm * 9.0 / 8.0, 0.07)) return true
        if (relativeClose(drumsBpm, fileBpm * 16.0 / 9.0, 0.07)) return true
        if (relativeClose(drumsBpm, fileBpm * 2.0 * 8.0 / 9.0, 0.07)) return true
        return false
    }

    fun secondsToMediaUs(seconds: Double): Long {
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        return (seconds * 1_000_000.0).roundToLong().coerceAtLeast(0L)
    }

    /**
     * BPM по наклону `beats[i] ≈ origin + i * period`.
     * Медиана сырых интервалов после snap к hop 512 остаётся квантованной
     * (±1 BPM). МНК по индексу это сглаживает.
     */
    fun bpmFromBeats(beats: DoubleArray, hintBpm: Double = DEFAULT_BPM): Double? {
        if (beats.size < 4) return null
        val hint = if (hintBpm.isFinite() && hintBpm > 0.0) hintBpm else DEFAULT_BPM
        val expected = 60.0 / hint
        val lo = expected * 0.72
        val hi = expected * 1.28
        val times = ArrayList<Double>(beats.size)
        times.add(beats[0])
        for (i in 1 until beats.size) {
            val d = beats[i] - times.last()
            if (d.isFinite() && d > lo && d < hi) times.add(beats[i])
        }
        if (times.size < 4) return null
        val n = times.size
        val meanX = (n - 1) / 2.0
        var meanY = 0.0
        for (t in times) meanY += t
        meanY /= n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            num += dx * (times[i] - meanY)
            den += dx * dx
        }
        if (den < 1e-9) return null
        val period = num / den
        if (!period.isFinite() || period <= 1e-6) return null
        return clampHalfDouble(60.0 / period)
    }
}
