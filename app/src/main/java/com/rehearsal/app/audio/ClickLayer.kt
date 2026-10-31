package com.rehearsal.app.audio

import java.nio.ByteBuffer

/**
 * Константы клика и тестовый painter: обнуляет буфер и зовёт [MetronomeClickEngine.mixInto].
 * Прод-путь — тот же mix после WSOLA в [MetronomePcmPipe], не отдельный AudioTrack.
 */
object ClickLayer {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = 4

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
