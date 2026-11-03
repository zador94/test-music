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
 *
 * Длинный трек: [streamSeparate] — те же окна 7.8 с и overlap-add, что
 * in-memory [separate] в 0.3.28, но аккумулятор только на один сегмент,
 * готовый STRIDE сразу на диск. Внешний слой 30 с + линейный кроссфейд
 * из 0.3.29 вложенно окончил уже оконченный кусок и паддил последний
 * внутренний сегмент нулями — в стемах были помехи вместо музыки.
 */
object HtDemucs {
    const val SAMPLE_RATE = 44100
    const val SEGMENT_SAMPLES = 343_980
    const val OVERLAP_SAMPLES = SEGMENT_SAMPLES / 4
    const val STRIDE_SAMPLES = SEGMENT_SAMPLES - OVERLAP_SAMPLES

    private val stemOrder = arrayOf(StemId.DRUMS, StemId.BASS, StemId.OTHER, StemId.VOCALS)

    fun <T> withSession(modelFile: File, block: (OrtSession) -> T): T {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            setIntraOpNumThreads(2)
            setMemoryPatternOptimization(false)
        }
        return env.createSession(modelFile.absolutePath, opts).use(block)
    }

    fun separate(
        mix: StereoPcm,
        modelFile: File,
        onChunk: (index: Int, total: Int) -> Unit,
    ): Map<StemId, StereoPcm> = withSession(modelFile) { session ->
        separate(mix, session, onChunk)
    }

    fun separate(
        mix: StereoPcm,
        session: OrtSession,
        onChunk: (index: Int, total: Int) -> Unit,
    ): Map<StemId, StereoPcm> {
        require(mix.sampleRate == SAMPLE_RATE)
        val total = mix.samples
        val nChunks = maxOf(1, (total + STRIDE_SAMPLES - 1) / STRIDE_SAMPLES)
        val window = makeWindow(SEGMENT_SAMPLES, OVERLAP_SAMPLES)
        val outL = Array(4) { FloatArray(total) }
        val outR = Array(4) { FloatArray(total) }
        val weight = FloatArray(total)
        val env = OrtEnvironment.getEnvironment()
        val inputBuffer = ByteBuffer.allocateDirect(2 * SEGMENT_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until nChunks) {
            val start = i * STRIDE_SAMPLES
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

    /**
     * Окна модели 7.8 с, overlap-add в кольце SEGMENT, готовый STRIDE на диск.
     * В RAM нет ни всего микса, ни четырёх стемов целиком.
     */
    fun streamSeparate(
        mixWav: File,
        session: OrtSession,
        writers: Map<StemId, WavWriter>,
        onChunk: (index: Int, total: Int) -> Unit,
    ) {
        val env = OrtEnvironment.getEnvironment()
        val inputBuffer = ByteBuffer.allocateDirect(2 * SEGMENT_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        streamOverlapAdd(mixWav, writers, onChunk) { mixSlice, clen, accL, accR, weight, window ->
            fillChunk(inputBuffer, mixSlice, 0, clen)
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
                    addChunk(tensor, start = 0, clen, window, accL, accR, weight)
                }
            }
        }
    }

    /**
     * Тождественная «модель»: копирует микс во все 4 стема. Проверяет, что
     * overlap-add на диск восстанавливает исходник без щелей и без хвоста нулей.
     */
    internal fun streamOverlapAddIdentity(
        mixWav: File,
        writers: Map<StemId, WavWriter>,
        onChunk: (index: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        streamOverlapAdd(mixWav, writers, onChunk) { mixSlice, clen, accL, accR, weight, window ->
            addIdentityChunk(mixSlice, clen, window, accL, accR, weight)
        }
    }

    internal fun streamOverlapAdd(
        mixWav: File,
        writers: Map<StemId, WavWriter>,
        onChunk: (index: Int, total: Int) -> Unit,
        addModelChunk: (
            mixSlice: StereoPcm,
            clen: Int,
            accL: Array<FloatArray>,
            accR: Array<FloatArray>,
            weight: FloatArray,
            window: FloatArray,
        ) -> Unit,
    ) {
        val mapped = WavReader.openMapped(mixWav)
        try {
            require(mapped.info.sampleRate == SAMPLE_RATE) {
                "HT-Demucs ждёт ${SAMPLE_RATE} Гц, в файле ${mapped.info.sampleRate}"
            }
            val total = mapped.info.frameCount
            require(total > 0) { "Пустой mix WAV" }
            val nChunks = maxOf(1, (total + STRIDE_SAMPLES - 1) / STRIDE_SAMPLES)
            val window = makeWindow(SEGMENT_SAMPLES, OVERLAP_SAMPLES)
            val accL = Array(4) { FloatArray(SEGMENT_SAMPLES) }
            val accR = Array(4) { FloatArray(SEGMENT_SAMPLES) }
            val weight = FloatArray(SEGMENT_SAMPLES)
            val outL = Array(4) { FloatArray(SEGMENT_SAMPLES) }
            val outR = Array(4) { FloatArray(SEGMENT_SAMPLES) }
            for (i in 0 until nChunks) {
                val start = i * STRIDE_SAMPLES
                val clen = min(SEGMENT_SAMPLES, total - start)
                val mixSlice = mapped.readRange(start, clen)
                addModelChunk(mixSlice, clen, accL, accR, weight, window)
                val last = i == nChunks - 1
                val flushCount = if (last) clen else STRIDE_SAMPLES
                flushAcc(flushCount, accL, accR, weight, outL, outR, writers)
                if (!last) {
                    shiftAcc(STRIDE_SAMPLES, accL, accR, weight)
                }
                onChunk(i + 1, nChunks)
            }
        } finally {
            mapped.close()
        }
    }

    private fun flushAcc(
        count: Int,
        accL: Array<FloatArray>,
        accR: Array<FloatArray>,
        weight: FloatArray,
        outL: Array<FloatArray>,
        outR: Array<FloatArray>,
        writers: Map<StemId, WavWriter>,
    ) {
        val n = count.coerceAtLeast(0)
        if (n == 0) return
        for (t in 0 until n) {
            val w = weight[t].coerceAtLeast(1e-8f)
            for (s in 0 until 4) {
                outL[s][t] = accL[s][t] / w
                outR[s][t] = accR[s][t] / w
            }
        }
        for (s in 0 until 4) {
            val writer = writers[stemOrder[s]] ?: continue
            writer.writePlanarFloatStereo(outL[s], outR[s], n)
        }
    }

    private fun shiftAcc(
        shift: Int,
        accL: Array<FloatArray>,
        accR: Array<FloatArray>,
        weight: FloatArray,
    ) {
        val keep = SEGMENT_SAMPLES - shift
        if (keep < 0 || shift < 0) return
        for (s in 0 until 4) {
            System.arraycopy(accL[s], shift, accL[s], 0, keep)
            accL[s].fill(0f, keep, SEGMENT_SAMPLES)
            System.arraycopy(accR[s], shift, accR[s], 0, keep)
            accR[s].fill(0f, keep, SEGMENT_SAMPLES)
        }
        System.arraycopy(weight, shift, weight, 0, keep)
        weight.fill(0f, keep, SEGMENT_SAMPLES)
    }

    private fun fillChunk(buffer: FloatBuffer, mix: StereoPcm, start: Int, clen: Int) {
        buffer.clear()
        val available = mix.samples
        for (ch in 0..1) {
            val src = if (ch == 0) mix.left else mix.right
            for (t in 0 until SEGMENT_SAMPLES) {
                val idx = start + t
                buffer.put(if (t < clen && idx < available) src[idx] else 0f)
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
        val n = SEGMENT_SAMPLES
        val limit = outL[0].size
        for (stem in 0 until 4) {
            val baseL = stem * 2 * n
            val baseR = baseL + n
            for (t in 0 until clen) {
                val dest = start + t
                if (dest < 0 || dest >= limit) break
                val w = window[t]
                outL[stem][dest] += buf.get(baseL + t) * w
                outR[stem][dest] += buf.get(baseR + t) * w
            }
        }
        for (t in 0 until clen) {
            val dest = start + t
            if (dest < 0 || dest >= weight.size) break
            weight[dest] += window[t]
        }
    }

    private fun addIdentityChunk(
        mix: StereoPcm,
        clen: Int,
        window: FloatArray,
        accL: Array<FloatArray>,
        accR: Array<FloatArray>,
        weight: FloatArray,
    ) {
        val n = min(clen, mix.samples)
        for (t in 0 until n) {
            val w = window[t]
            val l = mix.left[t]
            val r = mix.right[t]
            for (s in 0 until 4) {
                accL[s][t] += l * w
                accR[s][t] += r * w
            }
            weight[t] += w
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
