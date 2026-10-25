package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin

/**
 * Слой клика (Web Audio node): [renderLayer] в отдельный PCM, не в stem mix.
 * `beats[]` в media time; без analysis — равная сетка [BeatClock].
 * Lookahead задаёт [ClickLayer] (0.12s wall × speed). Handler-таймера нет.
 */
class MetronomeClickEngine {
    @Volatile var enabled: Boolean = false
    @Volatile var originalBpm: Float = DEFAULT_BPM
    @Volatile private var beatTimesUs: LongArray = EmptyBeats

    var sampleRate: Int = ClickLayer.SAMPLE_RATE
        private set
    var channelCount: Int = ClickLayer.CHANNELS
        private set
    var encoding: Int = C.ENCODING_PCM_16BIT
        private set

    private var bytesPerSample: Int = 2
    private val downbeat: FloatArray = synthesizeDownbeat(ClickLayer.SAMPLE_RATE)
    private val beatClick: FloatArray = synthesizeBeat(ClickLayer.SAMPLE_RATE)

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

    fun hasBeatTimes(): Boolean = beatTimesUs.isNotEmpty()

    fun clickDurationUs(): Long {
        val n = maxOf(downbeat.size, beatClick.size)
        return n.toLong() * 1_000_000L / ClickLayer.SAMPLE_RATE
    }

    fun setAnalysis(analysis: TrackAnalysis?) {
        if (analysis == null || analysis.beats.isEmpty()) {
            beatTimesUs = EmptyBeats
            return
        }
        beatTimesUs = analysis.beatTimesUs()
    }

    fun setBeatTimesUs(timesUs: LongArray) {
        if (timesUs.isEmpty()) {
            beatTimesUs = EmptyBeats
            return
        }
        val copy = timesUs.copyOf()
        copy.sort()
        beatTimesUs = copy
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
        val beats = beatTimesUs
        var last = fromExclusiveUs
        if (beats.isNotEmpty()) {
            last = paintFromTimes(out, origin, frames, startUs, endUs, clickMediaUs, speedSafe, beats, fromExclusiveUs)
        } else {
            last = paintEqualGrid(
                out,
                origin,
                frames,
                startUs,
                endUs,
                clickMediaUs,
                speedSafe,
                BeatClock.intervalUs(bpm),
                fromExclusiveUs,
            )
        }
        out.position(origin)
        out.limit(origin + frames * ClickLayer.BYTES_PER_FRAME)
        return last
    }

    private fun paintFromTimes(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        endUs: Long,
        clickMediaUs: Long,
        speed: Float,
        times: LongArray,
        fromExclusiveUs: Long,
    ): Long {
        var i = BeatClock.firstOverlappingIndex(times, startUs, clickMediaUs)
        var last = fromExclusiveUs
        var guard = 0
        while (i < times.size && guard < MAX_BEATS_PER_BUFFER) {
            val beatUs = times[i]
            if (beatUs >= endUs) break
            if (beatUs > fromExclusiveUs) {
                paintClick(buffer, origin, frames, startUs, beatUs, beatIndex = i, speed = speed)
                last = maxOf(last, beatUs)
            }
            i++
            guard++
        }
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
        fromExclusiveUs: Long,
    ): Long {
        var beatUs = BeatClock.firstOverlappingBeatUs(startUs, intervalUs, clickMediaUs)
        var last = fromExclusiveUs
        var guard = 0
        while (guard < MAX_BEATS_PER_BUFFER) {
            if (beatUs >= endUs) break
            val beatIndex = if (intervalUs > 0L) (beatUs / intervalUs).toInt() else 0
            val startFrame = wallFrame(beatUs, startUs, speed)
            if (startFrame >= frames && beatUs > startUs) break
            if (beatUs > fromExclusiveUs) {
                paintClick(buffer, origin, frames, startUs, beatUs, beatIndex, speed)
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
        beatIndex: Int,
        speed: Float,
    ) {
        val startFrame = wallFrame(beatUs, startUs, speed)
        val wave = if (beatIndex % BEATS_PER_BAR == 0) downbeat else beatClick
        val clickOffset = if (startFrame < 0) -startFrame else 0
        val frame = if (startFrame < 0) 0 else startFrame
        if (clickOffset >= wave.size || frame >= frames) return
        val gain = clickGain(beatIndex)
        var i = clickOffset
        var f = frame
        while (i < wave.size && f < frames) {
            writeShortFrame(buffer, origin, f, wave[i] * gain)
            i++
            f++
        }
    }

    private fun wallFrame(beatUs: Long, startUs: Long, speed: Float): Int {
        // Sample-domain, not (beatUs - startUs) then convert: chunked 512-frame
        // buffers must land on the same frame as a single whole-buffer render.
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
        const val BEATS_PER_BAR = 4
        /** Как веб: master gain ~2.2 на «раз», иначе клик тонет в барабанах. */
        const val DOWNBEAT_GAIN = 2.2f
        const val BEAT_GAIN = 1.65f
        private const val MAX_BEATS_PER_BUFFER = 10_000
        private val EmptyBeats = LongArray(0)

        fun clickGain(beatIndex: Int): Float =
            if (beatIndex % BEATS_PER_BAR == 0) DOWNBEAT_GAIN else BEAT_GAIN

        /**
         * Пик волны = 1/DOWNBEAT_GAIN, чтобы «раз» × 2.2 = full-scale,
         * остальные × 1.65 тише (~0.75) и не упирались в тот же клип.
         */
        private fun peakNormalize(wave: FloatArray, peak: Float): FloatArray {
            var max = 1e-9f
            for (s in wave) max = maxOf(max, abs(s))
            val g = peak / max
            return FloatArray(wave.size) { i -> wave[i] * g }
        }

        /** Доля «раз»: длиннее и ниже. */
        fun synthesizeDownbeat(sampleRate: Int): FloatArray {
            val n = (sampleRate * 0.028).toInt().coerceAtLeast(64)
            val f0 = 980.0
            val f1 = 1470.0
            val raw = FloatArray(n) { i ->
                val env = sin(PI * i / n)
                val t = i.toDouble() / sampleRate
                val s = (sin(2.0 * PI * f0 * t) + 0.35 * sin(2.0 * PI * f1 * t)) * env
                s.toFloat()
            }
            return peakNormalize(raw, 1f / DOWNBEAT_GAIN)
        }

        /** Остальные доли: короче и выше. */
        fun synthesizeBeat(sampleRate: Int): FloatArray {
            val n = (sampleRate * 0.012).toInt().coerceAtLeast(48)
            val f0 = 2450.0
            val f1 = 3200.0
            val raw = FloatArray(n) { i ->
                val env = sin(PI * i / n)
                val t = i.toDouble() / sampleRate
                val s = (sin(2.0 * PI * f0 * t) + 0.4 * sin(2.0 * PI * f1 * t)) * env
                s.toFloat()
            }
            return peakNormalize(raw, 1f / DOWNBEAT_GAIN)
        }
    }
}
