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

    /** Первая доля с временем ≥ [mediaTimeUs]. Сетка от [originUs], шаг [intervalUs]. */
    fun beatAtOrAfter(mediaTimeUs: Long, intervalUs: Long, originUs: Long = 0L): Long {
        val origin = originUs.coerceAtLeast(0L)
        if (intervalUs <= 0L) return origin
        if (mediaTimeUs <= origin) return origin
        val delta = mediaTimeUs - origin
        val n = (delta + intervalUs - 1L) / intervalUs
        return origin + n * intervalUs
    }

    /**
     * Первая доля, чей клик ещё может пересекаться с [mediaStartUs]
     * (клик начался раньше и не успел закончиться).
     */
    fun firstOverlappingBeatUs(
        mediaStartUs: Long,
        intervalUs: Long,
        clickDurationUs: Long,
        originUs: Long = 0L,
    ): Long {
        val duration = clickDurationUs.coerceAtLeast(1L)
        val earliest = mediaStartUs - duration + 1L
        return beatAtOrAfter(earliest, intervalUs, originUs)
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

    /** Нижний индекс в отсортированном [timesUs] с временем ≥ [timeUs]. */
    fun firstIndexAtOrAfter(timesUs: LongArray, timeUs: Long): Int {
        var lo = 0
        var hi = timesUs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timesUs[mid] < timeUs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Первая метка `beats[i]`, чей клик ещё пересекает [mediaStartUs].
     * Не «догоняем через секунду» — ближайшая доля в текущем PCM-буфере.
     */
    fun firstOverlappingIndex(
        timesUs: LongArray,
        mediaStartUs: Long,
        clickDurationUs: Long,
    ): Int {
        val earliest = mediaStartUs - clickDurationUs.coerceAtLeast(1L) + 1L
        return firstIndexAtOrAfter(timesUs, earliest)
    }
}
