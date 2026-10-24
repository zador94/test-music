package com.rehearsal.app.player

import com.rehearsal.app.audio.MetronomeClickEngine

/** Чистая формула скорости: без ExoPlayer, её же крутят тесты. */
object PlaybackMath {
    fun playbackSpeed(metronomeBpm: Float, originalBpm: Float): Float {
        if (originalBpm <= 0f) return 1f
        return (metronomeBpm / originalBpm).coerceIn(0.5f, 2.0f)
    }

    fun minMetronomeBpm(originalBpm: Float): Float =
        (originalBpm * 0.5f).coerceAtLeast(MetronomeClickEngine.MIN_BPM)

    fun maxMetronomeBpm(originalBpm: Float): Float =
        (originalBpm * 2.0f).coerceAtMost(MetronomeClickEngine.MAX_BPM)
}
