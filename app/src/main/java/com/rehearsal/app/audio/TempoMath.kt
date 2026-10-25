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
}
