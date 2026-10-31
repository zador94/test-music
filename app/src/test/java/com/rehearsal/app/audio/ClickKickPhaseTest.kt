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
 * 0.3.42: клик из `beats[]`, не из одной `kickPhaseSec`.
 * Пустой `beats[]` — запасная решётка (см. ClickPeriodGridTest).
 */
class ClickKickPhaseTest {
    @Test
    fun clicksFollowBeatsNotSingleKickPhase() {
        val bpm = 120
        val engine = engine(bpm.toFloat())
        engine.setAnalysis(
            TrackAnalysis(
                bpm = bpm.toFloat(),
                beats = doubleArrayOf(0.0, 0.25, 0.5, 0.80, 1.0),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.08,
                periodSec = 0.5,
                phaseSec = 0.08,
            ),
        )
        assertEquals(5, engine.beatTimesUs.size)
        val times = clickMediaTimesUs(engine, 0L, seconds = 2)
        assertTrue("need clicks, was $times", times.size >= 4)
        val expected = longArrayOf(0L, 250_000L, 500_000L, 800_000L, 1_000_000L)
        for (want in expected) {
            assertTrue("click missing at beat $want, was $times", times.any { abs(it - want) < 8_000L })
        }
        assertTrue(
            "must not sit on kickPhase 80ms when beats[] is present, was $times",
            times.none { abs(it - 80_000L) < 8_000L },
        )
    }

    @Test
    fun seekAndEnableStayOnStoredBeatsNotEnableTime() {
        val bpm = 120
        val engine = engine(bpm.toFloat())
        engine.setAnalysis(
            TrackAnalysis(
                bpm = bpm.toFloat(),
                beats = doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.08,
                periodSec = 0.5,
                phaseSec = 0.08,
            ),
        )
        val enableUs = 1_200_000L
        val times = clickMediaTimesUs(engine, enableUs, seconds = 2)
        assertTrue("need clicks after enable, was $times", times.size >= 2)
        assertTrue(
            "next stored beat after 1.2s is 1.5s, was $times",
            times.any { abs(it - 1_500_000L) < 8_000L },
        )
        assertFalse(
            "must not start a new grid at enable time 1.2s",
            times.any { abs(it - enableUs) < 8_000L },
        )
        assertTrue(
            "must not paint kickPhase 80ms as if it were the grid",
            times.none { abs(it - 80_000L) < 8_000L },
        )
        val overlapping = BeatClock.firstOverlappingBeat(
            engine.beatTimesUs,
            enableUs,
            engine.clickDurationUs(),
        )
        assertEquals(1_500_000L, overlapping)
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
