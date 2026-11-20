package com.rehearsal.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val audioFormat: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val bytesPerSample: Int get() = bitsPerSample / 8
    val frameCount: Int get() = (dataBytes / (channels * bytesPerSample).coerceAtLeast(1)).toInt()
    val isPcm16: Boolean get() = audioFormat == 1 && bitsPerSample == 16
    val isFloat32: Boolean get() = audioFormat == 3 && bitsPerSample == 32
}

class MappedWav(
    val info: WavInfo,
    val data: ByteBuffer,
    private val raf: RandomAccessFile,
) {
    fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }

    fun toStereoPcm(): StereoPcm = readRange(0, info.frameCount)

    fun readRange(startFrame: Int, frameCount: Int): StereoPcm {
        val total = info.frameCount.coerceAtLeast(0)
        val start = startFrame.coerceIn(0, total)
        val n = frameCount.coerceAtMost(total - start).coerceAtLeast(0)
        val left = FloatArray(n)
        val right = FloatArray(n)
        val stride = (info.channels * info.bytesPerSample).coerceAtLeast(1)
        for (i in 0 until n) {
            val base = (start + i) * stride
            left[i] = sampleAt(base)
            right[i] = if (info.channels > 1) sampleAt(base + info.bytesPerSample) else left[i]
        }
        return StereoPcm(info.sampleRate, left, right)
    }

    fun frameSample(frame: Int, channel: Int): Float {
        val stride = (info.channels * info.bytesPerSample).coerceAtLeast(1)
        val ch = channel.coerceIn(0, (info.channels - 1).coerceAtLeast(0))
        return sampleAt(frame * stride + ch * info.bytesPerSample)
    }

    private fun sampleAt(index: Int): Float {
        if (index < 0 || index + info.bytesPerSample > data.capacity()) return 0f
        return if (info.isFloat32) {
            data.getFloat(index)
        } else {
            data.getShort(index) / 32768f
        }
    }
}

/**
 * Читает stem WAV без MediaCodec: 16-bit PCM или IEEE float32, little-endian.
 * Канал файла остаётся открытым, пока жив mmap.
 */
object WavReader {
    fun readStereo(file: File): StereoPcm {
        val mapped = openMapped(file)
        try {
            return mapped.toStereoPcm()
        } finally {
            mapped.close()
        }
    }

    fun readStereoRange(file: File, startFrame: Int, frameCount: Int): StereoPcm {
        val mapped = openMapped(file)
        try {
            return mapped.readRange(startFrame, frameCount)
        } finally {
            mapped.close()
        }
    }

    fun frameCount(file: File): Int {
        val mapped = openMapped(file)
        try {
            return mapped.info.frameCount
        } finally {
            mapped.close()
        }
    }

    fun openMapped(file: File): MappedWav {
        val raf = RandomAccessFile(file, "r")
        try {
            val info = parse(raf, file.name)
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, info.dataOffset, info.dataBytes)
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            return MappedWav(info, mapped, raf)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    private fun parse(raf: RandomAccessFile, name: String): WavInfo {
        val header = ByteArray(12)
        raf.readFully(header)
        require(header.copyOfRange(0, 4).toStringAscii() == "RIFF") { "Не RIFF: $name" }
        require(header.copyOfRange(8, 12).toStringAscii() == "WAVE") { "Не WAVE: $name" }

        var audioFormat = 1
        var channels = 2
        var sampleRate = 44100
        var bits = 16
        var dataOffset = -1L
        var dataBytes = 0L

        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4).also { raf.readFully(it) }.toStringAscii()
            val size = raf.readIntLE().toLong() and 0xFFFF_FFFFL
            val chunkStart = raf.filePointer
            when (id) {
                "fmt " -> {
                    audioFormat = raf.readUShortLE()
                    channels = raf.readUShortLE().coerceAtLeast(1)
                    sampleRate = raf.readIntLE()
                    raf.readIntLE()
                    raf.readUShortLE()
                    bits = raf.readUShortLE()
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataBytes = size
                }
            }
            if (id == "data") break
            raf.seek(chunkStart + size + (size and 1))
        }
        require(dataOffset >= 0) { "В $name нет data-чанка" }
        require(audioFormat == 1 || audioFormat == 3) {
            "Неподдерживаемый WAV format=$audioFormat в $name"
        }
        return WavInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            audioFormat = audioFormat,
            dataOffset = dataOffset,
            dataBytes = dataBytes,
        )
    }
}

private fun ByteArray.toStringAscii() = String(this, Charsets.US_ASCII)

private fun RandomAccessFile.readIntLE(): Int {
    val b = ByteArray(4)
    readFully(b)
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun RandomAccessFile.readUShortLE(): Int {
    val b = ByteArray(2)
    readFully(b)
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
}
