package com.rehearsal.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long,
)

/**
 * Декодирует файл в PCM 16-bit через MediaExtractor + MediaCodec.
 * Пишет кусками, чтобы длинный WAV не держался целиком в RAM.
 */
class PcmDecoder {
    fun decode(
        file: File,
        onFormat: (PcmFormat) -> Unit,
        onPcm: (ShortArray) -> Unit,
        onProgress: (positionUs: Long, durationUs: Long) -> Unit = { _, _ -> },
    ) {
        val extractor = MediaExtractor()
        val codec: MediaCodec
        var durationUs = 0L
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Неизвестный MIME")
            durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
        } catch (t: Throwable) {
            extractor.release()
            throw t
        }

        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var formatSent = false
        var channelCount = 2
        var sampleRate = 44100
        val timeoutUs = 10_000L

        try {
            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (!formatSent) {
                            onFormat(PcmFormat(sampleRate, channelCount, durationUs))
                            formatSent = true
                        }
                    }
                    else -> if (outIndex >= 0) {
                        if (!formatSent) {
                            val outFormat = codec.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            onFormat(PcmFormat(sampleRate, channelCount, durationUs))
                            formatSent = true
                        }
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pcm = toPcm16(outBuf, codec.outputFormat)
                            if (pcm.isNotEmpty()) onPcm(pcm)
                            onProgress(info.presentationTimeUs, durationUs)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun toPcm16(buffer: ByteBuffer, format: MediaFormat): ShortArray {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            android.media.AudioFormat.ENCODING_PCM_16BIT
        }
        val ordered = buffer.order(ByteOrder.nativeOrder())
        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                ShortArray(floats.remaining()) { index ->
                    (floats.get(floats.position() + index) * Short.MAX_VALUE)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
            android.media.AudioFormat.ENCODING_PCM_8BIT -> {
                ShortArray(ordered.remaining()) { index ->
                    val u = ordered.get(ordered.position() + index).toInt() and 0xFF
                    ((u - 128) shl 8).toShort()
                }
            }
            else -> {
                val shorts = ordered.asShortBuffer()
                ShortArray(shorts.remaining()).also { shorts.get(it) }
            }
        }
    }
}
