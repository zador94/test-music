package com.rehearsal.app.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

class RehearsalRenderersFactory(
    context: Context,
    private val clickEngine: MetronomeClickEngine,
    private val mixBank: StemMixBank,
) : DefaultRenderersFactory(context) {

    init {
        setEnableAudioFloatOutput(false)
        // Скорость на AudioTrack, не Sonic drain: слайдер вниз больше не глушит sink.
        setEnableAudioTrackPlaybackParams(true)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = super.buildAudioSink(
            context,
            /* enableFloatOutput = */ false,
            /* enableAudioTrackPlaybackParams = */ true,
        ) ?: error("AudioSink недоступен")
        return MetronomeAudioSink(sink, clickEngine, mixBank)
    }
}
