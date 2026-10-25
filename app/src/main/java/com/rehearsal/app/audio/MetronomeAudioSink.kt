package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import com.rehearsal.app.player.PlaybackBuffers
import java.nio.ByteBuffer

/**
 * Клики метронома поверх PCM. На вкладке стемов [StemMixBank] сводится **здесь**
 * (текущие mute/gain), а не из очереди MediaSource.
 * Toggle enabled только меняет флаг в [MetronomeClickEngine]: плеер не stop/prepare.
 */
class MetronomeAudioSink(
    private val delegate: AudioSink,
    clickEngine: MetronomeClickEngine,
    mixBank: StemMixBank,
) : AudioSink by delegate {

    private val pipe = MetronomePcmPipe(clickEngine, mixBank)

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        pipe.configure(inputFormat)
        val sampleRate = inputFormat.sampleRate.takeIf { it > 0 } ?: 44100
        val channels = inputFormat.channelCount.takeIf { it > 0 } ?: 2
        val bytesPerSample = if (inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val live = PlaybackBuffers.audioTrackBytes(sampleRate, channels, bytesPerSample)
        val bufferSize = if (specifiedBufferSize > 0) minOf(specifiedBufferSize, live) else live
        delegate.configure(inputFormat, bufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        return pipe.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) { mixed, pts, count ->
            delegate.handleBuffer(mixed, pts, count)
        }
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        delegate.setSkipSilenceEnabled(false)
    }

    override fun handleDiscontinuity() {
        pipe.flush()
        delegate.handleDiscontinuity()
    }

    override fun flush() {
        pipe.flush()
        delegate.flush()
    }

    override fun reset() {
        pipe.reset()
        delegate.reset()
    }
}
