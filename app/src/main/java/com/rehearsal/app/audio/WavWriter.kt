package com.rehearsal.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Пишет stereo IEEE float32 WAV (format 3). Без лишнего шага в 16-bit.
 * Заголовок с размером дописывается в [close].
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0

    init {
        raf.setLength(0)
        writeHeader(0)
    }

    fun writePlanarFloatStereo(
        left: FloatArray,
        right: FloatArray,
        frames: Int = minOf(left.size, right.size),
    ) {
        require(channels == 2)
        val n = frames.coerceAtMost(minOf(left.size, right.size)).coerceAtLeast(0)
        val frame = 2048
        val bytes = ByteBuffer.allocate(frame * 8).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val count = minOf(frame, n - i)
            bytes.clear()
            for (f in 0 until count) {
                bytes.putFloat(left[i + f])
                bytes.putFloat(right[i + f])
            }
            raf.write(bytes.array(), 0, count * 8)
            dataBytes += count * 8L
            i += count
        }
    }

    override fun close() {
        val size = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        writeHeader(size)
        raf.close()
    }

    private fun writeHeader(dataSize: Int) {
        raf.seek(0)
        val bits = 32
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(3) // WAVE_FORMAT_IEEE_FLOAT
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        raf.write(header.array())
    }
}
