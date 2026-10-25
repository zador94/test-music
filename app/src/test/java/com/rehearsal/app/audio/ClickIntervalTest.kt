package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.player.PlaybackMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Device report (0.3.13, «Фонари»): UI 102 BPM, click rushes.
 * At speed 1 the wall interval must be 60/displayedBpm, not the unrounded OLS float
 * and not a playhead+queue cursor that shortens the period.
 */
class ClickIntervalTest {
    @Test
    fun wallIntervalAtSpeedOneEqualsSixtyOverDisplayedBpm() {
        val displayed = 102
        val engine = engine(displayed.toFloat())
        val intervals = clickIntervalsUs(engine, mediaStartUs = 0L, speed = 1f, seconds = 8)
        assertInterval(displayed, intervals)
    }

    @Test
    fun displayedIntegerNotUnroundedOlsFloat() {
        val ols = 102.8f
        val displayed = ols.toInt()
        assertEquals("UI shows toInt()", 102, displayed)
        val grid = PlaybackMath.committedOriginalBpm(ols)
        assertEquals(
            "grid BPM must be the integer on screen, not OLS 102.8",
            displayed.toFloat(),
            grid,
            0f,
        )
        val engine = engine(grid)
        val intervals = clickIntervalsUs(engine, 0L, speed = 1f, seconds = 8)
        assertInterval(displayed, intervals)
        val olsInterval = BeatClock.intervalUs(ols)
        val shownInterval = BeatClock.intervalUs(displayed.toFloat())
        assertTrue(
            "OLS 102.8 would rush vs displayed 102 (${olsInterval}us < ${shownInterval}us)",
            olsInterval < shownInterval,
        )
        intervals.forEach { actual ->
            assertTrue(
                "click $actual us is OLS-fast, not 60/$displayed",
                abs(actual - olsInterval) > abs(actual - shownInterval),
            )
        }
    }

    @Test
    fun playheadPlusQueuedChunksKeepDisplayedIntervalEvenIfHeadSticks() {
        val displayed = 102
        val engine = engine(displayed.toFloat())
        val intervals = followPlayheadIntervalsUs(
            engine = engine,
            displayedBpm = displayed,
            seconds = 8,
            headStuck = true,
        )
        assertInterval(displayed, intervals)
    }

    @Test
    fun followPlayheadWithMovingHeadKeepsDisplayedInterval() {
        val displayed = 102
        val engine = engine(displayed.toFloat())
        val intervals = followPlayheadIntervalsUs(
            engine = engine,
            displayedBpm = displayed,
            seconds = 8,
            headStuck = false,
        )
        assertInterval(displayed, intervals)
    }

    @Test
    fun fonariBeatsOlsMustNotRushDisplayed102() {
        val displayed = 102
        val period = 60.0 / displayed
        // Hop-512 snap like TempoDetector: intervals land on 50 or 51 hops (~101.3 / 103.3).
        val hopSec = TempoDetector.HOP / 44100.0
        val beats = DoubleArray(64) { i ->
            val raw = i * period
            val hops = kotlin.math.round(raw / hopSec)
            hops * hopSec
        }
        val ols = TempoMath.bpmFromBeats(beats, hintBpm = 100.0)!!
        val grid = PlaybackMath.committedOriginalBpm(ols.toFloat())
        assertEquals(displayed.toFloat(), grid, 0f)
        val engine = engine(grid)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = ols.toFloat(),
                beats = beats,
                downbeatOffset = beats[0],
                duration = beats.last() + period,
            ),
        )
        val intervals = clickIntervalsUs(engine, 0L, speed = 1f, seconds = 6)
        assertInterval(displayed, intervals)
    }

    private fun assertInterval(displayedBpm: Int, intervalsUs: List<Long>) {
        assertTrue("need several clicks, was ${intervalsUs.size}", intervalsUs.size >= 6)
        val expected = BeatClock.intervalUs(displayedBpm.toFloat())
        intervalsUs.forEachIndexed { i, actual ->
            assertEquals(
                "click interval[$i]=$actual us, expected 60/$displayedBpm = $expected us",
                expected.toDouble(),
                actual.toDouble(),
                8_000.0,
            )
        }
        val median = intervalsUs.sorted()[intervalsUs.size / 2]
        val impliedBpm = 60_000_000.0 / median
        assertEquals(
            "median interval implies $impliedBpm BPM, UI is $displayedBpm",
            displayedBpm.toDouble(),
            impliedBpm,
            0.35,
        )
    }

    private fun engine(originalBpm: Float): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(ClickLayer.SAMPLE_RATE)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = originalBpm
        engine.enabled = true
        return engine
    }

    private fun clickIntervalsUs(
        engine: MetronomeClickEngine,
        mediaStartUs: Long,
        speed: Float,
        seconds: Int,
    ): List<Long> {
        val frames = ClickLayer.SAMPLE_RATE * seconds
        val dest = silence(frames)
        ClickLayer.render(dest, engine, mediaStartUs, speed)
        dest.position(0)
        dest.limit(dest.capacity())
        return onsetIntervalsUs(dest)
    }

    /**
     * Same loop as [ClickTrack]: each wall chunk is playhead + queued latency.
     */
    private fun followPlayheadIntervalsUs(
        engine: MetronomeClickEngine,
        displayedBpm: Int,
        seconds: Int,
        headStuck: Boolean,
    ): List<Long> {
        val chunkFrames = ClickLayer.chunkFrames()
        val totalFrames = ClickLayer.SAMPLE_RATE * seconds
        val concat = ByteBuffer.allocateDirect(totalFrames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        var framesWritten = 0L
        var cursorUs = Long.MIN_VALUE
        var lastClickUs = Long.MIN_VALUE
        var wallUs = 0L
        val origin = ClickClock.Origin(mediaUs = 0L, wallNs = 0L, speed = 1f, playing = true)
        val bufferLatencyMs = ClickClock.outputLatencyMs(chunkFrames * 2, ClickLayer.SAMPLE_RATE)
        while (concat.remaining() >= ClickLayer.chunkBytes()) {
            val snap = ClickClock.snapshot(origin, nowNs = wallUs * 1_000L)
            val head = if (headStuck) 0 else framesWritten.toInt()
            val queuedMs = ClickClock.queuedLatencyMs(framesWritten, head, ClickLayer.SAMPLE_RATE)
            val targetUs = ClickClock.scheduledMediaUs(
                snap.positionUs,
                snap.speed,
                ClickClock.writeLatencyMs(queuedMs, bufferLatencyMs),
            )
            cursorUs = ClickClock.nextWriteMediaUs(
                cursorUs,
                targetUs,
                ClickLayer.mediaAdvanceUs(snap.speed),
            )
            val mediaStartUs = cursorUs
            val chunk = ClickLayer.allocateChunk()
            chunk.clear()
            chunk.limit(ClickLayer.chunkBytes())
            lastClickUs = ClickLayer.render(chunk, engine, mediaStartUs, snap.speed, lastClickUs)
            chunk.position(0)
            chunk.limit(ClickLayer.chunkBytes())
            concat.put(chunk)
            framesWritten += chunkFrames
            wallUs += BeatClock.sampleToMediaUs(chunkFrames.toLong(), ClickLayer.SAMPLE_RATE)
        }
        concat.flip()
        val intervals = onsetIntervalsUs(concat)
        assertTrue("displayed $displayedBpm must still click", intervals.isNotEmpty())
        return intervals
    }

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        buf.limit(frames * ClickLayer.BYTES_PER_FRAME)
        buf.position(0)
        return buf
    }

    private fun onsetIntervalsUs(buffer: ByteBuffer): List<Long> {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val threshold = 6000
        val minGap = ClickLayer.SAMPLE_RATE / 20
        val onsets = ArrayList<Long>()
        var i = 0
        while (i < frames) {
            val s = abs(shorts.get(i * 2).toInt())
            if (s > threshold) {
                onsets.add(BeatClock.sampleToMediaUs(i.toLong(), ClickLayer.SAMPLE_RATE))
                i += minGap
            } else {
                i++
            }
        }
        return onsets.zipWithNext { a, b -> b - a }
    }
}
