package com.rehearsal.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Отдельный слой клика, как Web Audio node: не внутри stem PCM.
 * Всегда stereo 16-bit native @ 44.1 kHz. Gain «раз» 2.2, остальные доли тише;
 * downbeat длиннее/ниже, остальные короче/выше.
 *
 * Буфер — wall time (как AudioContext). Media time = playhead + wall * speed.
 * Lookahead веб-контракта: [LOOKAHEAD_SEC] * playbackRate секунд song time
 * при chunk = LOOKAHEAD_SEC wall.
 */
object ClickLayer {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val LOOKAHEAD_SEC = 0.12f
    const val BYTES_PER_FRAME = 4

    fun lookaheadUs(speed: Float): Long {
        val rate = speed.coerceIn(0.5f, 2f)
        return (LOOKAHEAD_SEC * rate * 1_000_000.0).toLong().coerceAtLeast(1L)
    }

    /** Media-us, которые покрывает один wall-chunk при [speed]. */
    fun mediaAdvanceUs(speed: Float): Long {
        val wallUs = BeatClock.sampleToMediaUs(chunkFrames().toLong(), SAMPLE_RATE)
        val rate = speed.coerceIn(0.5f, 2f)
        return (wallUs * rate.toDouble()).toLong().coerceAtLeast(1L)
    }

    fun chunkFrames(): Int =
        (SAMPLE_RATE * LOOKAHEAD_SEC.toDouble()).toInt().coerceAtLeast(1024)

    fun chunkBytes(): Int = chunkFrames() * BYTES_PER_FRAME

    fun allocateChunk(): ByteBuffer =
        ByteBuffer.allocateDirect(chunkBytes()).order(ByteOrder.nativeOrder())

    /**
     * Пишет **только** клики в [out]. Stem PCM не читает.
     * @return media us последней нарисованной доли
     */
    fun render(
        out: ByteBuffer,
        engine: MetronomeClickEngine,
        mediaStartUs: Long,
        speed: Float,
        fromExclusiveUs: Long = Long.MIN_VALUE,
    ): Long = engine.renderLayer(out, mediaStartUs, speed, fromExclusiveUs)
}
