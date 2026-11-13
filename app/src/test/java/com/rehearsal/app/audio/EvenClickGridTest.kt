package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Клик стучит ровно: шаг = periodSec разбора, фаза одна.
 * Соседние удары и отдельный сдвиг песни сетку не двигают.
 */
class EvenClickGridTest {
    @Test
    fun irregularBeatsDoNotMoveTheClick() {
        val engine = engine(105.0f)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 105.2f,
                beats = doubleArrayOf(0.115, 0.40, 0.91, 1.20),
                downbeatOffset = 0.0,
                duration = 8.0,
                kickPhaseSec = 0.115,
                periodSec = 0.55,
                phaseSec = 0.115,
            ),
        )
        val interval = BeatClock.intervalFromPeriodSec(0.55)
        assertEquals(interval, engine.clickIntervalUs())
        assertTrue(interval != BeatClock.intervalUs(105.0f))
        assertEquals(0, engine.beatTimesUs.size)
        assertEquals(115_000L, engine.kickPhaseUs)
        val times = clickTimes(engine, seconds = 4)
        assertTrue("need clicks, was $times", times.size >= 4)
        times.zipWithNext { a, b ->
            assertEquals(interval.toDouble(), (b - a).toDouble(), 2_000.0)
        }
        assertTrue(
            "first click is the one phase, not the neighbor at 400ms, was $times",
            times.any { abs(it - 115_000L) < 8_000L },
        )
        assertTrue(times.none { abs(it - 400_000L) < 8_000L })
    }

    @Test
    fun editingBpmChangesTheStepAndKeepsThePhase() {
        val engine = engine(105.0f)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 105f,
                beats = DoubleArray(0),
                downbeatOffset = 0.0,
                duration = 8.0,
                phaseSec = 0.2,
                kickPhaseSec = 0.2,
            ),
        )
        val phase = engine.kickPhaseUs
        engine.originalBpm = 100f
        assertEquals(BeatClock.intervalUs(100f), engine.clickIntervalUs())
        assertEquals(phase, engine.kickPhaseUs)
        val interval = engine.clickIntervalUs()
        val origin = engine.gridOriginUs
        val dist = (origin - phase).mod(interval)
        assertEquals(0L, minOf(dist, interval - dist))
    }

    @Test
    fun alignUsesOnePhaseAndAnEvenStep() {
        val bpm = 105.0
        val kickAt = 0.115
        val pcm = lowBandKicks(bpm, kickAt)
        val aligned = ClickGrid.fromPcm(pcm, hintBpm = bpm.toFloat())
        assertEquals(105.0, aligned.bpm.toDouble(), 0.5)
        val period = aligned.periodSec
        assertEquals(60.0 / aligned.bpm, period, 2e-3)
        assertTrue(abs(60.0 / period - aligned.bpm) <= 0.015)
        assertTrue(aligned.beats.size >= 8)
        for (i in 1 until aligned.beats.size) {
            assertEquals(period, aligned.beats[i] - aligned.beats[i - 1], 1e-3)
        }
        val err = wrapped(aligned.phaseSec - kickAt, period)
        assertTrue(
            "phase must sit on the kick, err=${err * 1000}ms phase=${aligned.phaseSec}",
            abs(err) < 0.040,
        )
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
        assertEquals(0.0, aligned.downbeatOffset, 0.0)
    }

    @Test
    fun musicStaysAudibleWithTheClickOn() {
        val engine = engine(105f)
        engine.setAnalysis(TrackAnalysis.grid(105f, phaseSec = 0.05))
        val frames = ClickLayer.SAMPLE_RATE
        val src = sine(frames)
        val before = shortsOf(src)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(ClickLayer.SAMPLE_RATE)
                .setChannelCount(2)
                .build(),
        )
        val played = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        val accepted = pipe.handleBuffer(src, 0L, 1) { buffer, _, _ ->
            val dup = buffer.duplicate().order(buffer.order())
            played.clear()
            played.put(dup)
            played.flip()
            true
        }
        assertTrue(accepted)
        val out = played.duplicate().order(played.order()).asShortBuffer()
        var loud = 0
        var unchanged = 0
        val n = minOf(before.size, out.limit())
        for (i in 0 until n) {
            val sample = out.get(i).toInt()
            if (abs(sample) > 1500) loud++
            if (sample == before[i].toInt()) unchanged++
        }
        assertTrue("music must stay loud, loud=$loud of $n", loud > n * 8 / 10)
        assertTrue("click must not replace the song, unchanged=$unchanged of $n", unchanged > n / 2)
    }

    @Test
    fun absurdTempoDoesNotEraseMusic() {
        val engine = engine(105f)
        engine.originalBpm = 1_000_000f
        assertEquals(BeatClock.intervalUs(MetronomeClickEngine.MAX_BPM), engine.clickIntervalUs())
        val frames = ClickLayer.SAMPLE_RATE
        val src = sine(frames)
        val before = shortsOf(src)
        engine.mixInto(src, 0L, 1f, frames, frames)
        val out = src.duplicate().order(src.order()).asShortBuffer()
        var same = 0
        for (i in before.indices) {
            if (out.get(i).toInt() == before[i].toInt()) same++
        }
        assertTrue("song erased, same=$same of ${before.size}", same > before.size * 8 / 10)
    }

    private fun lowBandKicks(bpm: Double, phaseSec: Double): StereoPcm {
        val sr = 44100
        val period = 60.0 / bpm
        val duration = phaseSec + 8 * 4 * period + 0.5
        val n = (duration * sr).toInt()
        val left = FloatArray(n)
        val attack = (0.012 * sr).toInt()
        var t = phaseSec
        while (t < duration - 0.05) {
            val i0 = (t * sr).toInt()
            for (k in 0 until attack) {
                val i = i0 + k
                if (i !in left.indices) continue
                val env = 1f - k.toFloat() / attack
                left[i] = env * sin(2.0 * PI * 70.0 * k / sr).toFloat()
            }
            t += period
        }
        return StereoPcm(sr, left, left.copyOf())
    }

    private fun wrapped(delta: Double, period: Double): Double {
        var m = delta % period
        if (m < 0.0) m += period
        return if (m <= period - m) m else m - period
    }

    private fun engine(bpm: Float): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(ClickLayer.SAMPLE_RATE)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = bpm
        engine.enabled = true
        return engine
    }

    private fun sine(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * 220.0 * i / ClickLayer.SAMPLE_RATE) * 0.25 * Short.MAX_VALUE)
                .toInt()
                .toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        return buf
    }

    private fun shortsOf(buffer: ByteBuffer): ShortArray {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val out = ShortArray(shorts.remaining())
        shorts.get(out)
        return out
    }

    private fun clickTimes(engine: MetronomeClickEngine, seconds: Int): List<Long> {
        val frames = ClickLayer.SAMPLE_RATE * seconds
        val dest = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        dest.limit(dest.capacity())
        dest.position(0)
        ClickLayer.render(dest, engine, 0L, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val shorts = dest.asShortBuffer()
        val n = shorts.limit() / 2
        val times = ArrayList<Long>()
        var i = 0
        while (i < n) {
            if (abs(shorts.get(i * 2).toInt()) > 6000) {
                times.add(BeatClock.sampleToMediaUs(i.toLong(), ClickLayer.SAMPLE_RATE))
                i += ClickLayer.SAMPLE_RATE / 20
            } else {
                i++
            }
        }
        return times
    }
}
