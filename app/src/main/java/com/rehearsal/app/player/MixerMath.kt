package com.rehearsal.app.player

/** Mute = 0, Solo глушит остальные. Без ExoPlayer — те же числа, что у фейдеров. */
object MixerMath {
    data class Fader(
        val volume: Float = 1f,
        val muted: Boolean = false,
        val solo: Boolean = false,
    )

    fun anySolo(faders: Collection<Fader>): Boolean = faders.any { it.solo }

    fun gain(fader: Fader, anySolo: Boolean): Float {
        val audible = if (anySolo) fader.solo else !fader.muted
        return if (audible) fader.volume.coerceIn(0f, 1f) else 0f
    }
}
