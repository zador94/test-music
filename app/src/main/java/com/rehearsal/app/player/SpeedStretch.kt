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

    /** ±кадры вокруг линейной позиции. 320 ≈ полпериода 70 Гц при 44.1 кГц, клик не фламит. */
    private const val PHASE_DELTA = 320

    /** Насколько далеко от линейной позиции можно уйти ради чуть лучшей корреляции. */
    private const val CENTER_BIAS = 0.2

    /** Хвост, по которому ищем фазу. Длиннее — лучше бас, дороже CPU. */
    private const val CORR_SAMPLES = 512

    /** Стык чанка притянуть к исходным семплам, чтобы не щёлкало. ~1.5 мс при 44.1 кГц. */
    private const val EDGE_FRAMES = 64

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
        val stretched = wsola(floats, inFrames, outFrames, channels)
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
        val stretched = wsola(input, inFrames, outFrames, channels)
        for (i in stretched.indices) {
            stretched[i] = stretched[i].coerceIn(-1f, 1f)
        }
        return writeFloat(dest, stretched, source.order())
    }

    /**
     * WSOLA: зерно копируется 1:1 (тон не едет), следующее ищется вокруг линейной
     * позиции `outFrames/inFrames` — той же, по которой клик садится после stretch.
     * Сдвиг зерна не больше [PHASE_DELTA]: сетка клика не уезжает, а нахлёст
     * не складывает волну саму с собой в противофазе (гребенка, «вата»).
     * Вес окна делится обратно: край буфера не уходит в ноль и не щёлкает.
     */
    private fun wsola(
        input: FloatArray,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
    ): FloatArray {
        val acc = FloatArray(outFrames * channels)
        val weight = FloatArray(outFrames)
        val win = grainWindow(inFrames)
        // 50% overlap: два окна Ханна в сумме дают 1, фаза ищется по половине зерна.
        val hopOut = (win / 2).coerceAtLeast(1)
        val window = hann(win)
        val ch = channels.coerceAtLeast(1)
        // Дробный шаг: целый hopIn округлял коэффициент и расходился с кликом.
        val hopIn = hopOut.toDouble() * inFrames.toDouble() / outFrames.coerceAtLeast(1).toDouble()
        var natural = 0.0
        var prevStart = 0
        var outPos = 0
        var grains = 0
        while (outPos < outFrames && grains <= outFrames) {
            val need = minOf(hopOut, outFrames - outPos).coerceAtLeast(1)
            val maxStart = (inFrames - need).coerceAtLeast(0)
            val desired = natural.roundToInt().coerceIn(0, maxStart)
            val start = if (grains == 0) {
                0
            } else {
                bestStart(input, inFrames, ch, prevStart, desired, hopOut, win, maxStart)
            }
            addGrain(input, acc, weight, start, outPos, win, inFrames, outFrames, ch, window)
            prevStart = start
            outPos += hopOut
            natural += hopIn
            grains++
        }
        normalizeAndLockEdges(acc, weight, input, inFrames, outFrames, ch)
        return acc
    }

    /**
     * Максимум корреляции хвоста предыдущего зерна с началом кандидата.
     * Центр поиска — линейная позиция, не «куда уехало прошлое зерно».
     */
    private fun bestStart(
        input: FloatArray,
        inFrames: Int,
        channels: Int,
        prevStart: Int,
        desired: Int,
        hopOut: Int,
        win: Int,
        maxStart: Int,
    ): Int {
        val center = desired.coerceIn(0, maxStart)
        val delta = minOf(PHASE_DELTA, win / 2).coerceAtLeast(1)
        val from = (center - delta).coerceAtLeast(0)
        val to = (center + delta).coerceAtMost(maxStart)
        if (from >= to) return center
        val compare = minOf(CORR_SAMPLES, win - hopOut, inFrames).coerceAtLeast(1)
        var best = center
        var bestScore = alignmentScore(
            input, inFrames, channels, prevStart, center, hopOut, compare, center, delta,
        )
        // Шаг 4, потом доводка: полный перебор ±320 на каждом зерне не нужен телефону.
        val step = if (to - from > 48) 4 else 1
        var candidate = from
        while (candidate <= to) {
            val score = alignmentScore(
                input, inFrames, channels, prevStart, candidate, hopOut, compare, center, delta,
            )
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += step
        }
        if (step > 1) {
            val refineFrom = (best - step).coerceAtLeast(from)
            val refineTo = (best + step).coerceAtMost(to)
            for (refine in refineFrom..refineTo) {
                val score = alignmentScore(
                    input, inFrames, channels, prevStart, refine, hopOut, compare, center, delta,
                )
                if (score > bestScore) {
                    bestScore = score
                    best = refine
                }
            }
        }
        return best
    }

    /**
     * Нормированная корреляция минус штраф за уход от линейной позиции.
     * Без штрафа идеальная копия хвоста (нулевой сдвиг по времени) всегда
     * побеждает и зерно застревает: темп не замедляется, а конец буфера зацикливается.
     */
    private fun alignmentScore(
        input: FloatArray,
        inFrames: Int,
        channels: Int,
        prevStart: Int,
        candidate: Int,
        hopOut: Int,
        compare: Int,
        center: Int,
        delta: Int,
    ): Double {
        val raw = correlate(input, inFrames, channels, prevStart, candidate, hopOut, compare)
        if (raw == Double.NEGATIVE_INFINITY) return raw
        val distance = (candidate - center).toDouble()
        val span = delta.toDouble().coerceAtLeast(1.0)
        val penalty = (distance * distance) / (span * span) * CENTER_BIAS
        return raw - penalty
    }

    private fun correlate(
        input: FloatArray,
        inFrames: Int,
        channels: Int,
        prevStart: Int,
        candidate: Int,
        hopOut: Int,
        compare: Int,
    ): Double {
        var dot = 0.0
        var energyA = 0.0
        var energyB = 0.0
        var n = 0
        for (i in 0 until compare) {
            val aFrame = prevStart + hopOut + i
            val bFrame = candidate + i
            if (aFrame >= inFrames || bFrame >= inFrames) break
            val aBase = aFrame * channels
            val bBase = bFrame * channels
            for (ch in 0 until channels) {
                val a = input[aBase + ch].toDouble()
                val b = input[bBase + ch].toDouble()
                dot += a * b
                energyA += a * a
                energyB += b * b
            }
            n++
        }
        if (n < 8 || energyA < 1e-6 || energyB < 1e-6) return Double.NEGATIVE_INFINITY
        return dot / sqrt(energyA * energyB)
    }

    private fun addGrain(
        input: FloatArray,
        acc: FloatArray,
        weight: FloatArray,
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
            weight[outStart + i] += w
            val inBase = (inStart + i) * channels
            val outBase = (outStart + i) * channels
            for (ch in 0 until channels) {
                acc[outBase + ch] += input[inBase + ch] * w
            }
        }
    }

    /**
     * Делим на сумму окон, иначе край Ханна (он равен 0) вырезает кусок тишины
     * на каждом буфере. Первые и последние [EDGE_FRAMES] притягиваем к линейной
     * карте входа: стык чанков — соседние семплы исходника, не скачок в ноль.
     */
    private fun normalizeAndLockEdges(
        acc: FloatArray,
        weight: FloatArray,
        input: FloatArray,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
    ) {
        for (frame in 0 until outFrames) {
            val w = weight[frame]
            val base = frame * channels
            if (w < 1e-4f) {
                val pos = frame.toDouble() * inFrames.toDouble() / outFrames.toDouble()
                for (ch in 0 until channels) {
                    acc[base + ch] = sampleAt(input, inFrames, channels, ch, pos)
                }
            } else {
                val inv = 1f / w
                for (ch in 0 until channels) {
                    acc[base + ch] *= inv
                }
            }
        }
        val edge = minOf(EDGE_FRAMES, outFrames / 8).coerceAtLeast(0)
        if (edge <= 1) return
        lockEdges(acc, input, inFrames, outFrames, channels, lockStart = true, lockEnd = true)
    }

    private fun lockEdges(
        acc: FloatArray,
        input: FloatArray,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
        lockStart: Boolean,
        lockEnd: Boolean,
    ) {
        val edge = minOf(EDGE_FRAMES, outFrames / 8).coerceAtLeast(0)
        if (edge <= 1) return
        for (i in 0 until edge) {
            val towardStretch = i / (edge - 1).toFloat()
            if (lockStart) {
                blendTowardLinear(acc, input, inFrames, outFrames, channels, i, towardStretch)
            }
            if (lockEnd) {
                blendTowardLinear(
                    acc, input, inFrames, outFrames, channels, outFrames - 1 - i, towardStretch,
                )
            }
        }
    }

    private fun blendTowardLinear(
        acc: FloatArray,
        input: FloatArray,
        inFrames: Int,
        outFrames: Int,
        channels: Int,
        frame: Int,
        towardStretch: Float,
    ) {
        val pos = frame.toDouble() * inFrames.toDouble() / outFrames.toDouble()
        val base = frame * channels
        val keep = towardStretch.coerceIn(0f, 1f)
        for (ch in 0 until channels) {
            val linear = sampleAt(input, inFrames, channels, ch, pos)
            acc[base + ch] = linear + (acc[base + ch] - linear) * keep
        }
    }

    private fun sampleAt(
        input: FloatArray,
        inFrames: Int,
        channels: Int,
        channel: Int,
        pos: Double,
    ): Float {
        if (inFrames <= 1) return input[channel]
        val x = pos.coerceIn(0.0, (inFrames - 1).toDouble())
        val i = x.toInt().coerceIn(0, inFrames - 1)
        val frac = (x - i).toFloat()
        val a = input[i * channels + channel]
        if (frac <= 0f || i >= inFrames - 1) return a
        val b = input[(i + 1) * channels + channel]
        return a + (b - a) * frac
    }

    private fun grainWindow(inFrames: Int): Int {
        val raw = when {
            inFrames >= 8192 -> 2048
            inFrames >= 2048 -> 1024
            inFrames >= 1024 -> 512
            inFrames >= 256 -> 256
            else -> (inFrames / 2).coerceAtLeast(16)
        }
        val even = raw - raw % 2
        val half = (inFrames / 2) * 2
        return even.coerceAtMost(half.coerceAtLeast(2)).coerceAtLeast(2)
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
