package com.rehearsal.app.audio

/**
 * Часы клика без ExoPlayer: songTime = origin + elapsed * speed (как веб).
 * Снимок [Origin] пишется с потока плеера; [snapshot] читается с `metronome-click`.
 * Читать `player.currentPosition` с worker — IllegalStateException и краш процесса.
 */
object ClickClock {
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
}
