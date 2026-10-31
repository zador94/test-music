package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 0.3.44: клик в том PCM, который [MetronomeAudioSink] отдаёт в delegate
 * (DefaultAudioSink → AudioTrack.write remaining()). Не helper mixInto и не
 * [ClickLayer]. Чанки как у [StemMixMediaSource] (1024 кадра). Analysis —
 * как [PlaybackEngine.loadAnalysis]: JSON секунды → [setAnalysis].
 */
class ClickSinkPcmTest {
    @Test
    fun metronomeAudioSinkHandleBufferPutsClickInDelegateRemaining() {
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
        assertEquals(500_000L, analysis.beatTimesUs()[1])

        val engine = engine()
        engine.enabled = true
        engine.setAnalysis(analysis)

        val captured = ArrayList<ByteArray>()
        val delegateBuffers = ArrayList<ByteBuffer>()
        val sources = ArrayList<ByteBuffer>()
        val sink = MetronomeAudioSink(
            capturingSink(captured, delegateBuffers),
            engine,
            StemMixBank(),
        )
        sink.configure(pcm16(), /* specifiedBufferSize = */ 0, /* outputChannels = */ null)

        val totalFrames = 44100 * 2
        var offset = 0
        while (offset < totalFrames) {
            val n = minOf(CHUNK_FRAMES, totalFrames - offset)
            val pts = BeatClock.sampleToMediaUs(offset.toLong(), 44100)
            val source = stemMixChunk(n)
            sources.add(source)
            assertTrue(sink.handleBuffer(source, pts, 1))
            offset += n
        }

        val played = concat(captured)
        assertTrue(
            "AudioTrack remaining() must contain the click, not an empty leftover after mixInto",
            peak(played) > 0.5,
        )
        val at500 = BeatClock.mediaUsToSample(500_000L, 44100).toInt()
        val clickFrames = (engine.clickDurationUs() * 44100 / 1_000_000L).toInt().coerceAtLeast(32)
        assertTrue(
            "click at 0.5s from player analysis.json beats[] in seconds, peak=${peakInRange(played, at500, at500 + clickFrames)}",
            peakInRange(played, at500, at500 + clickFrames) > 0.5,
        )
        assertTrue("delegate.handleBuffer must see mixed PCM", delegateBuffers.isNotEmpty())
        assertTrue(
            "sink must not play the decoder ByteBuffer (DefaultAudioSink identity)",
            sources.none { src -> delegateBuffers.any { it === src } },
        )
    }

    @Test
    fun farAwayPlausibleBeatsStillClickInAudioSinkRemaining() {
        val engine = engine()
        engine.enabled = true
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 120f,
                beats = doubleArrayOf(3600.0, 3600.5, 3601.0, 3601.5),
                downbeatOffset = 0.0,
                duration = 8.0,
                periodSec = 0.5,
                phaseSec = 0.0,
            ),
        )
        val times = engine.beatTimesUs
        assertTrue(BeatClock.isPlausibleClickGrid(times, engine.clickIntervalUs()))
        assertTrue(!BeatClock.hasBeatNear(times, 0L, 23_219L, engine.clickIntervalUs()))

        val captured = ArrayList<ByteArray>()
        val sink = MetronomeAudioSink(
            capturingSink(captured, ArrayList()),
            engine,
            StemMixBank(),
        )
        sink.configure(pcm16(), 0, null)
        val source = stemMixChunk(CHUNK_FRAMES)
        val pts = 0L
        assertTrue(sink.handleBuffer(source, pts, 1))
        assertTrue(
            "beats hours away must not silence the metronome on the AudioTrack buffer",
            peak(concat(captured)) > 0.5,
        )
    }

    private fun capturingSink(
        captured: MutableList<ByteArray>,
        delegateBuffers: MutableList<ByteBuffer>,
    ): AudioSink {
        return Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    delegateBuffers.add(buffer)
                    val n = buffer.remaining()
                    val copy = ByteArray(n)
                    val view = buffer.asReadOnlyBuffer()
                    if (n > 0) view.get(copy)
                    captured.add(copy)
                    buffer.position(buffer.limit())
                    java.lang.Boolean.TRUE
                }
                "supportsFormat" -> java.lang.Boolean.TRUE
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getCurrentPositionUs" -> java.lang.Long.valueOf(0L)
                "getAudioTrackBufferSizeUs" -> java.lang.Long.valueOf(C.TIME_UNSET)
                "getFormatSupport" -> java.lang.Integer.valueOf(2)
                else -> defaultValue(method.returnType)
            }
        } as AudioSink
    }

    private fun defaultValue(rt: Class<*>): Any? = when {
        rt == Void.TYPE -> null
        rt == java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
        rt == Integer.TYPE -> java.lang.Integer.valueOf(0)
        rt == java.lang.Long.TYPE -> java.lang.Long.valueOf(0L)
        rt == java.lang.Float.TYPE -> java.lang.Float.valueOf(1f)
        else -> null
    }

    private fun engine(): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(pcm16())
        engine.originalBpm = 120f
        return engine
    }

    private fun pcm16(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(44100)
        .setChannelCount(2)
        .build()

    private fun stemMixChunk(frames: Int): ByteBuffer {
        val bytes = frames * 4
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        buf.clear()
        buf.limit(bytes)
        buf.position(bytes)
        buf.flip()
        return buf
    }

    private fun concat(chunks: List<ByteArray>): ByteBuffer {
        val all = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun peak(buffer: ByteBuffer): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        var max = 0
        for (i in 0 until shorts.limit()) {
            max = maxOf(max, abs(shorts.get(i).toInt()))
        }
        return max / 32768.0
    }

    private fun peakInRange(buffer: ByteBuffer, startFrame: Int, endFrame: Int): Double {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val from = startFrame.coerceIn(0, frames)
        val to = endFrame.coerceIn(from, frames)
        var max = 0
        for (i in from until to) {
            max = maxOf(max, abs(shorts.get(i * 2).toInt()))
        }
        return max / 32768.0
    }

    companion object {
        private const val CHUNK_FRAMES = 1024
    }
}
