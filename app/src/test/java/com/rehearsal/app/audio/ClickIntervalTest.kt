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
    fun displayedTenthNotInteger105() {
        val ols = 104.63f
        val grid = PlaybackMath.committedOriginalBpm(ols)
        assertEquals("UI shows one decimal", 104.6f, grid, 0.01f)
        val engine = engine(grid)
        val intervals = clickIntervalsUs(engine, 0L, speed = 1f, seconds = 8)
        assertInterval(104.6f, intervals)
        val integerInterval = BeatClock.intervalUs(105f)
        val shownInterval = BeatClock.intervalUs(grid)
        assertTrue(
            "integer 105 would rush vs 104.6 (${integerInterval}us < ${shownInterval}us)",
            integerInterval < shownInterval,
        )
        intervals.forEach { actual ->
            assertTrue(
                "click $actual us must follow 104.6, not integer 105",
                abs(actual - integerInterval) > abs(actual - shownInterval),
            )
        }
    }

    @Test
    fun integerOnlyGridDriftsVersusFractional() {
        val trueBpm = 104.6f
        val trueInterval = BeatClock.intervalUs(trueBpm)
        val intInterval = BeatClock.intervalUs(105f)
        val beats = 40L
        val drift = abs(beats * trueInterval - beats * intInterval)
        val hopUs = (TempoDetector.HOP * 1_000_000L) / 44100L
        assertTrue("105 integer grid drifts vs 104.6 after 40 beats, drift=$drift", drift > hopUs)
        val engine = engine(trueBpm)
        val intervals = clickIntervalsUs(engine, 0L, speed = 1f, seconds = 10)
        assertInterval(trueBpm, intervals)
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
        assertEquals(102.0, grid.toDouble(), 0.15)
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
        assertInterval(grid, intervals)
    }

    private fun assertInterval(displayedBpm: Int, intervalsUs: List<Long>) =
        assertInterval(displayedBpm.toFloat(), intervalsUs)

    private fun assertInterval(displayedBpm: Float, intervalsUs: List<Long>) {
        assertTrue("need several clicks, was ${intervalsUs.size}", intervalsUs.size >= 6)
        val expected = BeatClock.intervalUs(displayedBpm)
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
     * Chunks through the mix pipe at absolute song PTS (no second-track queue).
     * [headStuck] is ignored: there is no AudioTrack playback head on the click path.
     */
    private fun followPlayheadIntervalsUs(
        engine: MetronomeClickEngine,
        displayedBpm: Int,
        seconds: Int,
        @Suppress("UNUSED_PARAMETER") headStuck: Boolean,
    ): List<Long> {
        val chunkFrames = 1024
        val totalFrames = ClickLayer.SAMPLE_RATE * seconds
        val concat = ByteBuffer.allocateDirect(totalFrames * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(ClickLayer.SAMPLE_RATE)
                .setChannelCount(2)
                .build(),
        )
        var offset = 0
        while (offset < totalFrames) {
            val n = minOf(chunkFrames, totalFrames - offset)
            val pts = BeatClock.sampleToMediaUs(offset.toLong(), ClickLayer.SAMPLE_RATE)
            val chunk = silence(n)
            val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
            var guard = 0
            while (guard < 10_000) {
                if (pipe.handleBuffer(chunk, pts, 1, sink::handleBuffer)) break
                guard++
            }
            concat.put(sink.lastSubmittedAsBuffer())
            offset += n
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
