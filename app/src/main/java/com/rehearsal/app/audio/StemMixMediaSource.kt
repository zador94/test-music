package com.rehearsal.app.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Сырой PCM 16-bit stereo из [StemMixBank]. ExoPlayer не декодирует float-WAV
 * (из-за этого вкладка «Стемы» была тихой при живом транспорте).
 */
class StemMixMediaSource(
    private val mixBank: StemMixBank,
) : BaseMediaSource() {

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        val durationUs = mixBank.durationUs().coerceAtLeast(1L)
        refreshSourceInfo(
            SinglePeriodTimeline(
                durationUs,
                /* isSeekable = */ true,
                /* isDynamic = */ false,
                /* useLiveConfiguration = */ false,
                /* manifest = */ null,
                getMediaItem(),
            ),
        )
    }

    override fun maybeThrowSourceInfoRefreshError() = Unit

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = Period(mixBank)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

    override fun getMediaItem(): MediaItem = MEDIA_ITEM

    override fun releaseSourceInternal() = Unit

    private class Period(private val mixBank: StemMixBank) : MediaPeriod {
        private val format = pcmFormat(mixBank.sampleRate)
        private val tracks = TrackGroupArray(TrackGroup(format))
        private val streams = ArrayList<PcmStream>()
        private val durationUs = mixBank.durationUs().coerceAtLeast(1L)

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = tracks

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streamsOut: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            val clamped = positionUs.coerceIn(0L, durationUs)
            for (i in selections.indices) {
                if (streamsOut[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                    streams.remove(streamsOut[i])
                    streamsOut[i] = null
                }
                if (streamsOut[i] == null && selections[i] != null) {
                    val stream = PcmStream(mixBank, durationUs)
                    stream.seekTo(clamped)
                    streams.add(stream)
                    streamsOut[i] = stream
                    streamResetFlags[i] = true
                }
            }
            return clamped
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long {
            val clamped = positionUs.coerceIn(0L, durationUs)
            streams.forEach { it.seekTo(clamped) }
            return clamped
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            positionUs.coerceIn(0L, durationUs)

        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE

        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

        override fun isLoading(): Boolean = false

        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private class PcmStream(
        private val mixBank: StemMixBank,
        private val durationUs: Long,
    ) : SampleStream {
        private val frameSize = Util.getPcmFrameSize(C.ENCODING_PCM_16BIT, CHANNELS)
        private val durationBytes = byteCount(durationUs, mixBank.sampleRate, frameSize)
        private var sentFormat = false
        private var positionBytes = 0L

        fun seekTo(positionUs: Long) {
            positionBytes = byteCount(positionUs, mixBank.sampleRate, frameSize)
                .coerceIn(0L, durationBytes)
        }

        fun mediaPositionUs(): Long = positionUs(positionBytes, mixBank.sampleRate, frameSize)

        override fun isReady(): Boolean = true

        override fun maybeThrowError() = Unit

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (!sentFormat || (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0) {
                formatHolder.format = pcmFormat(mixBank.sampleRate)
                sentFormat = true
                return C.RESULT_FORMAT_READ
            }
            val remaining = durationBytes - positionBytes
            if (remaining <= 0L) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            buffer.timeUs = positionUs(positionBytes, mixBank.sampleRate, frameSize)
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            val bytesToWrite = min(CHUNK_BYTES, remaining.toInt())
            if ((readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0) {
                buffer.ensureSpaceForWrite(bytesToWrite)
                val data = requireNotNull(buffer.data)
                data.order(ByteOrder.nativeOrder())
                data.clear()
                data.limit(bytesToWrite)
                mixBank.render(data, C.ENCODING_PCM_16BIT, CHANNELS, buffer.timeUs)
                data.position(bytesToWrite)
            }
            if ((readFlags and SampleStream.FLAG_PEEK) == 0) {
                positionBytes += bytesToWrite
            }
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            val old = positionBytes
            seekTo(positionUs)
            return ((positionBytes - old) / CHUNK_BYTES).toInt()
        }
    }

    companion object {
        private const val CHANNELS = 2
        private const val CHUNK_FRAMES = 1024
        private const val CHUNK_BYTES = CHUNK_FRAMES * 4
        private val MEDIA_ITEM = MediaItem.Builder()
            .setMediaId("StemMixMediaSource")
            .setUri(Uri.EMPTY)
            .setMimeType(MimeTypes.AUDIO_RAW)
            .build()

        private fun pcmFormat(sampleRate: Int): Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(CHANNELS)
            .setSampleRate(sampleRate.coerceAtLeast(1))
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

        private fun byteCount(durationUs: Long, sampleRate: Int, frameSize: Int): Long {
            val samples = BeatClock.mediaUsToSample(durationUs, sampleRate.coerceAtLeast(1))
            return samples * frameSize
        }

        private fun positionUs(bytes: Long, sampleRate: Int, frameSize: Int): Long {
            val samples = bytes / frameSize.coerceAtLeast(1)
            return BeatClock.sampleToMediaUs(samples, sampleRate.coerceAtLeast(1))
        }
    }
}
