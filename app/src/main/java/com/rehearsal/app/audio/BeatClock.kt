package com.rehearsal.app.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Сетка долей в **media time**. Шаг = 60/originalBpm (дробный, не `toInt`).
 * Фаза — kickPhase из ударных плюс ручная калибровка, не `beats[]`.
 */
object BeatClock {
    fun intervalUs(originalBpm: Float): Long {
        if (originalBpm <= 0f) return Long.MAX_VALUE
        return (60_000_000.0 / originalBpm.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    /** origin = (фаза бочки + калибровка) mod период. Калибровка может быть отрицательной. */
    fun gridOriginUs(phaseUs: Long, calibrationUs: Long, intervalUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        return (phaseUs + calibrationUs).mod(interval)
    }

    /**
     * Фаза сетки по `beats[]` — только для старых тестов математики кругового среднего.
     * Прод-путь клика это не вызывает: фаза из KickGrid.
     */
    fun kickPhaseUs(beatTimesUs: LongArray, intervalUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        val times = ArrayList<Long>(beatTimesUs.size)
        for (t in beatTimesUs) {
            if (t >= 0L) times.add(t)
        }
        if (times.isEmpty()) return 0L
        return circularMeanPhaseUs(times, interval)
    }

    /** Origin only later (mod interval). Never pull earlier. */
    fun delayLater(originUs: Long, intervalUs: Long, delayUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        val delay = delayUs.mod(interval)
        return (originUs.mod(interval) + delay) % interval
    }

    /**
     * Клик раньше бочки на [kickUs]-[clickUs]: origin += lead (mod interval).
     * Отрицательный lead (клик уже позже) не трогаем — не двигаем раньше.
     */
    fun alignOriginLater(originUs: Long, clickUs: Long, kickUs: Long, intervalUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        val origin = originUs.mod(interval)
        val lead = kickUs - clickUs
        if (lead <= 0L) return origin
        val phaseLead = lead % interval
        if (phaseLead == 0L) return origin
        return (origin + phaseLead) % interval
    }

    fun circularMeanPhaseUs(timesUs: List<Long>, intervalUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        if (timesUs.isEmpty()) return 0L
        var sinSum = 0.0
        var cosSum = 0.0
        for (t in timesUs) {
            val ang = 2.0 * PI * (t.mod(interval).toDouble() / interval.toDouble())
            sinSum += sin(ang)
            cosSum += cos(ang)
        }
        val n = timesUs.size.toDouble()
        var turns = atan2(sinSum / n, cosSum / n) / (2.0 * PI)
        if (turns < 0.0) turns += 1.0
        return (turns * interval).roundToLong().mod(interval)
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
