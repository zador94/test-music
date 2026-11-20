package com.rehearsal.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBuffersTest {
    @Test
    fun audioTrackBufferIsTensOfMsNotSeconds() {
        val bytes = PlaybackBuffers.audioTrackBytesForPcm16Stereo(44100)
        val bytesPerSecond = 44100 * 4
        val seconds = bytes.toDouble() / bytesPerSecond
        assertEquals(14112, bytes)
        assertTrue("mute lag was 1–2s because AudioTrack params sized ~8×; want ~80ms, was $seconds s", seconds < 0.12)
        assertTrue(seconds > 0.03)
        val quarterSecond = bytesPerSecond / 4
        assertTrue(bytes < quarterSecond)
    }

    @Test
    fun chooseTrackBytesNeverBelowDeviceMin() {
        val requested = PlaybackBuffers.audioTrackBytesForPcm16Stereo(44100)
        val fallback = PlaybackBuffers.fallbackAudioTrackBytes(44100, 2, 2)
        assertEquals(32768, PlaybackBuffers.chooseTrackBytes(requested, 32768, fallback))
        assertTrue(PlaybackBuffers.chooseTrackBytes(requested, 32768, fallback) >= 32768)
        assertEquals(fallback, PlaybackBuffers.chooseTrackBytes(requested, 0, fallback))
        assertEquals(fallback, PlaybackBuffers.chooseTrackBytes(requested, -2, fallback))
        assertEquals(requested, PlaybackBuffers.chooseTrackBytes(requested, 4096, fallback))
        val fallbackSeconds = fallback.toDouble() / (44100 * 4)
        assertTrue("fallback must stay under a second, was $fallbackSeconds", fallbackSeconds < 0.3)
        assertTrue(fallbackSeconds > 0.2)
    }
}
