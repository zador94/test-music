package com.rehearsal.app.player

import androidx.media3.common.C
import com.rehearsal.app.audio.ClickMix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Свой WSOLA на **всех** скоростях; AudioTrack всегда 1×.
 * HAL/BlueStacks часто отказывается от speed > 1; speed < 1 на PlaybackParams
 * растягивал бы клик после PCM, и клик нельзя было бы поставить после stretch.
 * Не resample/decimate: период волны тот же, тон не гелий. Не Sonic drain.
 * Speed ~1 — bit-exact passthrough (тот же буфер).
 */
object SpeedStretch {
    const val APPLY_ABOVE = 1.001f
    const val APPLY_BELOW = 0.999f

    fun isUnity(speed: Float): Boolean {
        if (!speed.isFinite()) return true
        return speed in APPLY_BELOW..APPLY_ABOVE
    }

    fun softwareSpeed(requested: Float): Float {
        if (!requested.isFinite()) return 1f
        val clamped = requested.coerceIn(PlaybackMath.MIN_SPEED, PlaybackMath.MAX_SPEED)
        return if (isUnity(clamped)) 1f else clamped
    }

    fun audioTrackSpeed(@Suppress("UNUSED_PARAMETER") requested: Float): Float = 1f

    fun outputFrameCount(inputFrames: Int, speed: Float): Int {
        val stretch = softwareSpeed(speed)
        val frames = inputFrames.coerceAtLeast(0)
        if (isUnity(stretch) || frames <= 1) return frames
        return (frames / stretch.toDouble()).toInt().coerceAtLeast(1)
    }

    fun inputFrameCount(outputFrames: Int, speed: Float): Int {
        val stretch = softwareSpeed(speed)
        val frames = outputFrames.coerceAtLeast(0)
        if (isUnity(stretch) || frames <= 1) return frames
        return (frames * stretch.toDouble()).toInt().coerceAtLeast(1)
    }

    /**
     * Кадр входа (song) → кадр выхода после WSOLA. Identity, если длины совпали.
     * Отрицательный input (клик начался до буфера) масштабируется так же.
     */
    fun outputFrameForInput(inputFrame: Int, inFrames: Int, outFrames: Int): Int {
        if (inFrames <= 0 || outFrames == inFrames) return inputFrame
        return ((inputFrame.toLong() * outFrames) / inFrames).toInt()
    }

    fun timeStretch(
        source: ByteBuffer,
        dest: ByteBuffer,
        speed: Float,
        encoding: Int,
        channelCount: Int,
    ): ByteBuffer {
        val stretch = softwareSpeed(speed)
        if (isUnity(stretch)) return source
        val frameSize = ClickMix.frameSizeBytes(encoding, channelCount)
        val remaining = source.remaining()
        if (frameSize <= 0 || remaining < frameSize) return source
        val inFrames = remaining / frameSize
        val outFrames = outputFrameCount(inFrames, stretch)
        if (outFrames == inFrames || inFrames <= 0) return source
        return if (encoding == C.ENCODING_PCM_FLOAT) {
            stretchFloat(source, dest, inFrames, outFrames, stretch, channelCount.coerceAtLeast(1))
        } else {
            stretchPcm16(source, dest, inFrames, outFrames, stretch, channelCount.coerceAtLeast(1))
        }
    }

    fun flush() {
        // Per-chunk WSOLA; no overlap state across buffers.
    }

    /** @deprecated pitch-up resample; kept as alias so old call sites stretch instead. */
    fun decimate(
        source: ByteBuffer,
        dest: ByteBuffer,
        speed: Float,
        encoding: Int,
        channelCount: Int,
    ): ByteBuffer = timeStretch(source, dest, speed, encoding, channelCount)

    /**
     * Drop-sample resample (helium). Tests compare pitch against [timeStretch].
     */
    fun dropSample(
        source: ByteBuffer,
        dest: ByteBuffer,
        speed: Float,
        encoding: Int,
        channelCount: Int,
    ): ByteBuffer {
        val stretch = softwareSpeed(speed)
        if (isUnity(stretch) || stretch < 1f) return source
        val frameSize = ClickMix.frameSizeBytes(encoding, channelCount)
        val remaining = source.remaining()
        if (frameSize <= 0 || remaining < frameSize * 2) return source
        val inFrames = remaining / frameSize
        val outFrames = outputFrameCount(inFrames, stretch)
        if (outFrames >= inFrames) return source
        val channels = channelCount.coerceAtLeast(1)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val input = readFloat(source, inFrames, channels)
            val output = FloatArray(outFrames * channels)
            for (i in 0 until outFrames) {
                val srcFrame = ((i.toLong() * inFrames) / outFrames).toInt().coerceAtMost(inFrames - 1)
                val from = srcFrame * channels
                val to = i * channels
                for (ch in 0 until channels) {
                    output[to + ch] = input[from + ch]
                }
            }
            return writeFloat(dest, output, source.order())
        }
        val input = readPcm16(source, inFrames, channels)
        val output = ShortArray(outFrames * channels)
        for (i in 0 until outFrames) {
            val srcFrame = ((i.toLong() * inFrames) / outFrames).toInt().coerceAtMost(inFrames - 1)
            val from = srcFrame * channels
            val to = i * channels
            for (ch in 0 until channels) {
                output[to + ch] = input[from + ch]
            }
        }
        return writePcm16(dest, output, source.order())
    }

    private fun stretchPcm16(
        source: ByteBuffer,
        dest: ByteBuffer,
        inFrames: Int,
        outFrames: Int,
        stretch: Float,
        channels: Int,
    ): ByteBuffer {
        val input = readPcm16(source, inFrames, channels)
        val floats = FloatArray(input.size) { input[it].toFloat() }
        val stretched = wsola(floats, inFrames, outFrames, channels, stretch)
        val output = ShortArray(stretched.size) { i ->
            stretched[i].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return writePcm16(dest, output, source.order())
    }

    private fun stretchFloat(
        source: ByteBuffer,
        dest: ByteBuffer,
        inFrames: Int,
        outFrames: Int,
        stretch: Float,
        channels: Int,
    ): ByteBuffer {
        val input = readFloat(source, inFrames, channels)
        val stretched = wsola(input, inFrames, outFrames, channels, stretch)
        for (i in stretched.indices) {
            stretched[i] = stretched[i].coerceIn(-1f, 1f)
        }
        return writeFloat(dest, stretched, source.order())
    }

    /**
     * Waveform-similarity overlap-add: каждое зерно копируется 1:1 (период тот же),
     * следующее ищем по корреляции, а средний шаг по входу = hopOut × speed.
     */
    private fun wsola(
        input: FloatArray,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
        stretch: Float,
    ): FloatArray {
        val acc = FloatArray(outFrames * channels)
        val win = grainWindow(inFrames)
        val hopOut = (win / 2).coerceAtLeast(1)
        val hopIn = (hopOut * stretch.toDouble()).roundToInt().coerceAtLeast(1)
        val search = hopOut.coerceAtLeast(1)
        val window = hann(win)
        var desiredIn = 0
        var outPos = 0
        var prevGrain = 0
        while (outPos < outFrames) {
            val grainStart = if (outPos == 0) {
                0
            } else {
                bestMatch(
                    input,
                    prevGrain,
                    hopOut,
                    desiredIn.coerceIn(0, (inFrames - 1).coerceAtLeast(0)),
                    search,
                    win,
                    inFrames,
                    channels,
                )
            }
            addGrain(input, acc, grainStart, outPos, win, inFrames, outFrames, channels, window)
            prevGrain = grainStart
            outPos += hopOut
            desiredIn += hopIn
        }
        return acc
    }

    private fun bestMatch(
        input: FloatArray,
        prevGrain: Int,
        hopOut: Int,
        desiredIn: Int,
        search: Int,
        win: Int,
        inFrames: Int,
        channels: Int,
    ): Int {
        val lastStart = (inFrames - win).coerceAtLeast(0)
        val lo = (desiredIn - search).coerceAtLeast(0)
        val hi = (desiredIn + search).coerceAtMost(lastStart)
        if (hi < lo) return desiredIn.coerceIn(0, lastStart)
        val overlap = hopOut.coerceAtMost(win)
        val ref = prevGrain + hopOut
        if (ref >= inFrames) return desiredIn.coerceIn(lo, hi)
        var best = desiredIn.coerceIn(lo, hi)
        var bestCorr = Double.NEGATIVE_INFINITY
        for (cand in lo..hi) {
            val n = minOf(overlap, inFrames - ref, inFrames - cand)
            if (n < 8) continue
            var dot = 0.0
            var n1 = 0.0
            var n2 = 0.0
            for (i in 0 until n) {
                val a = input[(ref + i) * channels].toDouble()
                val b = input[(cand + i) * channels].toDouble()
                dot += a * b
                n1 += a * a
                n2 += b * b
            }
            val denom = sqrt(n1 * n2)
            val corr = if (denom < 1.0) 0.0 else dot / denom
            if (corr > bestCorr) {
                bestCorr = corr
                best = cand
            }
        }
        return best
    }

    private fun addGrain(
        input: FloatArray,
        acc: FloatArray,
        inStart: Int,
        outStart: Int,
        win: Int,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
        hann: FloatArray,
    ) {
        val n = minOf(win, inFrames - inStart, outFrames - outStart)
        if (n <= 0) return
        for (i in 0 until n) {
            val w = hann[i]
            val inBase = (inStart + i) * channels
            val outBase = (outStart + i) * channels
            for (ch in 0 until channels) {
                acc[outBase + ch] += input[inBase + ch] * w
            }
        }
    }

    private fun grainWindow(inFrames: Int): Int {
        val raw = when {
            inFrames >= 8192 -> 2048
            inFrames >= 2048 -> 1024
            inFrames >= 512 -> 256
            inFrames >= 128 -> 64
            else -> (inFrames / 2).coerceAtLeast(8)
        }
        return (raw / 2) * 2
    }

    private fun hann(n: Int): FloatArray {
        if (n <= 1) return FloatArray(n.coerceAtLeast(1)) { 1f }
        return FloatArray(n) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        }
    }

    private fun readPcm16(source: ByteBuffer, frames: Int, channels: Int): ShortArray {
        val src = source.asReadOnlyBuffer().order(source.order())
        val samples = ShortArray(frames * channels)
        for (i in samples.indices) {
            samples[i] = src.getShort()
        }
        return samples
    }

    private fun readFloat(source: ByteBuffer, frames: Int, channels: Int): FloatArray {
        val src = source.asReadOnlyBuffer().order(source.order())
        val samples = FloatArray(frames * channels)
        for (i in samples.indices) {
            samples[i] = src.getFloat()
        }
        return samples
    }

    private fun writePcm16(dest: ByteBuffer, samples: ShortArray, order: ByteOrder): ByteBuffer {
        val out = ensureDirect(dest, samples.size * 2, order)
        for (s in samples) {
            out.putShort(s)
        }
        out.flip()
        return out
    }

    private fun writeFloat(dest: ByteBuffer, samples: FloatArray, order: ByteOrder): ByteBuffer {
        val out = ensureDirect(dest, samples.size * 4, order)
        for (s in samples) {
            out.putFloat(s)
        }
        out.flip()
        return out
    }

    private fun ensureDirect(dest: ByteBuffer, bytes: Int, order: ByteOrder): ByteBuffer {
        val out = if (dest.capacity() < bytes) {
            ByteBuffer.allocateDirect(bytes.coerceAtLeast(4096))
        } else {
            dest
        }
        out.order(order)
        out.clear()
        return out
    }
}
