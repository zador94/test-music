package com.rehearsal.app.audio

import androidx.media3.common.C
import com.rehearsal.app.data.StemId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Сводит 4 stem WAV в один PCM-буфер по presentationTimeUs.
 * Выход — native-endian 16-bit или float, как просит [encoding].
 */
class StemMixBank {
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var sampleRate: Int = 44100
        private set

    @Volatile
    private var loaded: Array<MappedStem?> = emptyArray()

    private val gainBits = AtomicIntegerArray(StemId.entries.size).apply {
        for (i in 0 until StemId.entries.size) set(i, java.lang.Float.floatToIntBits(1f))
    }

    fun load(files: Map<StemId, File>) {
        clear()
        val stems = arrayOfNulls<MappedStem>(StemId.entries.size)
        var rate = 44100
        try {
            StemId.entries.forEach { id ->
                val file = files[id] ?: error("Нет файла для ${id.fileName}")
                val mapped = WavReader.openMapped(file)
                require(mapped.info.isPcm16 || mapped.info.isFloat32) {
                    "Стем ${id.fileName} должен быть PCM16 или float32 WAV"
                }
                rate = mapped.info.sampleRate
                stems[id.ordinal] = MappedStem(mapped)
            }
        } catch (t: Throwable) {
            stems.forEach { it?.close() }
            throw t
        }
        sampleRate = rate
        loaded = stems
        active = true
    }

    fun clear() {
        active = false
        val previous = loaded
        loaded = emptyArray()
        previous.forEach { it?.close() }
    }

    fun durationUs(): Long {
        val stems = loaded
        if (!active || stems.isEmpty()) return 0L
        val frames = stems.maxOf { it?.frameCount ?: 0 }
        val rate = sampleRate.coerceAtLeast(1)
        return frames * 1_000_000L / rate
    }

    fun setGain(id: StemId, gain: Float) {
        gainBits.set(id.ordinal, java.lang.Float.floatToIntBits(gain.coerceIn(0f, 1f)))
    }

    fun gain(id: StemId): Float = java.lang.Float.intBitsToFloat(gainBits.get(id.ordinal))

    fun render(
        out: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        presentationTimeUs: Long,
    ) {
        out.order(ByteOrder.nativeOrder())
        val stems = loaded
        val floatOut = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (floatOut) 4 else 2
        val ch = channelCount.coerceAtLeast(1)
        val frameSize = bytesPerSample * ch
        if (frameSize <= 0 || out.remaining() < frameSize) {
            zero(out)
            return
        }
        if (!active || stems.size != StemId.entries.size) {
            zero(out)
            return
        }
        val rate = sampleRate.coerceAtLeast(1)
        val pts = presentationTimeUs.coerceAtLeast(0L)
        // Inverse of StemMixMediaSource / BeatClock.sampleToMediaUs. Floor
        // `(pts * rate) / 1e6` is 1 sample early on some chunks (e.g. 3072 →
        // pts 69659 → 3071), so live-remix music and the click (round-trip)
        // sat 1 sample apart. Not 170 ms — but the same mapping the click uses.
        val startFrame = BeatClock.mediaUsToSample(pts, rate).toInt()
        val frames = out.remaining() / frameSize
        val g0 = gainAt(0)
        val g1 = gainAt(1)
        val g2 = gainAt(2)
        val g3 = gainAt(3)
        val s0 = stems[0]
        val s1 = stems[1]
        val s2 = stems[2]
        val s3 = stems[3]
        val pos = out.position()
        for (i in 0 until frames) {
            val frame = startFrame + i
            var left = 0f
            var right = 0f
            if (g0 > 0f && s0 != null) {
                left += s0.left(frame) * g0
                right += s0.right(frame) * g0
            }
            if (g1 > 0f && s1 != null) {
                left += s1.left(frame) * g1
                right += s1.right(frame) * g1
            }
            if (g2 > 0f && s2 != null) {
                left += s2.left(frame) * g2
                right += s2.right(frame) * g2
            }
            if (g3 > 0f && s3 != null) {
                left += s3.left(frame) * g3
                right += s3.right(frame) * g3
            }
            left = left.coerceIn(-1f, 1f)
            right = right.coerceIn(-1f, 1f)
            val base = pos + i * frameSize
            if (floatOut) {
                out.putFloat(base, left)
                if (ch > 1) out.putFloat(base + 4, right)
                for (c in 2 until ch) out.putFloat(base + c * 4, 0f)
            } else {
                out.putShort(base, toPcm16(left))
                if (ch > 1) out.putShort(base + 2, toPcm16(right))
                for (c in 2 until ch) out.putShort(base + c * 2, 0)
            }
        }
    }

    private fun gainAt(index: Int): Float = java.lang.Float.intBitsToFloat(gainBits.get(index))

    private fun zero(out: ByteBuffer) {
        val pos = out.position()
        val lim = out.limit()
        for (i in pos until lim) out.put(i, 0)
    }

    private fun toPcm16(sample: Float): Short =
        (sample * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

private class MappedStem(private val wav: MappedWav) {
    private val info = wav.info
    private val data = wav.data
    private val stride = info.channels * info.bytesPerSample
    val frameCount: Int = info.frameCount

    fun left(frame: Int): Float = sample(frame, 0)

    fun right(frame: Int): Float = sample(frame, if (info.channels > 1) 1 else 0)

    fun close() = wav.close()

    private fun sample(frame: Int, channel: Int): Float {
        if (frame < 0 || frame >= frameCount) return 0f
        val index = frame * stride + channel * info.bytesPerSample
        if (index + info.bytesPerSample > data.capacity()) return 0f
        return if (info.isFloat32) {
            data.getFloat(index)
        } else {
            data.getShort(index) / 32768f
        }
    }
}
