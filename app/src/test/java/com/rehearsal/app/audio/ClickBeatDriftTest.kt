package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.player.SpeedStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 0.3.42: клик из `beats[]`, притянутых к атаке. Дрифт 120→116 остаётся
 * на бочке; seek — song time; скорость 0.85/1.15 не растягивает клик.
 */
class ClickBeatDriftTest {
    @Test
    fun analyzeSnapsDriftingKicks120to116() {
        val kicks = driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 32)
        val pcm = kicksAt(kicks)
        val duration = pcm.samples.toDouble() / pcm.sampleRate
        val nov = TempoDetector.novelty(pcm, TempoDetector.HOP)
        val (beats, _) = TempoDetector.trackBeats(
            novelty = nov,
            bpm = 120.0,
            duration = duration,
            hop = TempoDetector.HOP,
            sampleRate = pcm.sampleRate,
            phaseSec = 0.0,
            periodSec = 0.5,
        )
        assertTrue("need tracked beats, was ${beats.size}", beats.size >= 24)
        val lateKicks = kicks.drop(kicks.size / 2)
        for (kick in lateKicks) {
            val nearest = beats.minBy { abs(it - kick) }
            val err = abs(nearest - kick)
            assertTrue(
                "late kick $kick must stay on a beat (nearest=$nearest err=${err * 1000}ms). " +
                    "A frozen 120 grid would miss the 116 drift.",
                err < 0.015,
            )
        }
        val lastKick = kicks.last()
        val frozen = (lastKick / 0.5).toInt() * 0.5
        val frozenErr = abs(lastKick - frozen)
        assertTrue("fixture must actually drift vs 120, Δ=${frozenErr * 1000}ms", frozenErr > 0.040)
        val analysis = TempoDetector.analyze(pcm)
        assertEquals(0.0, analysis.downbeatOffset, 0.0)
        assertTrue(analysis.periodSec > 1e-9)
        assertEquals(
            "periodSec is the TrackTempo label, not rewritten by snap",
            TrackTempo.tempoOfTrack(pcm).periodSec,
            analysis.periodSec,
            0.0,
        )
    }

    @Test
    fun mixIntoFollowsDriftingBeatsNotEqualGrid() {
        val kicks = driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 32)
        val engine = engine()
        engine.setAnalysis(analysisOf(kicks))
        val times = clickMediaTimesUs(engine, 0L, seconds = 18, speed = 1f)
        assertClicksOnKicks(times, kicks)
        val lastKickUs = TempoMath.secondsToMediaUs(kicks.last())
        val frozenUs = BeatClock.intervalUs(120f)
        val onFrozen = times.any { t ->
            val dist = t.mod(frozenUs)
            minOf(dist, frozenUs - dist) < 8_000L && abs(t - lastKickUs) < 80_000L
        }
        val onLastKick = times.any { abs(it - lastKickUs) < 15_000L }
        assertTrue("last click must sit on the drifted kick $lastKickUs, was $times", onLastKick)
        if (abs(lastKickUs.mod(frozenUs).toDouble()) > 20_000.0) {
            assertTrue("must not still be on the 120 ruler at the end", !onFrozen || onLastKick)
        }
    }

    @Test
    fun seekToMiddleHitsDriftingBeatInThatBuffer() {
        val kicks = driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 32)
        val engine = engine()
        engine.setAnalysis(analysisOf(kicks))
        val mid = kicks[kicks.size / 2]
        val startUs = TempoMath.secondsToMediaUs(mid) - 20_000L
        val times = clickMediaTimesUs(engine, startUs, seconds = 2, speed = 1f)
        assertTrue("need clicks after seek, was $times", times.size >= 2)
        val window = kicks.filter { it >= mid - 0.05 && it <= mid + 2.0 }
        assertClicksOnKicks(times, window.toDoubleArray())
        assertTrue(
            "seek must hit the mid-song kick $mid, was $times",
            times.any { abs(it - TempoMath.secondsToMediaUs(mid)) < 15_000L },
        )
        assertTrue(
            "must not restart a 120 grid at seek time $startUs",
            times.none { abs(it - startUs) < 8_000L },
        )
    }

    @Test
    fun speed085And115KeepSongTimeBeatsWithoutStretchingClick() {
        val kicks = driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 16)
        val engine = engine()
        engine.setAnalysis(analysisOf(kicks))
        val expected = MetronomeClickEngine.synthesizeClick(44100)
        for (speed in floatArrayOf(0.85f, 1.15f)) {
            val mediaSeconds = 4
            val dest = silence(SpeedStretch.outputFrameCount(44100 * mediaSeconds, speed))
            ClickLayer.render(dest, engine, 0L, speed)
            dest.position(0)
            dest.limit(dest.capacity())
            val shorts = dest.duplicate().order(dest.order()).asShortBuffer()
            val outFrames = shorts.limit() / 2
            val inFrames = SpeedStretch.inputFrameCount(outFrames, speed)
            val coveredMediaSec = inFrames.toDouble() / 44100.0
            val covered = kicks.filter { it < coveredMediaSec - 0.05 }
            assertTrue("need beats in the $speed window", covered.size >= 4)
            val clickFrames = expected.size
            for (kick in covered) {
                val beatUs = TempoMath.secondsToMediaUs(kick)
                val start = SpeedStretch.outputFrameForInput(
                    BeatClock.frameOffset(beatUs, 0L, 44100),
                    inFrames,
                    outFrames,
                )
                val peak = peakInRange(shorts, start, start + clickFrames)
                assertTrue(
                    "speed $speed: click missing at song kick $kick frame=$start peak=$peak",
                    peak > 0.5,
                )
                val mixed = FloatArray(clickFrames) { i ->
                    val frame = start + i
                    if (frame in 0 until outFrames) {
                        shorts.get(frame * 2) / Short.MAX_VALUE.toFloat()
                    } else {
                        0f
                    }
                }
                val want = FloatArray(clickFrames) { i ->
                    MetronomeClickEngine.limit(expected[i] * MetronomeClickEngine.CLICK_VOLUME)
                }
                val corr = correlation(mixed, want)
                assertTrue("click itself must not stretch at $speed, corr=$corr", corr > 0.90)
            }
        }
    }

    @Test
    fun timeUnsetDoesNotPaintBeatZero() {
        val kicks = driftKickTimes(startBpm = 120.0, endBpm = 116.0, beats = 8)
        val engine = engine()
        engine.setAnalysis(analysisOf(kicks))
        val dest = silence(44100)
        ClickLayer.render(dest, engine, C.TIME_UNSET, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val times = clickMediaTimesFromBuffer(dest, 0L)
        assertTrue("TIME_UNSET must not paint beats[0]=0, was $times", times.isEmpty())
    }

    private fun analysisOf(kicks: DoubleArray): TrackAnalysis = TrackAnalysis(
        bpm = 120f,
        beats = kicks,
        downbeatOffset = 0.0,
        duration = (kicks.lastOrNull() ?: 0.0) + 0.5,
        periodSec = 0.5,
        phaseSec = kicks.firstOrNull() ?: 0.0,
        kickPhaseSec = kicks.firstOrNull() ?: 0.0,
    )

    private fun assertClicksOnKicks(clickUs: List<Long>, kicks: DoubleArray) {
        assertTrue("need clicks, was ${clickUs.size}", clickUs.size >= 3)
        for (t in clickUs) {
            val nearest = kicks.minOf { abs(TempoMath.secondsToMediaUs(it) - t) }
            assertTrue("click $t is ${nearest}us off the nearest kick", nearest < 15_000L)
        }
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
        engine.enabled = true
        return engine
    }

    private fun clickMediaTimesUs(
        engine: MetronomeClickEngine,
        mediaStartUs: Long,
        seconds: Int,
        speed: Float,
    ): List<Long> {
        val dest = silence(44100 * seconds)
        ClickLayer.render(dest, engine, mediaStartUs, speed)
        dest.position(0)
        dest.limit(dest.capacity())
        return clickMediaTimesFromBuffer(dest, mediaStartUs)
    }

    private fun clickMediaTimesFromBuffer(buffer: ByteBuffer, mediaStartUs: Long): List<Long> {
        val shorts = buffer.duplicate().order(buffer.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val threshold = 6000
        val minGap = 44100 / 20
        val times = ArrayList<Long>()
        var i = 0
        while (i < frames) {
            val s = abs(shorts.get(i * 2).toInt())
            if (s > threshold) {
                times.add(mediaStartUs + BeatClock.sampleToMediaUs(i.toLong(), 44100))
                i += minGap
            } else {
                i++
            }
        }
        return times
    }

    private fun silence(frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        buf.limit(frames * 4)
        buf.position(0)
        return buf
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

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(na * nb)
        return if (denom < 1e-9) 0.0 else dot / denom
    }

    companion object {
        fun driftKickTimes(startBpm: Double, endBpm: Double, beats: Int): DoubleArray {
            val times = DoubleArray(beats)
            var t = 0.0
            val last = (beats - 1).coerceAtLeast(1)
            for (i in 0 until beats) {
                times[i] = t
                val bpm = startBpm + (endBpm - startBpm) * i / last
                t += 60.0 / bpm.coerceAtLeast(1.0)
            }
            return times
        }

        fun kicksAt(times: DoubleArray, sampleRate: Int = 44100): StereoPcm {
            val durationSec = (times.lastOrNull() ?: 0.0) + 0.4
            val samples = (durationSec * sampleRate).toInt().coerceAtLeast(sampleRate)
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (sampleRate * 0.012).toInt().coerceAtLeast(32)
            val tau = 0.004 * sampleRate
            for (t in times) {
                val start = (t * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx !in 0 until samples) continue
                    val env = exp(-i / tau)
                    val s = (env * 0.95 * sin(2.0 * PI * 70.0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s * 0.96f
                }
            }
            return StereoPcm(sampleRate, left, right)
        }
    }
}
