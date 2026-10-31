package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.player.PlaybackMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Сетка клика = periodSec/phaseSec tempoOfTrack, не 60/BPM после 0.1 и не складка 150→75.
 */
class ClickPeriodGridTest {
    @Test
    fun analyzeJsonStoresTrackTempoPeriodAndPhase() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 104.6, bars = 8, beatsPerBar = 4)
        val tracked = TrackTempo.tempoOfTrack(pcm)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(tracked.periodSec, analysis.periodSec, 0.0)
        assertEquals(tracked.phaseSec, analysis.phaseSec, 0.0)
        val json = analysis.toJson()
        assertTrue(json.contains("\"periodSec\""))
        assertTrue(json.contains("\"phaseSec\""))
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(tracked.periodSec, parsed.periodSec, 1e-12)
        assertEquals(tracked.phaseSec, parsed.phaseSec, 1e-12)
        assertEquals(parsed.phaseSec, parsed.kickPhaseSec, 1e-12)
        assertEquals(
            BeatClock.intervalFromPeriodSec(tracked.periodSec),
            BeatClock.intervalFromPeriodSec(parsed.periodSec),
        )
        assertEquals(
            PlaybackMath.roundToTenth(tracked.bpm.toFloat()),
            parsed.bpm,
            1e-3f,
        )
        val roundedPeriod = 60.0 / parsed.bpm
        assertTrue(
            "click period must be tempoOfTrack, not 60/rounded bpm " +
                "(period=${parsed.periodSec} rounded=$roundedPeriod bpm=${parsed.bpm})",
            abs(parsed.periodSec - roundedPeriod) > 1e-12 ||
                abs(tracked.periodSec - roundedPeriod) < 1e-12,
        )
    }

    @Test
    fun oldJsonWithoutPeriodSecDoesNotInventAPeriod() {
        val json = """
            {
              "bpm": 104.6,
              "kickPhaseSec": 0.08,
              "beats": [],
              "downbeatOffset": 0.08,
              "duration": 8,
              "sampleRate": 44100
            }
        """.trimIndent()
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(0.0, parsed.periodSec, 0.0)
        assertFalse(parsed.hasClickPeriod())
        assertEquals(0.08, parsed.phaseSec, 1e-9)
        assertEquals(0.08, parsed.kickPhaseSec, 1e-9)
    }

    @Test
    fun clickStepEqualsStoredPeriodNotRoundedBpm() {
        val exactBpm = 104.64
        val period = 60.0 / exactBpm
        val analysis = TrackAnalysis(
            bpm = 104.6f,
            beats = DoubleArray(0),
            downbeatOffset = 0.08,
            duration = 8.0,
            kickPhaseSec = 0.08,
            periodSec = period,
            phaseSec = 0.08,
        )
        val engine = engine(104.6f)
        engine.setAnalysis(analysis)
        val fromPeriod = BeatClock.intervalFromPeriodSec(period)
        val fromRounded = BeatClock.intervalUs(104.6f)
        assertEquals(fromPeriod, engine.clickIntervalUs())
        assertTrue(
            "104.64 period must not collapse to 60/104.6 (${fromPeriod} vs ${fromRounded})",
            fromPeriod != fromRounded,
        )
        assertEquals(80_000L, engine.gridOriginUs)
        val times = clickTimes(engine, seconds = 4)
        assertTrue("need clicks, was $times", times.size >= 4)
        times.forEach { t ->
            val dist = (t - engine.gridOriginUs).mod(fromPeriod)
            val wrap = fromPeriod - dist
            assertTrue(
                "click $t must sit on periodSec grid, not 60/104.6",
                minOf(dist, wrap) < 8_000L,
            )
        }
        val roundedDist = times.map { t ->
            val dist = (t - 80_000L).mod(fromRounded)
            minOf(dist, fromRounded - dist)
        }
        assertTrue(
            "at least one click is off the rounded-BPM grid",
            times.size < 2 || roundedDist.any { it > 50L },
        )
    }

    @Test
    fun clickDoesNotFoldOneFiftyToSeventyFive() {
        val period = 60.0 / 153.2
        val analysis = TrackAnalysis(
            bpm = 153.2f,
            beats = DoubleArray(0),
            downbeatOffset = 0.0,
            duration = 8.0,
            kickPhaseSec = 0.0,
            periodSec = period,
            phaseSec = 0.0,
        )
        val engine = engine(76.6f)
        engine.setAnalysis(analysis)
        val expected = BeatClock.intervalFromPeriodSec(period)
        val folded = BeatClock.intervalUs(76.6f)
        assertEquals(expected, engine.clickIntervalUs())
        assertTrue("153.2 period must not become 76.6 (${expected} vs ${folded})", expected != folded)
        val times = clickTimes(engine, seconds = 3)
        assertTrue("need clicks, was $times", times.size >= 4)
        val intervals = times.zipWithNext { a, b -> b - a }
        intervals.forEachIndexed { i, actual ->
            assertEquals(
                "interval[$i]=$actual must follow 153.2 period, not 75-fold",
                expected.toDouble(),
                actual.toDouble(),
                8_000.0,
            )
            assertTrue(
                "fold 153.2→76.6 would be $folded, click was $actual",
                abs(actual - folded) > abs(actual - expected),
            )
        }
    }

    @Test
    fun missingPeriodFallsBackToOriginalBpm() {
        val engine = engine(120f)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 120f,
                beats = DoubleArray(0),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.0,
            ),
        )
        assertEquals(0.0, engine.periodSec, 0.0)
        assertEquals(500_000L, engine.clickIntervalUs())
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

    private fun clickTimes(engine: MetronomeClickEngine, seconds: Int): List<Long> {
        val dest = ByteBuffer.allocateDirect(ClickLayer.SAMPLE_RATE * seconds * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        dest.limit(dest.capacity())
        dest.position(0)
        ClickLayer.render(dest, engine, 0L, 1f)
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
                times.add(BeatClock.sampleToMediaUs(i.toLong(), ClickLayer.SAMPLE_RATE))
                i += minGap
            } else {
                i++
            }
        }
        return times
    }
}
