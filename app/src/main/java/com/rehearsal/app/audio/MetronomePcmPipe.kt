package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import com.rehearsal.app.player.SpeedStretch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Копия PCM (live-remix стемов) → WSOLA на любой скорости → клик в **тот же** буфер →
 * DefaultAudioSink. AudioTrack всегда 1×. Один выход: music_after_stretch + click.
 *
 * DefaultAudioSink держит `inputBuffer` и требует **тот же** ByteBuffer, пока не съест его.
 * Поэтому всегда копируем, даже если метроном выключен.
 */
class MetronomePcmPipe(
    private val clickEngine: MetronomeClickEngine,
    private val mixBank: StemMixBank? = null,
) {
    private var mixBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var stretchBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var retryBuffer: ByteBuffer? = null
    private var retryPts: Long = C.TIME_UNSET
    private var mediaCursorUs: Long = 0L
    private var loggedStart: Boolean = false
    @Volatile var softwareSpeed: Float = 1f
    var configured: Boolean = false
        private set

    fun configure(format: Format) {
        clickEngine.configure(format)
        retryBuffer = null
        retryPts = C.TIME_UNSET
        configured = true
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
        if (!configured) {
            retryBuffer = null
            retryPts = C.TIME_UNSET
            return sinkHandle(source, presentationTimeUs, encodedAccessUnitCount)
        }
        val toWrite = if (retryBuffer != null && presentationTimeUs == retryPts) {
            retryBuffer!!
        } else {
            val pts = resolvePts(presentationTimeUs, source.remaining())
            val mixed = ClickMix.copyAndMix(
                source,
                mixBuffer,
                clickEngine,
                pts,
                mixBank,
            )
            mixBuffer = mixed
            val frameSize = ClickMix.frameSizeBytes(
                clickEngine.encoding,
                clickEngine.channelCount,
            ).coerceAtLeast(1)
            val inFrames = mixed.remaining() / frameSize
            val stretched = SpeedStretch.timeStretch(
                mixed,
                stretchBuffer,
                softwareSpeed,
                clickEngine.encoding,
                clickEngine.channelCount,
            )
            stretchBuffer = stretched
            val outFrames = stretched.remaining() / frameSize
            if (!loggedStart) {
                loggedStart = true
                logStart(pts, inFrames, outFrames)
            }
            clickEngine.mixInto(stretched, pts, softwareSpeed, inFrames, outFrames)
            retryBuffer = stretched
            retryPts = presentationTimeUs
            stretched
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
        SpeedStretch.flush()
        retryBuffer = null
        retryPts = C.TIME_UNSET
        mediaCursorUs = 0L
        loggedStart = false
    }

    fun reset() {
        flush()
        configured = false
        softwareSpeed = 1f
    }

    private fun resolvePts(presentationTimeUs: Long, remainingBytes: Int): Long {
        val pts = if (presentationTimeUs == C.TIME_UNSET || presentationTimeUs < 0L) {
            mediaCursorUs
        } else {
            presentationTimeUs
        }
        val frameSize = ClickMix.frameSizeBytes(clickEngine.encoding, clickEngine.channelCount)
            .coerceAtLeast(1)
        val frames = remainingBytes / frameSize
        mediaCursorUs = pts + BeatClock.sampleToMediaUs(frames.toLong(), clickEngine.sampleRate)
        return pts
    }

    private fun logStart(ptsUs: Long, inFrames: Int, outFrames: Int) {
        val phaseMs = clickEngine.kickPhaseUs / 1000.0
        val originMs = clickEngine.gridOriginUs / 1000.0
        val songMs = ptsUs.coerceAtLeast(0L) / 1000.0
        val padFrames = (inFrames - outFrames).coerceAtLeast(0)
        // Per-chunk WSOLA, no overlap delay line. Speed 1.0 is passthrough:
        // delayFrames stays 0 — any −170 ms is not start-pad / WSOLA latency.
        val delayFrames = 0
        val msg = String.format(
            Locale.US,
            "start phase=%.1fms origin=%.1fms songOffset=%.1fms speed=%.2f in=%d out=%d padFrames=%d delayFrames=%d",
            phaseMs,
            originMs,
            songMs,
            softwareSpeed,
            inFrames,
            outFrames,
            padFrames,
            delayFrames,
        )
        println("$TAG $msg")
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
            // JVM unit tests: Log is a stub; println above still shows the line.
        }
    }

    companion object {
        const val TAG = "ClickPipe"
    }
}

/**
 * Копия PCM + опциональный live-remix стемов. Клики — после stretch в [MetronomePcmPipe],
 * не здесь: WSOLA не должен смазывать атаку.
 */
object ClickMix {
    fun frameSizeBytes(encoding: Int, channelCount: Int): Int {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        return bytesPerSample * channelCount.coerceAtLeast(1)
    }

    fun canLiveRemix(
        remaining: Int,
        encoding: Int,
        channelCount: Int,
        presentationTimeUs: Long,
        durationUs: Long,
    ): Boolean {
        if (presentationTimeUs == C.TIME_UNSET || presentationTimeUs < 0L) return false
        if (durationUs <= 0L || presentationTimeUs >= durationUs) return false
        val frameSize = frameSizeBytes(encoding, channelCount)
        // Whole frames only. A codec tail of 1–3 bytes must not skip remix:
        // otherwise Music is whatever MediaCodec emitted and the click still
        // uses PTS — the one path that could put a constant gap between them.
        return frameSize > 0 && remaining >= frameSize
    }

    fun copyAndMix(
        source: ByteBuffer,
        dest: ByteBuffer,
        engine: MetronomeClickEngine,
        presentationTimeUs: Long,
        mixBank: StemMixBank? = null,
    ): ByteBuffer {
        val remaining = source.remaining()
        val out = if (dest.capacity() < remaining) {
            ByteBuffer.allocateDirect(remaining.coerceAtLeast(4096))
        } else {
            dest
        }
        out.order(ByteOrder.nativeOrder())
        out.clear()
        val dup = source.asReadOnlyBuffer()
        out.put(dup)
        out.flip()
        val frameSize = frameSizeBytes(engine.encoding, engine.channelCount).coerceAtLeast(1)
        val mixBytes = remaining / frameSize * frameSize
        if (mixBank != null && mixBank.active &&
            canLiveRemix(
                mixBytes,
                engine.encoding,
                engine.channelCount,
                presentationTimeUs,
                mixBank.durationUs(),
            )
        ) {
            out.position(0)
            out.limit(mixBytes)
            mixBank.render(out, engine.encoding, engine.channelCount, presentationTimeUs)
            out.position(0)
            out.limit(remaining)
        }
        out.order(ByteOrder.nativeOrder())
        out.position(0)
        out.limit(remaining)
        return out
    }
}
