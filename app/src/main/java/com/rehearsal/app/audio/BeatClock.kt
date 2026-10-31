package com.rehearsal.app.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Сетка долей в **media time**. Шаг клика = [intervalFromPeriodSec],
 * не `60 / bpm` после округления до 0.1. [intervalUs] — запасной путь
 * без `periodSec` в JSON.
 */
object BeatClock {
    fun intervalUs(originalBpm: Float): Long {
        if (originalBpm <= 0f) return Long.MAX_VALUE
        return (60_000_000.0 / originalBpm.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    fun intervalFromPeriodSec(periodSec: Double): Long {
        if (!periodSec.isFinite() || periodSec <= 0.0) return Long.MAX_VALUE
        return (periodSec * 1_000_000.0).roundToLong().coerceAtLeast(1L)
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

    /** Время первой пересекающей метки; нет метки — [Long.MAX_VALUE]. */
    fun firstOverlappingBeat(
        timesUs: LongArray,
        mediaStartUs: Long,
        clickDurationUs: Long,
    ): Long {
        val i = firstOverlappingIndex(timesUs, mediaStartUs, clickDurationUs)
        if (i !in timesUs.indices) return Long.MAX_VALUE
        return timesUs[i]
    }

    /**
     * Есть ли доля в окне буфера ± один период. Если `beats[]` в другой
     * шкале, чем PTS стемов, ни один 23 мс chunk её не содержит — тогда
     * [MetronomeClickEngine] должен снова красить равную решётку.
     */
    fun hasBeatNear(
        timesUs: LongArray,
        startUs: Long,
        endUs: Long,
        intervalUs: Long,
    ): Boolean {
        if (timesUs.isEmpty()) return false
        val pad = intervalUs.coerceAtLeast(1L)
        val lo = startUs - pad
        val hi = endUs + pad
        val i = firstIndexAtOrAfter(timesUs, lo)
        return i < timesUs.size && timesUs[i] <= hi
    }

    /**
     * `analysis.json` хранит `beats[]` в **секундах**. Если значения уже
     * микросекунды (max > 10 с как если бы это были µs) — не умножаем снова.
     */
    fun beatTimesUs(beatsSec: DoubleArray): LongArray {
        if (beatsSec.isEmpty()) return LongArray(0)
        var max = 0.0
        for (t in beatsSec) if (t.isFinite() && t > max) max = t
        val alreadyUs = max > ALREADY_US_SEC
        val out = LongArray(beatsSec.size) { i ->
            val t = beatsSec[i]
            if (!t.isFinite() || t < 0.0) {
                0L
            } else if (alreadyUs) {
                t.roundToLong().coerceAtLeast(0L)
            } else {
                (t * 1_000_000.0).roundToLong().coerceAtLeast(0L)
            }
        }
        out.sort()
        return out
    }

    /**
     * Сетка клика в µs: шаг около [intervalUs], не 0/1 (секунды как Long)
     * и не десятки секунд (hop как «секунды»).
     */
    fun isPlausibleClickGrid(timesUs: LongArray, intervalUs: Long): Boolean {
        if (timesUs.size < 2) return false
        val dts = ArrayList<Long>(timesUs.size - 1)
        for (i in 1 until timesUs.size) {
            val dt = timesUs[i] - timesUs[i - 1]
            if (dt > 0L) dts.add(dt)
        }
        if (dts.isEmpty()) return false
        dts.sort()
        val median = dts[dts.size / 2]
        if (median < MIN_BEAT_STEP_US) return false
        val interval = intervalUs.coerceAtLeast(1L)
        return median >= interval / 4L && median <= interval * 4L
    }

    /** Короче 5 мс — не четверть; 10 с как число > 10000 — уже µs, не секунды. */
    const val MIN_BEAT_STEP_US = 5_000L
    const val ALREADY_US_SEC = 10_000.0
}
