package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.rehearsal.app.data.StemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Device 0.3.23 still needs −170 ms on Drunk_n_Funk at ×1.00.
 * If the pipe shifts click vs kick, it must show up in the **same** output PCM.
 */
class ClickPipeOffsetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stemMixPtsAndClickFrameStayOnSameSample() {
        val sr = 44100
        val kick = (0.08 * sr).toInt()
        val kickUs = TempoMath.secondsToMediaUs(0.08)
        var checked = 0
        for (chunk in 0..800) {
            val startSample = chunk * CHUNK_FRAMES
            if (kick < startSample || kick >= startSample + CHUNK_FRAMES) continue
            val pts = BeatClock.sampleToMediaUs(startSample.toLong(), sr)
            val recovered = BeatClock.mediaUsToSample(pts, sr).toInt()
            val musicLocal = kick - recovered
            val clickLocal = BeatClock.frameOffset(kickUs, pts, sr)
            assertEquals("round-trip sample $startSample pts=$pts", startSample, recovered)
            assertTrue(
                "chunk $chunk musicLocal=$musicLocal clickLocal=$clickLocal pts=$pts",
                musicLocal == clickLocal,
            )
            checked++
        }
        assertTrue("kick must land in some chunk", checked == 1)
    }

    @Test
    fun mixedPcmClickMinusKickAtSpeedOneFromStart() {
        assertMedianOffsetMs(speed = 1f, startUs = 0L, viaBank = false)
    }

    @Test
    fun mixedPcmClickMinusKickAtSpeedOneAfterSeek() {
        assertMedianOffsetMs(speed = 1f, startUs = 2_000_000L, viaBank = false)
    }

    @Test
    fun mixedPcmClickMinusKickAtSpeedOneStartMidSong() {
        val periodUs = BeatClock.intervalUs(BPM)
        val startUs = TempoMath.secondsToMediaUs(PHASE_SEC) + 3L * periodUs
        assertMedianOffsetMs(speed = 1f, startUs = startUs, viaBank = false)
    }

    @Test
    fun mixedPcmClickMinusKickAtSpeed08() {
        assertMedianOffsetMs(speed = 0.8f, startUs = 0L, viaBank = false)
    }

    @Test
    fun mixedPcmClickMinusKickAtSpeed12() {
        assertMedianOffsetMs(speed = 1.2f, startUs = 0L, viaBank = false)
    }

    @Test
    fun stemMixBankSameBufferAtSpeedOne() {
        assertMedianOffsetMs(speed = 1f, startUs = 0L, viaBank = true)
    }

    @Test
    fun stemMixBankSameBufferAfterSeek() {
        assertMedianOffsetMs(speed = 1f, startUs = 2_000_000L, viaBank = true)
    }

    @Test
    fun songTimeMapsToSameOutputIndexAtSpeedOne() {
        val sr = 44100
        val atSec = 1.0
        val samples = sr * 3
        val left = FloatArray(samples)
        val right = FloatArray(samples)
        val at = (atSec * sr).toInt()
        left[at] = 0.95f
        right[at] = 0.95f
        val pcm = StereoPcm(sr, left, right)
        val engine = engine(enabled = false)
        val out = pipeMixDirect(pcm, speed = 1f, startUs = 0L, engine = engine)
        val peak = peakFrame(pcm16Left(out))
        val errMs = (peak - at) * 1000.0 / sr
        println("ClickPipeOffset songTime→output impulse at ${atSec}s peak=$peak err=${"%.3f".format(errMs)}ms")
        assertTrue(
            "song sample $at must stay at output $at, not +170ms (err=${errMs}ms peak=$peak)",
            abs(errMs) <= 5.0,
        )
    }

    @Test
    fun clickOnSilenceLandsAtSongTimeAtSpeedOne() {
        val sr = 44100
        val atSec = 1.0
        val pcm = StereoPcm(sr, FloatArray(sr * 3), FloatArray(sr * 3))
        val engine = engine(phaseSec = atSec, enabled = true)
        val out = pipeMixDirect(pcm, speed = 1f, startUs = 0L, engine = engine)
        val left = pcm16Left(out)
        val expected = BeatClock.mediaUsToSample(TempoMath.secondsToMediaUs(atSec), sr).toInt()
        val window = (sr * 0.02).toInt()
        val peak = peakFrame(left, expected - window, expected + window)
        val errMs = (peak - expected) * 1000.0 / sr
        println("ClickPipeOffset click-only at ${atSec}s peak=$peak expected=$expected err=${"%.3f".format(errMs)}ms")
        assertTrue(
            "click at song ${atSec}s must land on that output sample, err=${errMs}ms " +
                "(global max can be an earlier origin-mod-period click)",
            abs(errMs) <= 5.0,
        )
    }

    private fun assertMedianOffsetMs(speed: Float, startUs: Long, viaBank: Boolean) {
        val pcm = kicks(bpm = BPM.toDouble(), phaseSec = PHASE_SEC, bars = 8)
        val out = if (viaBank) {
            pipeMixBank(pcm, speed, startUs)
        } else {
            pipeMixDirect(pcm, speed, startUs, engine())
        }
        val sr = pcm.sampleRate
        val left = pcm16Left(out)
        // Click is a 6 ms 2–4.5 kHz tick; kick is a 70 Hz burst. Same-buffer
        // HP vs LP onsets — not a 70 Hz template (that locks a period = 14 ms).
        val clickFrames = hpOnsets(left, sr)
        val kickFrames = lpOnsets(left, sr)
        val periodOut = (60.0 / BPM / speed.toDouble()) * sr
        val phaseOut = (PHASE_SEC / speed.toDouble()) * sr
        val startOut = (startUs / 1_000_000.0 / speed.toDouble()) * sr
        val pairs = ArrayList<Double>()
        val window = (sr * 0.012).toInt()
        var n = 0
        while (n < 48) {
            val expected = phaseOut + n * periodOut - startOut
            if (expected < 32) {
                n++
                continue
            }
            if (expected >= left.size - sr / 10) break
            val want = expected.roundToIntSafe()
            val click = nearest(clickFrames, want, window)
            val kick = nearest(kickFrames, want, window)
            if (click != null && kick != null) {
                pairs.add((click - kick) * 1000.0 / sr)
            }
            n++
        }
        assertTrue(
            "need click-kick pairs in output (clicks=${clickFrames.size} kicks=${kickFrames.size} speed=$speed startUs=$startUs bank=$viaBank)",
            pairs.size >= 4,
        )
        val sorted = pairs.sorted()
        val median = sorted[sorted.size / 2]
        val first = sorted.minOrNull() ?: 0.0
        val last = sorted.maxOrNull() ?: 0.0
        println(
            "ClickPipeOffset speed=$speed startUs=$startUs bank=$viaBank " +
                "median=${"%.3f".format(median)}ms min=${"%.3f".format(first)}ms " +
                "max=${"%.3f".format(last)}ms n=${pairs.size} pairs=$sorted",
        )
        assertTrue(
            "pipe click-kick median ${median}ms (min=${first}ms max=${last}ms n=${pairs.size} speed=$speed startUs=$startUs bank=$viaBank). " +
                "If this is ~0, the −170 ms is not MetronomePcmPipe/StemMix.",
            abs(median) <= 5.0,
        )
        if (abs(speed - 1f) < 0.001f) {
            assertTrue(
                "pipe click-kick must not grow along the song, min=${first}ms max=${last}ms",
                abs(last - first) <= 12.0,
            )
        }
    }

    private fun pipeMixDirect(
        pcm: StereoPcm,
        speed: Float,
        startUs: Long,
        engine: MetronomeClickEngine,
    ): ByteBuffer {
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(pcm16())
        pipe.softwareSpeed = speed
        pipe.flush()
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val sr = pcm.sampleRate
        val startFrame = BeatClock.mediaUsToSample(startUs, sr).toInt().coerceAtLeast(0)
        var offset = startFrame
        val src = toPcm16(pcm)
        while (offset < pcm.samples) {
            val n = minOf(CHUNK_FRAMES, pcm.samples - offset)
            val slice = src.duplicate()
            slice.position(offset * 4)
            slice.limit((offset + n) * 4)
            val pts = BeatClock.sampleToMediaUs(offset.toLong(), sr)
            drain(pipe, slice, pts, sink)
            offset += n
        }
        return sink.lastSubmittedAsBuffer()
    }

    private fun pipeMixBank(pcm: StereoPcm, speed: Float, startUs: Long): ByteBuffer {
        val dir = tmp.newFolder("stems-$speed-$startUs")
        val files = StemId.entries.associateWith { id ->
            val file = java.io.File(dir, id.fileName)
            val left = if (id == StemId.DRUMS) pcm.left else FloatArray(pcm.samples)
            val right = if (id == StemId.DRUMS) pcm.right else FloatArray(pcm.samples)
            WavWriter(file, pcm.sampleRate, channels = 2).use { it.writePlanarFloatStereo(left, right) }
            file
        }
        val bank = StemMixBank()
        bank.load(files)
        val engine = engine()
        val pipe = MetronomePcmPipe(engine, bank)
        pipe.configure(pcm16())
        pipe.softwareSpeed = speed
        pipe.flush()
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        val sr = pcm.sampleRate
        val dummy = ByteBuffer.allocateDirect(CHUNK_FRAMES * 4).order(ByteOrder.nativeOrder())
        val durationUs = bank.durationUs()
        var positionBytes = BeatClock.mediaUsToSample(startUs, sr) * 4L
        val durationBytes = pcm.samples.toLong() * 4L
        while (positionBytes < durationBytes) {
            val pts = BeatClock.sampleToMediaUs(positionBytes / 4L, sr)
            if (pts >= durationUs) break
            dummy.clear()
            dummy.limit(CHUNK_FRAMES * 4)
            drain(pipe, dummy, pts, sink)
            positionBytes += CHUNK_FRAMES * 4L
        }
        bank.clear()
        return sink.lastSubmittedAsBuffer()
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
        error("pipe did not accept buffer at pts=$pts")
    }

    private fun engine(
        phaseSec: Double = PHASE_SEC,
        enabled: Boolean = true,
    ): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(pcm16())
        engine.originalBpm = BPM
        engine.setAnalysis(
            TrackAnalysis(
                bpm = BPM,
                beats = DoubleArray(0),
                downbeatOffset = phaseSec,
                duration = 20.0,
                sampleRate = 44100,
                kickPhaseSec = phaseSec,
            ),
        )
        engine.enabled = enabled
        engine.calibrationUs = 0L
        return engine
    }

    private fun peakFrame(x: FloatArray, from: Int = 0, to: Int = x.size): Int {
        val lo = from.coerceIn(0, x.size)
        val hi = to.coerceIn(lo, x.size)
        var best = lo
        var bestAbs = 0f
        for (i in lo until hi) {
            val a = abs(x[i])
            if (a > bestAbs) {
                bestAbs = a
                best = i
            }
        }
        return best
    }

    private fun Double.roundToIntSafe(): Int =
        if (!isFinite()) 0 else toInt().coerceAtLeast(0)

    private fun pcm16(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(44100)
        .setChannelCount(2)
        .build()

    private fun toPcm16(pcm: StereoPcm): ByteBuffer {
        val src = ByteBuffer.allocateDirect(pcm.samples * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until pcm.samples) {
            val s = (pcm.left[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            src.putShort(s)
            src.putShort(s)
        }
        src.flip()
        return src
    }

    private fun pcm16Left(pcm16: ByteBuffer): FloatArray {
        val shorts = pcm16.duplicate().order(pcm16.order()).asShortBuffer()
        val frames = shorts.remaining() / 2
        return FloatArray(frames) { i -> shorts.get(i * 2) / 32768f }
    }

    private fun hpOnsets(x: FloatArray, sr: Int): List<Int> {
        val energy = FloatArray(x.size)
        var prev = 0f
        for (i in x.indices) {
            val hp = x[i] - prev
            prev = x[i]
            energy[i] = abs(hp)
        }
        return localPeaks(energy, (sr * 0.004).toInt().coerceAtLeast(2), 0.12f)
    }

    private fun lpOnsets(x: FloatArray, sr: Int): List<Int> {
        val alpha = (1.0 - exp(-2.0 * PI * 80.0 / sr)).toFloat()
        var lp = 0f
        val energy = FloatArray(x.size)
        for (i in x.indices) {
            lp += alpha * (x[i] - lp)
            energy[i] = abs(lp)
        }
        val peaks = localPeaks(energy, (sr * 0.08).toInt().coerceAtLeast(8), 0.08f)
        // Walk back to 12% of that peak — onset start, not the 70 Hz body.
        return peaks.map { peakIdx ->
            val target = energy[peakIdx] * 0.12f
            var i = peakIdx
            while (i > 0 && energy[i] > target) i--
            i
        }
    }

    private fun localPeaks(energy: FloatArray, minGap: Int, frac: Float): List<Int> {
        var peak = 1e-8f
        for (v in energy) if (v > peak) peak = v
        val thresh = peak * frac
        val out = ArrayList<Int>()
        var i = 1
        while (i < energy.size - 1) {
            if (energy[i] >= thresh && energy[i] >= energy[i - 1] && energy[i] >= energy[i + 1]) {
                out.add(i)
                i += minGap
            } else {
                i++
            }
        }
        return out
    }

    private fun nearest(times: List<Int>, expected: Int, window: Int): Int? {
        var best: Int? = null
        var bestDist = window + 1
        for (t in times) {
            val d = abs(t - expected)
            if (d < bestDist) {
                bestDist = d
                best = t
            }
        }
        return best
    }

    companion object {
        const val BPM = 104.6f
        const val PHASE_SEC = 0.08
        const val CHUNK_FRAMES = 1024

        fun kicks(bpm: Double, phaseSec: Double, bars: Int, sampleRate: Int = 44100): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.3
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (sampleRate * 0.04).toInt().coerceAtLeast(64)
            val tau = 0.012 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx < 0 || idx >= samples) continue
                    val env = exp(-i / tau)
                    val s = (env * 0.95 * sin(2.0 * PI * 70.0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s
                }
            }
            return StereoPcm(sampleRate, left, right)
        }
    }
}
