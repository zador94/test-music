package com.rehearsal.app.audio

import kotlin.math.roundToLong

/**
 * Сетка долей в **media time** (presentationTimeUs), от t=0, по original BPM.
 * Целые микросекунды — без float ceil. После Sonic клики остаются на музыкальных битах,
 * потому что speed = metronomeBpm / originalBpm растягивает и музыку, и эти клики одинаково.
 */
object BeatClock {
    fun intervalUs(originalBpm: Float): Long {
        if (originalBpm <= 0f) return Long.MAX_VALUE
        return (60_000_000.0 / originalBpm.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    /** Доля 0 в t=0. Первая доля с временем ≥ [mediaTimeUs]. */
    fun beatAtOrAfter(mediaTimeUs: Long, intervalUs: Long): Long {
        if (intervalUs <= 0L) return 0L
        if (mediaTimeUs <= 0L) return 0L
        val n = (mediaTimeUs + intervalUs - 1L) / intervalUs
        return n * intervalUs
    }

    /**
     * Первая доля, чей клик ещё может пересекаться с [mediaStartUs]
     * (клик начался раньше и не успел закончиться).
     */
    fun firstOverlappingBeatUs(
        mediaStartUs: Long,
        intervalUs: Long,
        clickDurationUs: Long,
    ): Long {
        val duration = clickDurationUs.coerceAtLeast(1L)
        val earliest = mediaStartUs - duration + 1L
        return beatAtOrAfter(earliest, intervalUs)
    }

    fun mediaUsToSample(timeUs: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        if (timeUs <= 0L) return 0L
        return (timeUs * sampleRate.toLong() + 500_000L) / 1_000_000L
    }

    fun sampleToMediaUs(sample: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || sample <= 0L) return 0L
        return sample * 1_000_000L / sampleRate.toLong()
    }

    fun frameOffset(eventUs: Long, bufferStartUs: Long, sampleRate: Int): Int {
        val delta = mediaUsToSample(eventUs, sampleRate) - mediaUsToSample(bufferStartUs, sampleRate)
        return delta.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
