package com.rehearsal.app.audio

import kotlin.math.roundToLong

/**
 * Half/double в коридор ~70–180, как веб-детектор.
 * 60→120, 240→120, 102 остаётся, 51→102, 204→102.
 */
object TempoMath {
    const val MIN_BPM = 70.0
    const val MAX_BPM = 180.0
    const val DEFAULT_BPM = 120.0

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
