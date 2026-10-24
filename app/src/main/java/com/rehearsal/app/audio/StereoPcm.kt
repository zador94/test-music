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
