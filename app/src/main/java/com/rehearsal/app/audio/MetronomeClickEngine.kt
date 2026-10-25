package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Клик в PCM по [presentationTimeUs].
 * Если есть `beats[]` из analysis.json — стреляем по ним (media time).
 * Иначе равная сетка [BeatClock] от t=0 по original BPM (старые треки без анализа).
 * Lookahead только внутри текущего PCM-буфера. Handler-таймера нет.
 */
class MetronomeClickEngine {
    @Volatile var enabled: Boolean = false
    @Volatile var originalBpm: Float = DEFAULT_BPM
    @Volatile private var beatTimesUs: LongArray = EmptyBeats

    var sampleRate: Int = 44100
        private set
    var channelCount: Int = 2
        private set
    var encoding: Int = C.ENCODING_PCM_16BIT
        private set

    private var bytesPerSample: Int = 2
    private var click: FloatArray = synthesizeClick(44100)

    fun configure(format: Format) {
        sampleRate = format.sampleRate.takeIf { it > 0 } ?: 44100
        channelCount = format.channelCount.takeIf { it > 0 } ?: 2
        encoding = resolveEncoding(format)
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        click = synthesizeClick(sampleRate)
    }

    fun onFlush() {
        // Сетка считается заново из presentationTimeUs — состояния клика нет.
    }

    fun isPcm(): Boolean = bytesPerSample > 0

    fun hasBeatTimes(): Boolean = beatTimesUs.isNotEmpty()

    fun clickDurationUs(): Long {
        if (sampleRate <= 0) return 0L
        return click.size.toLong() * 1_000_000L / sampleRate.toLong()
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

    fun mixInPlace(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (!enabled || bytesPerSample == 0 || presentationTimeUs == C.TIME_UNSET) return
        val bpm = originalBpm
        if (bpm < MIN_BPM) return

        val frameSizeBytes = bytesPerSample * channelCount
        if (frameSizeBytes <= 0) return
        val origin = buffer.position()
        val remaining = buffer.remaining()
        if (remaining < frameSizeBytes || remaining % frameSizeBytes != 0) return
        val frames = remaining / frameSizeBytes
        if (frames <= 0) return

        val startUs = presentationTimeUs.coerceAtLeast(0L)
        val clickUs = clickDurationUs().coerceAtLeast(1L)
        val beats = beatTimesUs
        if (beats.isNotEmpty()) {
            mixFromTimes(buffer, origin, frames, startUs, clickUs, beats)
        } else {
            mixEqualGrid(buffer, origin, frames, startUs, clickUs, BeatClock.intervalUs(bpm))
        }
    }

    private fun mixFromTimes(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        clickUs: Long,
        times: LongArray,
    ) {
        val durationUs = BeatClock.sampleToMediaUs(frames.toLong(), sampleRate)
        val endUs = startUs + durationUs
        var i = BeatClock.firstOverlappingIndex(times, startUs, clickUs)
        var guard = 0
        while (i < times.size && guard < MAX_BEATS_PER_BUFFER) {
            val beatUs = times[i]
            if (beatUs >= endUs) break
            paintClick(buffer, origin, frames, startUs, beatUs, beatIndex = i)
            i++
            guard++
        }
    }

    private fun mixEqualGrid(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        clickUs: Long,
        intervalUs: Long,
    ) {
        var beatUs = BeatClock.firstOverlappingBeatUs(startUs, intervalUs, clickUs)
        var guard = 0
        while (guard < MAX_BEATS_PER_BUFFER) {
            val startFrame = BeatClock.frameOffset(beatUs, startUs, sampleRate)
            if (startFrame >= frames) break
            val beatIndex = if (intervalUs > 0L) (beatUs / intervalUs).toInt() else 0
            paintClick(buffer, origin, frames, startUs, beatUs, beatIndex)
            val next = beatUs + intervalUs
            if (next <= beatUs) break
            beatUs = next
            guard++
        }
    }

    private fun paintClick(
        buffer: ByteBuffer,
        origin: Int,
        frames: Int,
        startUs: Long,
        beatUs: Long,
        beatIndex: Int,
    ) {
        val startFrame = BeatClock.frameOffset(beatUs, startUs, sampleRate)
        if (startFrame >= frames) return
        val clickOffset = if (startFrame < 0) -startFrame else 0
        val frame = if (startFrame < 0) 0 else startFrame
        if (clickOffset < click.size && frame < frames) {
            mixClick(
                buffer,
                origin,
                startFrame = frame,
                clickOffset = clickOffset,
                frames = frames,
                gain = clickGain(beatIndex),
            )
        }
    }

    private fun mixClick(
        buffer: ByteBuffer,
        origin: Int,
        startFrame: Int,
        clickOffset: Int,
        frames: Int,
        gain: Float,
    ) {
        var i = clickOffset
        var frame = startFrame
        while (i < click.size && frame < frames) {
            val sample = click[i] * gain
            when (encoding) {
                C.ENCODING_PCM_16BIT -> mixShortFrame(buffer, origin, frame, sample)
                C.ENCODING_PCM_FLOAT -> mixFloatFrame(buffer, origin, frame, sample)
            }
            i++
            frame++
        }
    }

    private fun mixShortFrame(buffer: ByteBuffer, origin: Int, frame: Int, clickSample: Float) {
        val add = (clickSample * Short.MAX_VALUE).toInt()
        for (ch in 0 until channelCount) {
            val idx = origin + (frame * channelCount + ch) * 2
            if (idx + 2 > buffer.limit()) return
            val mixed = buffer.getShort(idx).toInt() + add
            buffer.putShort(
                idx,
                mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
            )
        }
    }

    private fun mixFloatFrame(buffer: ByteBuffer, origin: Int, frame: Int, clickSample: Float) {
        for (ch in 0 until channelCount) {
            val idx = origin + (frame * channelCount + ch) * 4
            if (idx + 4 > buffer.limit()) return
            buffer.putFloat(idx, (buffer.getFloat(idx) + clickSample).coerceIn(-1f, 1f))
        }
    }

    private fun resolveEncoding(format: Format): Int {
        return when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> format.pcmEncoding
            else -> if (MimeTypes.AUDIO_RAW == format.sampleMimeType) C.ENCODING_PCM_16BIT else 0
        }
    }

    companion object {
        const val DEFAULT_BPM = 120f
        const val MIN_BPM = 30f
        const val MAX_BPM = 240f
        const val BEATS_PER_BAR = 4
        /** Как веб: клик ~2.2 поверх полного микса, иначе 0.78 тонет в барабанах. */
        const val DOWNBEAT_GAIN = 2.2f
        const val BEAT_GAIN = 1.65f
        private const val MAX_BEATS_PER_BUFFER = 10_000
        private val EmptyBeats = LongArray(0)

        fun clickGain(beatIndex: Int): Float =
            if (beatIndex % BEATS_PER_BAR == 0) DOWNBEAT_GAIN else BEAT_GAIN

        private fun synthesizeClick(sampleRate: Int): FloatArray {
            val n = (sampleRate * 0.018).toInt().coerceAtLeast(48)
            val f0 = 1850.0
            val f1 = 3100.0
            return FloatArray(n) { i ->
                val env = sin(PI * i / n)
                val t = i.toDouble() / sampleRate
                val s = (sin(2.0 * PI * f0 * t) + 0.45 * sin(2.0 * PI * f1 * t)) * env
                s.toFloat()
            }
        }
    }
}
