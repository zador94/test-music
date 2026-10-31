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
 * 0.3.43: метроном ON → в выходном PCM есть щелчки.
 * Прод-путь [MetronomePcmPipe.mixInto], не только ClickLayer.
 */
class ClickAudibleTest {
    @Test
    fun oldAnalysisJsonBeatsInSecondsStillClickInSinkPcm() {
        val json = """
            {
              "bpm": 120,
              "periodSec": 0.5,
              "phaseSec": 0.0,
              "kickPhaseSec": 0.0,
              "kickGridVersion": 12,
              "beats": [0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0],
              "downbeatOffset": 0,
              "timeSignature": [4, 4],
              "duration": 8,
              "sampleRate": 44100,
              "separator": "htdemucs",
              "stems": ["vocals", "drums", "bass", "other"]
            }
        """.trimIndent()
        val analysis = TrackAnalysis.fromJson(json)!!
        assertEquals(7, analysis.beats.size)
        assertEquals(500_000L, analysis.beatTimesUs()[1])
        val engine = engine()
        engine.enabled = true
        engine.setAnalysis(analysis)
        assertEquals(2.2f, MetronomeClickEngine.CLICK_VOLUME, 1e-4f)
        assertEquals(1f, MetronomeClickEngine.CLICK_GAIN, 1e-4f)
        val out = sinkPcm(engine, silence(44100 * 2), 0L)
        assertTrue("old analysis.json beats[] (seconds) must click in mixInto", peak(out) > 0.5)
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt().coerceAtLeast(32)
        val at500 = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        assertTrue(
            "click at 0.5s from beats[] in seconds, peak=${peakInRange(shorts, at500, at500 + clickFrames)}",
            peakInRange(shorts, at500, at500 + clickFrames) > 0.5,
        )
        val mid = sinkPcm(engine, silence(44100), 1_200_000L)
        val midShorts = mid.duplicate().order(mid.order()).asShortBuffer()
        val nearest = BeatClock.mediaUsToSample(1_500_000L - 1_200_000L, 44100).toInt()
        assertTrue(
            "seek/mid-song must still click, not miss because times were seconds",
            peakInRange(midShorts, nearest, nearest + clickFrames) > 0.5,
        )
    }

    @Test
    fun emptyBeatsFallbackGridClicksInSinkPcm() {
        val json = """
            {
              "bpm": 120,
              "periodSec": 0.5,
              "phaseSec": 0.0,
              "beats": [],
              "downbeatOffset": 0,
              "duration": 8,
              "sampleRate": 44100
            }
        """.trimIndent()
        val engine = engine()
        engine.enabled = true
        engine.setAnalysis(TrackAnalysis.fromJson(json)!!)
        assertEquals(0, engine.beatTimesUs.size)
        val out = sinkPcm(engine, silence(44100), 0L)
        assertTrue("empty beats[] must fall back to periodSec grid", peak(out) > 0.5)
        val shorts = out.duplicate().order(out.order()).asShortBuffer()
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt().coerceAtLeast(32)
        val at500 = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        assertTrue(
            "fallback grid click at 500ms",
            peakInRange(shorts, at500, at500 + clickFrames) > 0.5,
        )
    }

    @Test
    fun secondsStoredAsLongDoNotSilenceTheMetronome() {
        val engine = engine()
        engine.enabled = true
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 120f,
                beats = doubleArrayOf(0.0, 1e-6, 2e-6, 3e-6),
                downbeatOffset = 0.0,
                duration = 8.0,
                periodSec = 0.5,
                phaseSec = 0.0,
            ),
        )
        // 0, 1, 2, 3 µs: firstOverlappingIndex at 1.2s finds nothing.
        assertTrue(
            !BeatClock.isPlausibleClickGrid(engine.beatTimesUs, engine.clickIntervalUs()),
        )
        val out = sinkPcm(engine, silence(44100), 1_200_000L)
        assertTrue(
            "unusable seconds-as-µs beats[] must fall back to an audible grid",
            peak(out) > 0.5,
        )
    }

    @Test
    fun gainIsNotZero() {
        assertTrue(MetronomeClickEngine.CLICK_GAIN > 0f)
        assertTrue(MetronomeClickEngine.CLICK_VOLUME > 0f)
        assertEquals(2.2f, MetronomeClickEngine.CLICK_VOLUME, 1e-4f)
    }

    private fun engine(): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = 120f
        return engine
    }

    private fun sinkPcm(engine: MetronomeClickEngine, source: ByteBuffer, pts: Long): ByteBuffer {
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        pipe.softwareSpeed = 1f
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        var guard = 0
        while (guard < 10_000) {
            if (pipe.handleBuffer(source, pts, 1, sink::handleBuffer)) break
            guard++
        }
        return sink.lastSubmittedAsBuffer()
    }

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        buf.limit(frames * 4)
        buf.position(0)
        return buf
    }

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
    }

    private fun peakInRange(shorts: java.nio.ShortBuffer, startFrame: Int, endFrame: Int): Double {
        val frames = shorts.limit() / 2
        val from = startFrame.coerceIn(0, frames)
        val to = endFrame.coerceIn(from, frames)
        var max = 0
        for (i in from until to) {
            max = maxOf(max, abs(shorts.get(i * 2).toInt()))
        }
        return max / 32768.0
    }
}
