package com.rehearsal.app.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.rehearsal.app.data.StemId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Один проход HT-Demucs (не FT-bag, не 6-stem).
 * Вход: stereo 44.1 kHz, сегмент 7.8 с (343980 сэмплов).
 * Выход в порядке модели: drums, bass, other, vocals.
 */
object HtDemucs {
    const val SAMPLE_RATE = 44100
    const val SEGMENT_SAMPLES = 343_980
    private const val OVERLAP = SEGMENT_SAMPLES / 4
    private const val STRIDE = SEGMENT_SAMPLES - OVERLAP

    private val stemOrder = arrayOf(StemId.DRUMS, StemId.BASS, StemId.OTHER, StemId.VOCALS)

    fun separate(
        mix: StereoPcm,
        modelFile: File,
        onChunk: (index: Int, total: Int) -> Unit,
    ): Map<StemId, StereoPcm> {
        require(mix.sampleRate == SAMPLE_RATE)
        val total = mix.samples
        val nChunks = maxOf(1, (total + STRIDE - 1) / STRIDE)
        val window = makeWindow(SEGMENT_SAMPLES, OVERLAP)
        val outL = Array(4) { FloatArray(total) }
        val outR = Array(4) { FloatArray(total) }
        val weight = FloatArray(total)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            setIntraOpNumThreads(2)
            setMemoryPatternOptimization(false)
        }
        env.createSession(modelFile.absolutePath, opts).use { session ->
            val inputBuffer = ByteBuffer.allocateDirect(2 * SEGMENT_SAMPLES * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            for (i in 0 until nChunks) {
                val start = i * STRIDE
                val end = min(start + SEGMENT_SAMPLES, total)
                val clen = end - start
                fillChunk(inputBuffer, mix, start, clen)
                inputBuffer.rewind()
                OnnxTensor.createTensor(
                    env,
                    inputBuffer,
                    longArrayOf(1, 2, SEGMENT_SAMPLES.toLong()),
                ).use { input ->
                    session.run(mapOf("mix" to input)).use { result ->
                        val tensor = result.get("stems")
                            .orElseThrow { IllegalStateException("Модель не вернула тензор stems") }
                            as OnnxTensor
                        addChunk(tensor, start, clen, window, outL, outR, weight)
                    }
                }
                onChunk(i + 1, nChunks)
            }
        }
        for (t in 0 until total) {
            val w = weight[t].coerceAtLeast(1e-8f)
            for (s in 0 until 4) {
                outL[s][t] /= w
                outR[s][t] /= w
            }
        }
        return stemOrder.indices.associate { index ->
            stemOrder[index] to StereoPcm(SAMPLE_RATE, outL[index], outR[index])
        }
    }

    private fun fillChunk(buffer: FloatBuffer, mix: StereoPcm, start: Int, clen: Int) {
        buffer.clear()
        for (ch in 0..1) {
            val src = if (ch == 0) mix.left else mix.right
            for (t in 0 until SEGMENT_SAMPLES) {
                buffer.put(if (t < clen) src[start + t] else 0f)
            }
        }
        buffer.flip()
    }

    private fun addChunk(
        tensor: OnnxTensor,
        start: Int,
        clen: Int,
        window: FloatArray,
        outL: Array<FloatArray>,
        outR: Array<FloatArray>,
        weight: FloatArray,
    ) {
        val buf = tensor.floatBuffer
        // (1, 4, 2, N) row-major
        val n = SEGMENT_SAMPLES
        for (stem in 0 until 4) {
            val baseL = stem * 2 * n
            val baseR = baseL + n
            for (t in 0 until clen) {
                val w = window[t]
                outL[stem][start + t] += buf.get(baseL + t) * w
                outR[stem][start + t] += buf.get(baseR + t) * w
            }
        }
        for (t in 0 until clen) {
            weight[start + t] += window[t]
        }
    }

    private fun makeWindow(n: Int, overlap: Int): FloatArray {
        val w = FloatArray(n) { 1f }
        if (overlap <= 0) return w
        for (i in 0 until overlap) {
            val fade = i.toFloat() / overlap
            w[i] = fade
            w[n - 1 - i] = fade
        }
        return w
    }
}
