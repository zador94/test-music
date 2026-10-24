package com.rehearsal.app.audio

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Клики метронома поверх PCM. Стемы сюда не подменяются — их отдаёт [StemMixMediaSource].
 * Toggle enabled только меняет флаг в [MetronomeClickEngine]: плеер не stop/prepare,
 * буфер декодера в sink не отдаём (см. [MetronomePcmPipe]).
 */
class MetronomeAudioSink(
    private val delegate: AudioSink,
    clickEngine: MetronomeClickEngine,
) : AudioSink by delegate {

    private val pipe = MetronomePcmPipe(clickEngine)

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        pipe.configure(inputFormat)
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
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
