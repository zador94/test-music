package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Enable/seek mid-song: grid stays 60/bpm from song t=0, not from enable/seek time.
 * No second AudioTrack cursor — PTS of the mix buffer is song time.
 */
class ClickResyncTest {
    @Test
    fun enableAtTGreaterThanZeroStaysOnSongGridFromZero() {
        val bpm = 120
        val enableUs = 1_200_000L
        val engine = engine(bpm.toFloat())
        val times = clickMediaTimesUs(engine, enableUs, seconds = 2)
        assertOnSongGrid(times, bpm)
        assertTrue(
            "mid-song enable must hit 1.5s (next quarter from t=0), was $times",
            times.any { abs(it - 1_500_000L) < 8_000L },
        )
        assertTrue(
            "must not start a new grid at enable time 1.2s",
            times.none { abs(it - enableUs) < 8_000L },
        )
    }

    @Test
    fun seekWhileRunningStaysOnSongGridFromZero() {
        val bpm = 120
        val engine = engine(bpm.toFloat())
        val before = clickMediaTimesUs(engine, 0L, seconds = 1)
        val afterSeek = clickMediaTimesUs(engine, 2_000_000L, seconds = 1)
        assertOnSongGrid(before, bpm)
        assertOnSongGrid(afterSeek, bpm)
        assertTrue(
            "after seek to 2s the next quarters are 2.0 / 2.5, was $afterSeek",
            afterSeek.any { abs(it - 2_000_000L) < 8_000L } ||
                afterSeek.any { abs(it - 2_500_000L) < 8_000L },
        )
    }

    @Test
    fun pipeFlushThenNewPtsUsesAbsoluteBeat() {
        val engine = engine(120f)
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16())
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        drain(pipe, silence(2048), 0L, sink)
        pipe.flush()
        sink.submitted.clear()
        drain(pipe, silence(ClickLayer.SAMPLE_RATE), 1_500_000L, sink)
        val times = clickMediaTimesFromBuffer(sink.lastSubmittedAsBuffer(), 1_500_000L)
        assertTrue("next click after seek is 1.5s, was $times", times.any { abs(it - 1_500_000L) < 8_000L })
        assertTrue("must not paint the t=0 grid after flush", times.none { abs(it) < 8_000L && it < 1_000_000L })
    }

    @Test
    fun negativePtsMustNotPaintTheZeroGrid() {
        val engine = engine(120f)
        val dest = silence(ClickLayer.SAMPLE_RATE)
        ClickLayer.render(dest, engine, Long.MIN_VALUE, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val times = clickMediaTimesFromBuffer(dest, mediaStartUs = 0L)
        assertTrue(
            "negative PTS (seek race) must not suddenly click as if t=0, was $times",
            times.isEmpty(),
        )
    }

    private fun assertOnSongGrid(timesUs: List<Long>, bpm: Int) {
        assertTrue("need clicks, was $timesUs", timesUs.size >= 2)
        val interval = BeatClock.intervalUs(bpm.toFloat())
        timesUs.forEach { t ->
            val next = BeatClock.beatAtOrAfter(t, interval, originUs = 0L)
            val prev = if (next >= interval) next - interval else 0L
            val dist = minOf(abs(t - next), abs(t - prev))
            assertTrue(
                "click at $t us is off the 60/$bpm grid from t=0 (interval $interval)",
                dist < 8_000L,
            )
        }
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

    private fun clickMediaTimesUs(
        engine: MetronomeClickEngine,
        mediaStartUs: Long,
        seconds: Int,
    ): List<Long> {
        val dest = silence(ClickLayer.SAMPLE_RATE * seconds)
        ClickLayer.render(dest, engine, mediaStartUs, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        return clickMediaTimesFromBuffer(dest, mediaStartUs)
    }

    private fun clickMediaTimesFromBuffer(buffer: ByteBuffer, mediaStartUs: Long): List<Long> {
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

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        buf.limit(frames * ClickLayer.BYTES_PER_FRAME)
        buf.position(0)
        return buf
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
