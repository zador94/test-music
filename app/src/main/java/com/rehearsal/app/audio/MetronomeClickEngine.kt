package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import com.rehearsal.app.player.SpeedStretch
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Клик в том же PCM, что музыка: [mixInto] **после** WSOLA.
 * Ровные четверти: шаг = `periodSec` tempoOfTrack, начало = `phaseSec` микса
 * плюс калибровка. Не `60 / bpm` после 0.1 и не KickGrid по drums.
 * Клик не растягивается; позиция доли — в выходных сэмплах.
 */
class MetronomeClickEngine {
    @Volatile var enabled: Boolean = false
    /** Читает `ClickPipe` logStart. Не `private var` — тогда assembleDebug падает. */
    var kickPhaseUs: Long = 0L
        private set
    @Volatile var gridOriginUs: Long = 0L
        private set
    @Volatile var periodSec: Double = 0.0
        private set
    @Volatile var originalBpm: Float = DEFAULT_BPM
        set(value) {
            field = value
            refreshGridOrigin()
        }
    @Volatile var calibrationUs: Long = 0L
        set(value) {
            field = value
            refreshGridOrigin()
        }

    var sampleRate: Int = ClickLayer.SAMPLE_RATE
        private set
    var channelCount: Int = ClickLayer.CHANNELS
        private set
    var encoding: Int = C.ENCODING_PCM_16BIT
        private set

    private var bytesPerSample: Int = 2
    private var click: FloatArray = synthesizeClick(ClickLayer.SAMPLE_RATE)

    fun configure(format: Format) {
        sampleRate = format.sampleRate.takeIf { it > 0 } ?: ClickLayer.SAMPLE_RATE
        channelCount = format.channelCount.takeIf { it > 0 } ?: ClickLayer.CHANNELS
        encoding = resolveEncoding(format)
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val n = (sampleRate * CLICK_DURATION_SEC).toInt().coerceAtLeast(32)
        if (click.size != n) {
            click = synthesizeClick(sampleRate)
        }
    }

    fun onFlush() {
        SpeedStretch.flush()
    }

    fun isPcm(): Boolean = bytesPerSample > 0

    fun clickDurationUs(): Long =
        click.size.toLong() * 1_000_000L / sampleRate.coerceAtLeast(1)

    fun setAnalysis(analysis: TrackAnalysis?) {
        periodSec = analysis?.periodSec ?: 0.0
        val phase = analysis?.clickPhaseSec() ?: 0.0
        kickPhaseUs = TempoMath.secondsToMediaUs(phase)
        refreshGridOrigin()
    }

    fun clickIntervalUs(): Long {
        if (periodSec > 1e-9) return BeatClock.intervalFromPeriodSec(periodSec)
        return BeatClock.intervalUs(originalBpm)
    }

    fun setGridOriginUs(originUs: Long) {
        gridOriginUs = originUs.coerceAtLeast(0L)
    }

    private fun refreshGridOrigin() {
        gridOriginUs = BeatClock.gridOriginUs(
            kickPhaseUs,
            calibrationUs,
            clickIntervalUs(),
        )
    }

    /**
     * Только клики в [out]. Обнуляет буфер, затем [mixInto].
     * @return us последней нарисованной доли
     */
    fun renderLayer(
        out: ByteBuffer,
        mediaStartUs: Long,
        speed: Float,
        fromExclusiveUs: Long = Long.MIN_VALUE,
    ): Long {
        zero(out)
        val frameSize = ClickMix.frameSizeBytes(encoding, channelCount).coerceAtLeast(1)
        val origin = out.position()
        val remaining = out.remaining()
        val outFrames = remaining / frameSize
        if (outFrames <= 0) return fromExclusiveUs
        val speedSafe = speed.coerceIn(0.5f, 2f)
        val inFrames = SpeedStretch.inputFrameCount(outFrames, speedSafe)
        val last = mixInto(out, mediaStartUs, speedSafe, inFrames, outFrames, fromExclusiveUs)
        out.position(origin)
        out.limit(origin + outFrames * frameSize)
        return last
    }

    /**
     * Добавляет нерастянутый клик в уже растянутый PCM.
     * `out[i] += click[i - beatSampleOut]`, затем limiter.
     */
    fun mixInto(
        out: ByteBuffer,
        mediaStartUs: Long,
        speed: Float,
        inputFrames: Int,
        outputFrames: Int,
        fromExclusiveUs: Long = Long.MIN_VALUE,
    ): Long {
        if (!enabled) return fromExclusiveUs
        val bpm = originalBpm
        if (bpm < MIN_BPM) return fromExclusiveUs
        val frames = outputFrames.coerceAtLeast(0)
        if (frames <= 0) return fromExclusiveUs
        val speedSafe = speed.coerceIn(0.5f, 2f)
        val inFrames = inputFrames.coerceAtLeast(1)
        val startUs = when {
            mediaStartUs == C.TIME_UNSET -> 0L
            mediaStartUs < 0L -> return fromExclusiveUs
            else -> mediaStartUs
        }
        val mediaDurationUs = BeatClock.sampleToMediaUs(inFrames.toLong(), sampleRate)
            .coerceAtLeast(1L)
        val endUs = startUs + mediaDurationUs
        val clickMediaUs = (clickDurationUs() * speedSafe.toDouble()).toLong().coerceAtLeast(1L)
        val origin = out.position()
        return paintEqualGrid(
            out,
            origin,
            inFrames,
            frames,
            startUs,
            endUs,
            clickMediaUs,
            clickIntervalUs(),
            gridOriginUs,
            fromExclusiveUs,
        )
    }

    private fun paintEqualGrid(
        buffer: ByteBuffer,
        origin: Int,
        inFrames: Int,
        outFrames: Int,
        startUs: Long,
        endUs: Long,
        clickMediaUs: Long,
        intervalUs: Long,
        gridOriginUs: Long,
        fromExclusiveUs: Long,
    ): Long {
        var beatUs = BeatClock.firstOverlappingBeatUs(startUs, intervalUs, clickMediaUs, gridOriginUs)
        var last = fromExclusiveUs
        var guard = 0
        while (guard < MAX_BEATS_PER_BUFFER) {
            if (beatUs >= endUs) break
            val startFrame = outputFrame(beatUs, startUs, inFrames, outFrames)
            if (startFrame >= outFrames && beatUs > startUs) break
            if (beatUs > fromExclusiveUs) {
                paintClick(buffer, origin, inFrames, outFrames, startUs, beatUs)
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
        inFrames: Int,
        outFrames: Int,
        startUs: Long,
        beatUs: Long,
    ) {
        val startFrame = outputFrame(beatUs, startUs, inFrames, outFrames)
        val clickOffset = if (startFrame < 0) -startFrame else 0
        val frame = if (startFrame < 0) 0 else startFrame
        if (clickOffset >= click.size || frame >= outFrames) return
        var i = clickOffset
        var f = frame
        while (i < click.size && f < outFrames) {
            mixFrame(buffer, origin, f, click[i] * CLICK_GAIN * CLICK_VOLUME)
            i++
            f++
        }
    }

    private fun outputFrame(beatUs: Long, startUs: Long, inFrames: Int, outFrames: Int): Int {
        val mediaFrame = BeatClock.frameOffset(beatUs, startUs, sampleRate)
        return SpeedStretch.outputFrameForInput(mediaFrame, inFrames, outFrames)
    }

    private fun mixFrame(buffer: ByteBuffer, origin: Int, frame: Int, clickSample: Float) {
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                for (ch in 0 until channels) {
                    val idx = origin + (frame * channels + ch) * 4
                    if (idx + 4 > buffer.limit()) return
                    val music = buffer.getFloat(idx)
                    buffer.putFloat(idx, limit(music + clickSample))
                }
            }
            else -> {
                for (ch in 0 until channels) {
                    val idx = origin + (frame * channels + ch) * 2
                    if (idx + 2 > buffer.limit()) return
                    val music = buffer.getShort(idx) / Short.MAX_VALUE.toFloat()
                    val pcm = (limit(music + clickSample) * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    buffer.putShort(idx, pcm)
                }
            }
        }
    }

    private fun zero(out: ByteBuffer) {
        val pos = out.position()
        val lim = out.limit()
        for (i in pos until lim) out.put(i, 0)
    }

    private fun resolveEncoding(format: Format): Int {
        // Вход sink — всегда декодированный PCM. mime AAC/MPEG в 0.3.8
        // давал isPcm=false и клик никогда не мешался в буфер, который играли колонки.
        return if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
            C.ENCODING_PCM_FLOAT
        } else {
            C.ENCODING_PCM_16BIT
        }
    }

    companion object {
        const val DEFAULT_BPM = 120f
        const val MIN_BPM = 30f
        const val MAX_BPM = 240f
        const val CLICK_GAIN = 1f
        const val CLICK_VOLUME = 2.2f
        const val CLICK_PEAK = 0.92f
        const val CLICK_DURATION_SEC = 0.006

        fun limit(sample: Float): Float {
            if (sample in -1f..1f) return sample
            return tanh(sample.toDouble()).toFloat()
        }
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
