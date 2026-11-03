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
        // WSOLA в MetronomePcmPipe на всех скоростях. AudioTrack 1×, без Sonic drain.
        setEnableAudioTrackPlaybackParams(false)
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
            /* enableAudioTrackPlaybackParams = */ false,
        ) ?: error("AudioSink недоступен")
        return MetronomeAudioSink(sink, clickEngine, mixBank)
    }
}
