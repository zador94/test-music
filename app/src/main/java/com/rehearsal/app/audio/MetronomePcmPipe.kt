package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import com.rehearsal.app.player.SpeedStretch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Всегда копирует PCM в свой буфер (live-remix стемов + software speed-up), и только
 * копию отдаёт в DefaultAudioSink. Клики метронома **не** здесь: [ClickTrack].
 *
 * DefaultAudioSink держит `inputBuffer` и требует **тот же** ByteBuffer, пока не съест его.
 * Если при выключенных кликах отдать буфер декодера, а при включении — mixBuffer,
 * sink бросает IllegalArgumentException → ExoPlayer error, play() no-op, пока не load().
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
    @Volatile var softwareSpeed: Float = 1f
    var configured: Boolean = false
        private set

    fun configure(format: Format) {
        clickEngine.configure(format)
        retryBuffer = null
        retryPts = C.TIME_UNSET
        // Вход AudioSink — всегда PCM после декодера / StemMixMediaSource.
        // 0.3.7: isPcm()==false → handleBuffer отдавал source без копии.
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
            val stretched = SpeedStretch.decimate(
                mixed,
                stretchBuffer,
                softwareSpeed,
                clickEngine.encoding,
                clickEngine.channelCount,
            )
            stretchBuffer = stretched
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
        retryBuffer = null
        retryPts = C.TIME_UNSET
        mediaCursorUs = 0L
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
}

/**
 * Копия PCM + опциональный live-remix стемов + клики.
 *
 * 0.3.6 при `mixBank.active` **не копировал** source, а сразу `render()` в пустой
 * буфер. Несовпавший encoding/channelCount или PTS за duration давали нули —
 * playhead шёл, колонки молчали, клики мешались в тишину или скидывались.
 * Сейчас source (уже сведённый StemMixMediaSource) всегда база; remix только
 * если кадр и media time валидны. Клики метронома — отдельный [ClickTrack],
 * не этот буфер (веб: отдельный audio node).
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
        return frameSize > 0 && remaining >= frameSize && remaining % frameSize == 0
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
        if (mixBank != null && mixBank.active &&
            canLiveRemix(
                remaining,
                engine.encoding,
                engine.channelCount,
                presentationTimeUs,
                mixBank.durationUs(),
            )
        ) {
            out.position(0)
            out.limit(remaining)
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
