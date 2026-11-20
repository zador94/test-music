package com.rehearsal.app.audio

import java.io.File
import kotlin.math.min

data class StereoPcm(
    val sampleRate: Int,
    val left: FloatArray,
    val right: FloatArray,
) {
    val samples: Int get() = min(left.size, right.size)
}

object AudioBuffers {
    /**
     * Декодирует исходник в IEEE float32 WAV 44.1 stereo **на диск**.
     * В RAM только буфер декодера / блок ресемпла, не вся песня.
     */
    fun decodeToWav44100(src: File, dest: File) {
        val nativeTmp = File(dest.parentFile, dest.name + ".in.tmp")
        var writer: WavWriter? = null
        var nativeRate = HtDemucs.SAMPLE_RATE
        var channels = 2
        try {
            dest.delete()
            PcmDecoder().decode(
                file = src,
                onFormat = { fmt ->
                    nativeRate = fmt.sampleRate
                    channels = fmt.channelCount
                    val target = if (fmt.sampleRate == HtDemucs.SAMPLE_RATE) dest else nativeTmp
                    writer = WavWriter(target, fmt.sampleRate, channels = 2)
                },
                onPcm = { pcm ->
                    val w = writer ?: return@decode
                    val (left, right) = interleavedShortsToPlanar(pcm, channels)
                    w.writePlanarFloatStereo(left, right)
                },
            )
            writer?.close()
            writer = null
            if (nativeRate != HtDemucs.SAMPLE_RATE) {
                resampleWavTo44100(nativeTmp, dest)
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        } finally {
            writer?.close()
            nativeTmp.delete()
        }
    }

    fun interleavedShortsToPlanar(pcm: ShortArray, channelCount: Int): Pair<FloatArray, FloatArray> {
        val ch = channelCount.coerceAtLeast(1)
        val frames = if (ch == 0) 0 else pcm.size / ch
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        if (ch == 1) {
            for (i in 0 until frames) {
                val s = pcm[i] / 32768f
                left[i] = s
                right[i] = s
            }
        } else {
            for (i in 0 until frames) {
                val base = i * ch
                left[i] = pcm[base] / 32768f
                right[i] = pcm[base + 1] / 32768f
            }
        }
        return left to right
    }

    fun resampleWavTo44100(src: File, dest: File) {
        val mapped = WavReader.openMapped(src)
        try {
            val inRate = mapped.info.sampleRate.coerceAtLeast(1)
            if (inRate == HtDemucs.SAMPLE_RATE) {
                src.copyTo(dest, overwrite = true)
                return
            }
            val nIn = mapped.info.frameCount
            val nOut = ((nIn.toLong() * HtDemucs.SAMPLE_RATE) / inRate).toInt().coerceAtLeast(1)
            val ratio = inRate.toDouble() / HtDemucs.SAMPLE_RATE
            val last = (nIn - 1).coerceAtLeast(0)
            val block = 8192
            val left = FloatArray(block)
            val right = FloatArray(block)
            WavWriter(dest, HtDemucs.SAMPLE_RATE, channels = 2).use { writer ->
                var outPos = 0
                while (outPos < nOut) {
                    val count = min(block, nOut - outPos)
                    for (i in 0 until count) {
                        val srcPos = (outPos + i) * ratio
                        val i1 = srcPos.toInt()
                        val frac = (srcPos - i1).toFloat()
                        left[i] = hermiteFrame(mapped, 0, i1, frac, last)
                        right[i] = hermiteFrame(mapped, 1, i1, frac, last)
                    }
                    writer.writePlanarFloatStereo(left.copyOf(count), right.copyOf(count))
                    outPos += count
                }
            }
        } finally {
            mapped.close()
        }
    }

    private fun hermiteFrame(mapped: MappedWav, channel: Int, i1: Int, frac: Float, last: Int): Float {
        val y0 = mapped.frameSample((i1 - 1).coerceIn(0, last), channel)
        val y1 = mapped.frameSample(i1.coerceIn(0, last), channel)
        val y2 = mapped.frameSample((i1 + 1).coerceIn(0, last), channel)
        val y3 = mapped.frameSample((i1 + 2).coerceIn(0, last), channel)
        return hermite(y0, y1, y2, y3, frac)
    }

    fun decodeToStereo44100(file: File): StereoPcm {
        val chunks = ArrayList<ShortArray>()
        var totalShorts = 0
        var format: PcmFormat? = null
        PcmDecoder().decode(
            file = file,
            onFormat = { format = it },
            onPcm = { pcm ->
                chunks.add(pcm)
                totalShorts += pcm.size
            },
        )
        val fmt = format ?: error("Декодер не вернул формат")
        val interleaved = ShortArray(totalShorts)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(interleaved, offset)
            offset += chunk.size
        }
        val channels = fmt.channelCount.coerceAtLeast(1)
        val frames = interleaved.size / channels
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        if (channels == 1) {
            for (i in 0 until frames) {
                val s = interleaved[i] / 32768f
                left[i] = s
                right[i] = s
            }
        } else {
            for (i in 0 until frames) {
                val base = i * channels
                left[i] = interleaved[base] / 32768f
                right[i] = interleaved[base + 1] / 32768f
            }
        }
        if (fmt.sampleRate == HtDemucs.SAMPLE_RATE) {
            return StereoPcm(HtDemucs.SAMPLE_RATE, left, right)
        }
        return StereoPcm(
            sampleRate = HtDemucs.SAMPLE_RATE,
            left = resampleCubic(left, fmt.sampleRate, HtDemucs.SAMPLE_RATE),
            right = resampleCubic(right, fmt.sampleRate, HtDemucs.SAMPLE_RATE),
        )
    }

    fun resampleCubic(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input
        val outLen = ((input.size.toLong() * outRate) / inRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = inRate.toDouble() / outRate
        val last = input.lastIndex
        for (i in 0 until outLen) {
            val src = i * ratio
            val i1 = src.toInt()
            val frac = (src - i1).toFloat()
            val y0 = input[(i1 - 1).coerceIn(0, last)]
            val y1 = input[i1.coerceIn(0, last)]
            val y2 = input[(i1 + 1).coerceIn(0, last)]
            val y3 = input[(i1 + 2).coerceIn(0, last)]
            out[i] = hermite(y0, y1, y2, y3, frac)
        }
        return out
    }

    private fun hermite(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
        val mu2 = mu * mu
        val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val a1 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val a2 = -0.5f * y0 + 0.5f * y2
        return a0 * mu * mu2 + a1 * mu2 + a2 * mu + y1
    }
}
