package com.rehearsal.app.player

/**
 * Размер AudioTrack. DefaultAudioSink при `enableAudioTrackPlaybackParams`
 * иначе считает буфер как для скорости 8× (~0.75–2 с) — mute тогда слышен с лагом.
 *
 * Цель ~80 мс, но **не ниже** `AudioTrack.getMinBufferSize`: на эмуляторе
 * (BlueStacks) 14112 байт часто меньше минимума HAL → AudioTrack не стартует,
 * playhead идёт, колонки молчат. Если min неизвестен — 250 мс, всё ещё не секунды.
 */
object PlaybackBuffers {
    const val TARGET_AUDIO_TRACK_US = 80_000L
    const val FALLBACK_AUDIO_TRACK_US = 250_000L
    const val MIN_FRAMES = 512

    fun audioTrackBytes(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Int =
        bytesForUs(TARGET_AUDIO_TRACK_US, sampleRate, channelCount, bytesPerSample)

    fun fallbackAudioTrackBytes(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Int =
        bytesForUs(FALLBACK_AUDIO_TRACK_US, sampleRate, channelCount, bytesPerSample)

    fun audioTrackBytesForPcm16Stereo(sampleRate: Int): Int =
        audioTrackBytes(sampleRate, channelCount = 2, bytesPerSample = 2)

    /**
     * @param deviceMin `AudioTrack.getMinBufferSize`, или ≤0 если ERROR / нет HAL
     */
    fun chooseTrackBytes(requested: Int, deviceMin: Int, fallback: Int): Int {
        val want = requested.coerceAtLeast(1)
        val safeFallback = fallback.coerceAtLeast(want)
        return if (deviceMin > 0) maxOf(want, deviceMin) else safeFallback
    }

    private fun bytesForUs(
        durationUs: Long,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
    ): Int {
        val sr = sampleRate.coerceAtLeast(1)
        val ch = channelCount.coerceAtLeast(1)
        val bps = bytesPerSample.coerceAtLeast(1)
        val frameSize = ch * bps
        val frames = ((sr * durationUs) / 1_000_000L).toInt().coerceAtLeast(MIN_FRAMES)
        return frames * frameSize
    }
}
