package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Всегда копирует PCM в свой буфер, потом (если надо) мешает клики, и только копию
 * отдаёт в DefaultAudioSink.
 *
 * DefaultAudioSink держит `inputBuffer` и требует **тот же** ByteBuffer, пока не съест его.
 * Если при выключенных кликах отдать буфер декодера, а при включении — mixBuffer,
 * sink бросает IllegalArgumentException → ExoPlayer error, play() no-op, пока не load().
 */
class MetronomePcmPipe(
    private val clickEngine: MetronomeClickEngine,
) {
    private var mixBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    private var retryBuffer: ByteBuffer? = null
    private var retryPts: Long = C.TIME_UNSET
    var configured: Boolean = false
        private set

    fun configure(format: Format) {
        clickEngine.configure(format)
        retryBuffer = null
        retryPts = C.TIME_UNSET
        configured = clickEngine.isPcm()
    }

    /**
     * @return true, если [source] полностью принят (как handleBuffer у AudioSink).
     */
    fun handleBuffer(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
        sinkHandle: (ByteBuffer, Long, Int) -> Boolean,
    ): Boolean {
        if (!configured || !clickEngine.isPcm()) {
            retryBuffer = null
            retryPts = C.TIME_UNSET
            return sinkHandle(source, presentationTimeUs, encodedAccessUnitCount)
        }
        val toWrite = if (retryBuffer != null && presentationTimeUs == retryPts) {
            retryBuffer!!
        } else {
            val mixed = ClickMix.copyAndMix(source, mixBuffer, clickEngine, presentationTimeUs)
            mixBuffer = mixed
            retryBuffer = mixed
            retryPts = presentationTimeUs
            mixed
        }
        val accepted = sinkHandle(toWrite, presentationTimeUs, encodedAccessUnitCount)
        if (accepted) {
            source.position(source.limit())
            retryBuffer = null
            retryPts = C.TIME_UNSET
        }
        return accepted
    }

    fun flush() {
        clickEngine.onFlush()
        retryBuffer = null
        retryPts = C.TIME_UNSET
    }

    fun reset() {
        flush()
        configured = false
    }
}

/** Копия PCM + клики. Тесты гоняют это же, без AudioSink. */
object ClickMix {
    fun copyAndMix(
        source: ByteBuffer,
        dest: ByteBuffer,
        engine: MetronomeClickEngine,
        presentationTimeUs: Long,
    ): ByteBuffer {
        val remaining = source.remaining()
        val out = if (dest.capacity() < remaining) {
            ByteBuffer.allocateDirect(remaining.coerceAtLeast(4096)).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            dest.order(ByteOrder.LITTLE_ENDIAN)
        }
        out.clear()
        val dup = source.asReadOnlyBuffer()
        out.put(dup)
        out.flip()
        if (engine.enabled && engine.isPcm()) {
            engine.mixInPlace(out, presentationTimeUs)
            out.position(0)
            out.limit(remaining)
        }
        return out
    }
}
