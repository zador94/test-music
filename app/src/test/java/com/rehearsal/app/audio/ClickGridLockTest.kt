package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Сетка клика — один periodSec и одна phaseSec на весь трек.
 * Не 60/округлённый BPM, не t=0 после TIME_UNSET/seek, не пересчёт на ходу.
 */
class ClickGridLockTest {
    @Test
    fun originalBpmChangeDoesNotRewriteLockedPeriod() {
        val period = 0.800117626775
        val engine = engine(120f)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 75f,
                beats = DoubleArray(0),
                downbeatOffset = 0.228,
                duration = 8.0,
                kickPhaseSec = 0.228,
                periodSec = period,
                phaseSec = 0.228,
            ),
        )
        val locked = BeatClock.intervalFromPeriodSec(period)
        assertEquals(locked, engine.clickIntervalUs())
        engine.originalBpm = 75f
        engine.originalBpm = 104.6f
        engine.originalBpm = 153.2f
        assertEquals("BPM rounding must not change click step", locked, engine.clickIntervalUs())
        assertEquals(TempoMath.secondsToMediaUs(0.228), engine.kickPhaseUs)
        assertEquals(TempoMath.secondsToMediaUs(0.228), engine.gridOriginUs)
    }

    @Test
    fun timeUnsetDoesNotPaintTheZeroGrid() {
        val engine = engine(120f)
        val dest = silence(ClickLayer.SAMPLE_RATE)
        ClickLayer.render(dest, engine, C.TIME_UNSET, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val times = clickTimes(dest, 0L)
        assertTrue(
            "TIME_UNSET after seek is mid-song, not t=0. clicks=$times",
            times.isEmpty(),
        )
    }

    @Test
    fun flushThenUnsetThenSeekKeepsSongGrid() {
        val engine = engine(120f)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16())
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        drain(pipe, silence(2048), 0L, sink)
        pipe.flush()
        sink.submitted.clear()
        drain(pipe, silence(ClickLayer.SAMPLE_RATE), C.TIME_UNSET, sink)
        val unsetOut = sink.lastSubmittedAsBuffer()
        val unsetTimes = clickTimes(unsetOut, 0L)
        assertTrue("UNSET after flush must not paint t=0, was $unsetTimes", unsetTimes.isEmpty())
        sink.submitted.clear()
        drain(pipe, silence(ClickLayer.SAMPLE_RATE), 1_500_000L, sink)
        val times = clickTimes(sink.lastSubmittedAsBuffer(), 1_500_000L)
        assertTrue("next click after seek is 1.5s, was $times", times.any { abs(it - 1_500_000L) < 8_000L })
        assertTrue("must not paint the t=0 grid after seek", times.none { abs(it) < 8_000L && it < 1_000_000L })
    }

    @Test
    fun nullAnalysisDoesNotClearLockedGrid() {
        val engine = engine(75f)
        val period = 0.571245460987
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 105f,
                beats = DoubleArray(0),
                downbeatOffset = 0.266,
                duration = 8.0,
                kickPhaseSec = 0.266,
                periodSec = period,
                phaseSec = 0.266,
            ),
        )
        val locked = engine.clickIntervalUs()
        val phase = engine.kickPhaseUs
        engine.setAnalysis(null)
        assertEquals(locked, engine.clickIntervalUs())
        assertEquals(phase, engine.kickPhaseUs)
    }

    private fun engine(originalBpm: Float): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(pcm16())
        engine.originalBpm = originalBpm
        engine.enabled = true
        return engine
    }

    private fun pcm16(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(ClickLayer.SAMPLE_RATE)
        .setChannelCount(2)
        .build()

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        buf.limit(frames * ClickLayer.BYTES_PER_FRAME)
        buf.position(0)
        return buf
    }

    private fun clickTimes(buffer: ByteBuffer, mediaStartUs: Long): List<Long> {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val threshold = 6000
        val minGap = ClickLayer.SAMPLE_RATE / 20
        val times = ArrayList<Long>()
        var i = 0
        while (i < frames) {
            val s = abs(shorts.get(i * 2).toInt())
            if (s > threshold) {
                times.add(mediaStartUs + BeatClock.sampleToMediaUs(i.toLong(), ClickLayer.SAMPLE_RATE))
                i += minGap
            } else {
                i++
            }
        }
        return times
    }

    private fun drain(
        pipe: MetronomePcmPipe,
        source: ByteBuffer,
        pts: Long,
        sink: IdentityAudioSink,
    ) {
        var guard = 0
        while (guard < 10_000) {
            if (pipe.handleBuffer(source, pts, 1, sink::handleBuffer)) return
            guard++
        }
    }
}
