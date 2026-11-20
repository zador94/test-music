package com.rehearsal.app.audio

import kotlin.math.roundToLong

/** Перевод секунд анализа в media time клика. */
object TempoMath {
    fun secondsToMediaUs(seconds: Double): Long {
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        return (seconds * 1_000_000.0).roundToLong().coerceAtLeast(0L)
    }
}
