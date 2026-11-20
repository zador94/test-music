package com.rehearsal.app.audio

import kotlin.math.roundToLong

/**
 * Сетка клика в media time: одна фаза и шаг `60 / BPM`.
 * `beats[]` плеер не читает.
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

    /** origin = (фаза + калибровка) mod период. Калибровка может быть отрицательной. */
    fun gridOriginUs(phaseUs: Long, calibrationUs: Long, intervalUs: Long): Long {
        val interval = intervalUs.coerceAtLeast(1L)
        return (phaseUs + calibrationUs).mod(interval)
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
     * Ближайшая доля. Ровно посередине — более поздняя, чтобы петля не съезжала назад.
     */
    fun nearestBeatUs(mediaTimeUs: Long, intervalUs: Long, originUs: Long = 0L): Long {
        val time = mediaTimeUs.coerceAtLeast(0L)
        val atOrAfter = beatAtOrAfter(time, intervalUs, originUs)
        if (intervalUs <= 0L) return atOrAfter
        val prev = atOrAfter - intervalUs
        if (prev < 0L) return atOrAfter
        val toPrev = time - prev
        val toNext = atOrAfter - time
        return if (toPrev < toNext) prev else atOrAfter
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

    /**
     * `analysis.json` хранит `beats[]` в секундах.
     * Если значения уже микросекунды (максимум больше 10 с, как если бы это были µs) — не умножаем снова.
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

    /** 10 с как число больше 10000 — уже µs, не секунды. */
    const val ALREADY_US_SEC = 10_000.0
}
