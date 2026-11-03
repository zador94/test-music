package com.rehearsal.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
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
     * [MetronomeClickEngine.mixInto] красит равную решётку, а не тишину.
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

    /**
     * Шаг, который отличается от соседних четвертей больше чем на ~40 мс
     * (или 8% периода), заменяется локальной медианой. Одна сорвавшаяся
     * доля больше не сдвигает все следующие клики. Медленный уход темпа,
     * где каждый шаг рядом с соседями, остаётся.
     */
    fun straightenQuarters(timesUs: LongArray): LongArray {
        if (timesUs.size < 4) return timesUs
        val dt = LongArray(timesUs.size - 1) { i -> timesUs[i + 1] - timesUs[i] }
        for (d in dt) if (d <= 0L) return timesUs
        val out = LongArray(timesUs.size)
        out[0] = timesUs[0]
        for (i in dt.indices) {
            val ref = localMedian(dt, i, width = 4)
            val tol = max(40_000L, (ref * 0.08).toLong())
            val step = if (abs(dt[i] - ref) > tol) ref else dt[i]
            out[i + 1] = out[i] + step
        }
        return out
    }

    private fun localMedian(values: LongArray, index: Int, width: Int): Long {
        val from = (index - width).coerceAtLeast(0)
        val to = (index + width).coerceAtMost(values.lastIndex)
        val window = LongArray(to - from + 1)
        for (i in window.indices) window[i] = values[from + i]
        window.sort()
        return window[window.size / 2]
    }

    /**
     * Доли, которые уже лежат в песне, продлевает тем же средним шагом
     * до t=0 и до [durationUs]. Шаг — (последняя − первая) / (n − 1),
     * не `periodSec`: у 102 BPM медиана хопа бывает 101.3, а сами доли
     * идут ровно в 102. Чужие метки вне длительности не разворачиваем
     * в сетку по всей песне.
     */
    fun extendClickBeats(timesUs: LongArray, durationUs: Long): LongArray {
        if (durationUs <= 0L || timesUs.size < 2) return timesUs
        val inside = ArrayList<Long>(timesUs.size)
        for (t in timesUs) {
            if (t in 0L..durationUs) inside.add(t)
        }
        if (inside.size < 2) return timesUs
        val span = inside.last() - inside.first()
        val nSteps = inside.size - 1
        if (span <= 0L) return timesUs
        val step = span.toDouble() / nSteps.toDouble()
        if (step < MIN_BEAT_STEP_US || step > 2_000_000.0) return timesUs
        val guard = (durationUs / step).toInt() + 4
        val head = ArrayList<Long>()
        var k = 1
        while (k < guard) {
            val t = inside.first() - k * step
            if (t < -20_000.0) break
            head.add(t.roundToLong().coerceAtLeast(0L))
            if (t < 0.0) break
            k++
        }
        head.reverse()
        val tail = ArrayList<Long>()
        k = 1
        while (k < guard) {
            val t = inside.last() + k * step
            if (t > durationUs) break
            tail.add(t.roundToLong())
            k++
        }
        val merged = ArrayList<Long>(head.size + inside.size + tail.size)
        merged.addAll(head)
        merged.addAll(inside)
        merged.addAll(tail)
        val out = ArrayList<Long>(merged.size)
        for (t in merged) {
            if (out.isEmpty() || t - out.last() > 2_000L) out.add(t)
        }
        return out.toLongArray()
    }

    /** Короче 5 мс — не четверть; 10 с как число > 10000 — уже µs, не секунды. */
    const val MIN_BEAT_STEP_US = 5_000L
    const val ALREADY_US_SEC = 10_000.0
}
