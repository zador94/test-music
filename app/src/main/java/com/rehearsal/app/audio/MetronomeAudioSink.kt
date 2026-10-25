package com.rehearsal.app.audio

import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import com.rehearsal.app.player.PlaybackBuffers
import com.rehearsal.app.player.SpeedStretch
import java.nio.ByteBuffer

/**
 * Stem PCM + mute-at-sink + software speed-up. Клики метронома — [ClickTrack],
 * не этот sink (веб: отдельный audio node, не тихий mix в song buffer).
 * На вкладке стемов [StemMixBank] сводится **здесь** (текущие mute/gain),
 * поверх копии уже смешанного PCM, только если layout/PTS совпадают.
 * Иначе 0.3.6 затирал выход нулями, а AudioTrack с буфером 80 мс не стартовал.
 * Toggle метронома не stop/prepare ExoPlayer.
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
        val requested = PlaybackBuffers.audioTrackBytes(sampleRate, channels, bytesPerSample)
        val fallback = PlaybackBuffers.fallbackAudioTrackBytes(sampleRate, channels, bytesPerSample)
        val target = if (specifiedBufferSize > 0) minOf(specifiedBufferSize, requested) else requested
        val deviceMin = deviceMinBufferBytes(sampleRate, channels, inputFormat.pcmEncoding)
        val bufferSize = PlaybackBuffers.chooseTrackBytes(target, deviceMin, fallback)
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

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val requested = playbackParameters.speed
        pipe.softwareSpeed = SpeedStretch.softwareSpeed(requested)
        val trackSpeed = SpeedStretch.audioTrackSpeed(requested)
        delegate.setPlaybackParameters(
            PlaybackParameters(trackSpeed, playbackParameters.pitch),
        )
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        val delegateParams = delegate.playbackParameters
        val software = pipe.softwareSpeed
        return if (software > SpeedStretch.APPLY_ABOVE) {
            PlaybackParameters(software, delegateParams.pitch)
        } else {
            delegateParams
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

    companion object {
        fun deviceMinBufferBytes(sampleRate: Int, channelCount: Int, pcmEncoding: Int): Int {
            val channelConfig = if (channelCount <= 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val encoding = if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
                AudioFormat.ENCODING_PCM_FLOAT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            return runCatching {
                AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            }.getOrDefault(0)
        }
    }
}
