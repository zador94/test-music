package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin

/**
 * Слой клика: [renderLayer] в отдельный PCM, не в stem mix.
 * Ровные четверти по [BeatClock] на original BPM; origin — downbeatOffset анализа.
 * Неровный `beats[]` метроном не играет: из‑за snap к пикам это были не четверти
 * и не тот темп, что на слайдере. Handler-таймера нет.
 */
class MetronomeClickEngine {
    @Volatile var enabled: Boolean = false
    @Volatile var originalBpm: Float = DEFAULT_BPM
    @Volatile var gridOriginUs: Long = 0L
        private set

    var sampleRate: Int = ClickLayer.SAMPLE_RATE
        private set
    var channelCount: Int = ClickLayer.CHANNELS
        private set
    var encoding: Int = C.ENCODING_PCM_16BIT
        private set

    private var bytesPerSample: Int = 2
    private val click: FloatArray = synthesizeClick(ClickLayer.SAMPLE_RATE)

    fun configure(format: Format) {
        sampleRate = format.sampleRate.takeIf { it > 0 } ?: ClickLayer.SAMPLE_RATE
        channelCount = format.channelCount.takeIf { it > 0 } ?: ClickLayer.CHANNELS
        encoding = resolveEncoding(format)
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }

    fun onFlush() {
        // Сетка из presentationTimeUs / playhead. Состояния клика нет.
    }

    fun isPcm(): Boolean = bytesPerSample > 0

    fun clickDurationUs(): Long =
        click.size.toLong() * 1_000_000L / ClickLayer.SAMPLE_RATE

    fun setAnalysis(analysis: TrackAnalysis?) {
        val originSec = analysis?.downbeatOffset ?: 0.0
        gridOriginUs = TempoMath.secondsToMediaUs(originSec)
    }

    fun setGridOriginUs(originUs: Long) {
        gridOriginUs = originUs.coerceAtLeast(0L)
    }

    /**
     * Только клики в [out] (stereo 16-bit native). Не читает stems.
     * @return us последней нарисованной доли
     */
    fun renderLayer(
        out: ByteBuffer,
        mediaStartUs: Long,
        speed: Float,
        fromExclusiveUs: Long = Long.MIN_VALUE,
    ): Long {
        out.order(ByteOrder.nativeOrder())
        zero(out)
        if (!enabled) return fromExclusiveUs
        val bpm = originalBpm
        if (bpm < MIN_BPM) return fromExclusiveUs
        val rate = ClickLayer.SAMPLE_RATE
        val origin = out.position()
        val remaining = out.remaining()
        val frames = remaining / ClickLayer.BYTES_PER_FRAME
        if (frames <= 0) return fromExclusiveUs
        val speedSafe = speed.coerceIn(0.5f, 2f)
        val wallDurationUs = BeatClock.sampleToMediaUs(frames.toLong(), rate)
        val mediaDurationUs = (wallDurationUs * speedSafe.toDouble()).toLong().coerceAtLeast(1L)
        val startUs = if (mediaStartUs == C.TIME_UNSET || mediaStartUs < 0L) 0L else mediaStartUs
        val endUs = startUs + mediaDurationUs
        val clickMediaUs = (clickDurationUs() * speedSafe.toDouble()).toLong().coerceAtLeast(1L)
        val last = paintEqualGrid(
            out,
            origin,
            frames,
            startUs,
            endUs,
            clickMediaUs,
            speedSafe,
            BeatClock.intervalUs(bpm),
            gridOriginUs,
            fromExclusiveUs,
        )
        out.position(origin)
        out.limit(origin + frames * ClickLayer.BYTES_PER_FRAME)
        return last
    }

    private fun paintEqualGrid(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        endUs: Long,
        clickMediaUs: Long,
        speed: Float,
        intervalUs: Long,
        gridOriginUs: Long,
        fromExclusiveUs: Long,
    ): Long {
        var beatUs = BeatClock.firstOverlappingBeatUs(startUs, intervalUs, clickMediaUs, gridOriginUs)
        var last = fromExclusiveUs
        var guard = 0
        while (guard < MAX_BEATS_PER_BUFFER) {
            if (beatUs >= endUs) break
            val startFrame = wallFrame(beatUs, startUs, speed)
            if (startFrame >= frames && beatUs > startUs) break
            if (beatUs > fromExclusiveUs) {
                paintClick(buffer, origin, frames, startUs, beatUs, speed)
                last = maxOf(last, beatUs)
            }
            val next = beatUs + intervalUs
            if (next <= beatUs) break
            beatUs = next
            guard++
        }
        return last
    }

    private fun paintClick(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        beatUs: Long,
        speed: Float,
    ) {
        val startFrame = wallFrame(beatUs, startUs, speed)
        val clickOffset = if (startFrame < 0) -startFrame else 0
        val frame = if (startFrame < 0) 0 else startFrame
        if (clickOffset >= click.size || frame >= frames) return
        var i = clickOffset
        var f = frame
        while (i < click.size && f < frames) {
            writeShortFrame(buffer, origin, f, click[i] * CLICK_GAIN)
            i++
            f++
        }
    }

    private fun wallFrame(beatUs: Long, startUs: Long, speed: Float): Int {
        val mediaFrame = BeatClock.frameOffset(beatUs, startUs, ClickLayer.SAMPLE_RATE)
        if (speed == 1f) return mediaFrame
        return round(mediaFrame / speed.toDouble()).toInt()
    }

    private fun writeShortFrame(buffer: ByteBuffer, origin: Int, frame: Int, sample: Float) {
        val pcm = (sample * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        for (ch in 0 until ClickLayer.CHANNELS) {
            val idx = origin + (frame * ClickLayer.CHANNELS + ch) * 2
            if (idx + 2 > buffer.limit()) return
            buffer.putShort(idx, pcm)
        }
    }

    private fun zero(out: ByteBuffer) {
        val pos = out.position()
        val lim = out.limit()
        for (i in pos until lim) out.put(i, 0)
    }

    private fun resolveEncoding(format: Format): Int {
        return when (format.pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
            C.ENCODING_PCM_16BIT -> C.ENCODING_PCM_16BIT
            else -> {
                val mime = format.sampleMimeType
                if (mime == null || mime == MimeTypes.AUDIO_RAW) {
                    C.ENCODING_PCM_16BIT
                } else if (mime == MimeTypes.AUDIO_AAC ||
                    mime == MimeTypes.AUDIO_MPEG ||
                    mime == MimeTypes.AUDIO_OPUS
                ) {
                    0
                } else if (mime.startsWith("audio/")) {
                    C.ENCODING_PCM_16BIT
                } else {
                    0
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BPM = 120f
        const val MIN_BPM = 30f
        const val MAX_BPM = 240f
        const val CLICK_GAIN = 1f
        const val CLICK_PEAK = 0.92f
        const val CLICK_DURATION_SEC = 0.006
        private const val MAX_BEATS_PER_BUFFER = 10_000

        /**
         * Один короткий клик на каждую четверть. Без «раза» и без смены тембра.
         */
        fun synthesizeClick(sampleRate: Int): FloatArray {
            val sr = sampleRate.coerceAtLeast(1)
            val n = (sr * CLICK_DURATION_SEC).toInt().coerceAtLeast(32)
            val f0 = 2000.0
            val f1 = 4500.0
            val tau = 0.0010
            val raw = FloatArray(n) { i ->
                val t = i.toDouble() / sr
                val env = exp(-t / tau)
                val s = (sin(2.0 * PI * f0 * t) + 0.45 * sin(2.0 * PI * f1 * t)) * env
                s.toFloat()
            }
            return peakNormalize(raw, CLICK_PEAK)
        }

        private fun peakNormalize(wave: FloatArray, peak: Float): FloatArray {
            var max = 1e-9f
            for (s in wave) max = maxOf(max, abs(s))
            val g = peak / max
            return FloatArray(wave.size) { i -> wave[i] * g }
        }
    }
}
