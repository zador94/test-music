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
}
