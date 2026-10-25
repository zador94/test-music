package com.rehearsal.app.audio

/**
 * Часы клика без ExoPlayer: songTime = origin + elapsed * speed (как веб).
 * Снимок [Origin] пишется с потока плеера; [snapshot] читается с `metronome-click`.
 * Читать `player.currentPosition` с worker — IllegalStateException и краш процесса.
 */
object ClickClock {
    const val MAX_OUTPUT_LATENCY_MS = 400

    data class Origin(
        val mediaUs: Long = 0L,
        val wallNs: Long = 0L,
        val speed: Float = 1f,
        val playing: Boolean = false,
    )

    fun snapshot(origin: Origin, nowNs: Long): ClickTrack.Snapshot {
        val speed = if (origin.speed.isFinite()) origin.speed.coerceIn(0.5f, 2f) else 1f
        val base = origin.mediaUs.coerceAtLeast(0L)
        if (!origin.playing) {
            return ClickTrack.Snapshot(base, speed, playing = false)
        }
        val elapsedNs = (nowNs - origin.wallNs).coerceAtLeast(0L)
        val elapsedUs = elapsedNs / 1_000L
        val mediaUs = base + (elapsedUs * speed.toDouble()).toLong()
        return ClickTrack.Snapshot(mediaUs.coerceAtLeast(0L), speed, playing = true)
    }

    /**
     * Курсор клика: то, что сейчас слышно ([heardMediaUs]), плюс задержка
     * выходного AudioTrack. Иначе клик пишется «на сейчас», а колонки играют его
     * на десятки–сотни мс позже доли.
     */
    fun outputLatencyMs(bufferSizeInFrames: Int, sampleRate: Int): Int {
        val sr = sampleRate.coerceAtLeast(1)
        val frames = bufferSizeInFrames.coerceAtLeast(0)
        val ms = (frames * 1000L) / sr
        return ms.toInt().coerceIn(0, MAX_OUTPUT_LATENCY_MS)
    }

    fun queuedLatencyMs(framesWritten: Long, playbackHeadPosition: Int, sampleRate: Int): Int {
        val sr = sampleRate.coerceAtLeast(1)
        val head = playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queued = (framesWritten - head).coerceAtLeast(0L)
        val ms = (queued * 1000L) / sr
        return ms.toInt().coerceIn(0, MAX_OUTPUT_LATENCY_MS)
    }

    /**
     * Фаза выходного AudioTrack, не темп. Крышка = реальный буфер:
     * залипший [AudioTrack.getPlaybackHeadPosition] не должен разгонять media time.
     */
    fun writeLatencyMs(queuedLatencyMs: Int, bufferLatencyMs: Int): Int {
        val queued = queuedLatencyMs.coerceIn(0, MAX_OUTPUT_LATENCY_MS)
        val buffer = bufferLatencyMs.coerceIn(0, MAX_OUTPUT_LATENCY_MS)
        return if (buffer > 0) minOf(queued, buffer) else queued
    }

    /**
     * Следующий chunk = предыдущий + [mediaAdvanceUs].
     * [targetUs] (playhead + очередь) — только resync на seek / большой срыв.
     * Растущая очередь не должна укорачивать период клика.
     */
    fun nextWriteMediaUs(previousUs: Long, targetUs: Long, mediaAdvanceUs: Long): Long {
        val target = targetUs.coerceAtLeast(0L)
        if (previousUs == Long.MIN_VALUE) return target
        val step = mediaAdvanceUs.coerceAtLeast(1L)
        val stepped = previousUs + step
        if (stepped < 0L) return target
        val drift = kotlin.math.abs(stepped - target)
        val slop = maxOf(step * 4, MAX_OUTPUT_LATENCY_MS * 1_000L)
        return if (drift > slop) target else stepped
    }

    fun scheduledMediaUs(heardMediaUs: Long, speed: Float, outputLatencyMs: Int): Long {
        val rate = if (speed.isFinite()) speed.coerceIn(0.5f, 2f) else 1f
        val latencyUs = outputLatencyMs.coerceIn(0, MAX_OUTPUT_LATENCY_MS).toLong() * 1_000L
        return heardMediaUs.coerceAtLeast(0L) + (latencyUs * rate.toDouble()).toLong()
    }
}
