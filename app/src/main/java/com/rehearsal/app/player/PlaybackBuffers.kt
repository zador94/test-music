package com.rehearsal.app.player

/**
 * Размер AudioTrack. DefaultAudioSink при `enableAudioTrackPlaybackParams`
 * иначе считает буфер как для скорости 8× (~0.75–2 с) — mute тогда слышен с лагом.
 * Mute/fader должны успевать за десятки мс, не за секунду.
 */
object PlaybackBuffers {
    const val TARGET_AUDIO_TRACK_US = 80_000L
    const val MIN_FRAMES = 512

    fun audioTrackBytes(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Int {
        val sr = sampleRate.coerceAtLeast(1)
        val ch = channelCount.coerceAtLeast(1)
        val bps = bytesPerSample.coerceAtLeast(1)
        val frameSize = ch * bps
        val frames = ((sr * TARGET_AUDIO_TRACK_US) / 1_000_000L).toInt().coerceAtLeast(MIN_FRAMES)
        return frames * frameSize
    }

    fun audioTrackBytesForPcm16Stereo(sampleRate: Int): Int =
        audioTrackBytes(sampleRate, channelCount = 2, bytesPerSample = 2)
}
