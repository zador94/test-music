package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Device: click FIRST then kick, then drift. Grid phase is kickPhaseSec from drums,
 * not beats[] and not a blind +80 ms pad.
 */
class ClickKickPhaseTest {
    @Test
    fun kickPhaseFromAnalysisNotBeatsArray() {
        val bpm = 120
        val engine = engine(bpm.toFloat())
        engine.setAnalysis(
            TrackAnalysis(
                bpm = bpm.toFloat(),
                beats = doubleArrayOf(0.0, 0.5, 1.0, 1.5),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.08,
            ),
        )
        val origin = 80_000L
        assertEquals(origin, engine.gridOriginUs)
        val times = clickMediaTimesUs(engine, 0L, seconds = 2)
        assertTrue("need clicks, was $times", times.size >= 3)
        times.forEach { t ->
            val dist = (t - engine.gridOriginUs).mod(BeatClock.intervalUs(bpm.toFloat()))
            val wrap = BeatClock.intervalUs(bpm.toFloat()) - dist
            assertTrue(
                "click $t must sit on kickPhase 80ms, not beats[] t=0",
                minOf(dist, wrap) < 8_000L,
            )
        }
        assertTrue("must not click at file t=0 when kick phase is 80ms, was $times", times.none { it < 40_000L })
        assertTrue("click on kick phase, at $origin", times.any { abs(it - origin) < 8_000L })
        val intervals = times.zipWithNext { a, b -> b - a }
        val expected = BeatClock.intervalUs(bpm.toFloat())
        intervals.forEachIndexed { i, actual ->
            assertEquals("interval[$i] still 60/$bpm", expected.toDouble(), actual.toDouble(), 8_000.0)
        }
    }

    @Test
    fun seekAndEnableStayOnKickGridNotEnableTime() {
        val bpm = 120
        val engine = engine(bpm.toFloat())
        engine.setAnalysis(
            TrackAnalysis(
                bpm = bpm.toFloat(),
                beats = doubleArrayOf(0.0, 0.5, 1.0),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.08,
            ),
        )
        val enableUs = 1_200_000L
        val times = clickMediaTimesUs(engine, enableUs, seconds = 2)
        val interval = BeatClock.intervalUs(bpm.toFloat())
        assertTrue("need clicks after enable, was $times", times.size >= 2)
        val origin = 80_000L
        times.forEach { t ->
            val dist = (t - origin).mod(interval)
            val wrap = interval - dist
            assertTrue("enable/seek must stay on kick grid, click $t", minOf(dist, wrap) < 8_000L)
        }
        assertFalse(
            "must not start a new grid at enable time 1.2s",
            times.any { abs(it - enableUs) < 8_000L },
        )
        val next = BeatClock.beatAtOrAfter(enableUs, interval, origin)
        assertTrue(
            "next kick beat after 1.2s is $next, was $times",
            times.any { abs(it - next) < 8_000L },
        )
    }

    @Test
    fun clickLeadOffsetMovesOriginLater() {
        val interval = BeatClock.intervalUs(102f)
        val click = 0L
        val kick = 90_000L
        val corrected = BeatClock.alignOriginLater(0L, click, kick, interval)
        assertEquals(kick, corrected)
        assertTrue(corrected > click)
        assertTrue(
            "wrong wrap would put origin near the end of the beat",
            corrected < interval / 2,
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

    private fun clickMediaTimesUs(
        engine: MetronomeClickEngine,
        mediaStartUs: Long,
        seconds: Int,
    ): List<Long> {
        val dest = ByteBuffer.allocateDirect(ClickLayer.SAMPLE_RATE * seconds * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        dest.limit(dest.capacity())
        dest.position(0)
        ClickLayer.render(dest, engine, mediaStartUs, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val shorts = dest.duplicate().order(dest.order()).asShortBuffer()
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
}
