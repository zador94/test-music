package com.rehearsal.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MixerMathTest {
    @Test
    fun muteSetsOnlyThatStemToZero() {
        val vocals = MixerMath.Fader(volume = 0.8f, muted = true)
        val drums = MixerMath.Fader(volume = 0.6f, muted = false)
        val bass = MixerMath.Fader(volume = 1f, muted = false)
        val other = MixerMath.Fader(volume = 0.4f, muted = false)
        val faders = listOf(vocals, drums, bass, other)
        val anySolo = MixerMath.anySolo(faders)
        assertEquals(0f, MixerMath.gain(vocals, anySolo), 1e-6f)
        assertEquals(0.6f, MixerMath.gain(drums, anySolo), 1e-6f)
        assertEquals(1f, MixerMath.gain(bass, anySolo), 1e-6f)
        assertEquals(0.4f, MixerMath.gain(other, anySolo), 1e-6f)
    }

    @Test
    fun unmutedVolumeIsTheFader() {
        val fader = MixerMath.Fader(volume = 0.33f, muted = false)
        assertEquals(0.33f, MixerMath.gain(fader, anySolo = false), 1e-6f)
    }

    @Test
    fun soloMutesNonSoloStems() {
        val vocals = MixerMath.Fader(volume = 1f, solo = true)
        val drums = MixerMath.Fader(volume = 1f, solo = false)
        val anySolo = MixerMath.anySolo(listOf(vocals, drums))
        assertEquals(1f, MixerMath.gain(vocals, anySolo), 1e-6f)
        assertEquals(0f, MixerMath.gain(drums, anySolo), 1e-6f)
    }
}
