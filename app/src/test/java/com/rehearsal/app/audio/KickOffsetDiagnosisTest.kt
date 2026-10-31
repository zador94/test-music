package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Device 0.3.20: default 0 is ≥150 ms late (slider pegged at −150).
 * Measure each suspect on synthetics before changing KickGrid / the pipe.
 */
class KickOffsetDiagnosisTest {
    @Test
    fun shortKickOnsetWithinOneHop() {
        val phaseSec = 0.08
        val pcm = kicks(bpm = 105.0, phaseSec = phaseSec, decaySec = 0.04, f0 = 70.0)
        val result = KickGrid.analyze(pcm)
        val errMs = wrappedMs(result.kickPhaseSec - phaseSec, 60.0 / 105.0)
        assertTrue(
            "a) short kick phase error ${errMs}ms (hop=${hopMs()}ms) phase=${result.kickPhaseSec}",
            abs(errMs) <= hopMs() + 1.0,
        )
    }

    @Test
    fun slowAttackKickMustLockToOnsetNotEnergyPeak() {
        val phaseSec = 0.08
        val attackSec = 0.15
        val pcm = rampedKicks(bpm = 105.0, phaseSec = phaseSec, attackSec = attackSec)
        val result = KickGrid.analyze(pcm)
        val errMs = wrappedMs(result.kickPhaseSec - phaseSec, 60.0 / 105.0)
        assertTrue(
            "a) 150ms-attack kick phase error ${errMs}ms (peak would be ~${attackSec * 1000}ms late) phase=${result.kickPhaseSec}",
            abs(errMs) <= hopMs() + 8.0,
        )
    }

    @Test
    fun sharpAttackLongLowBodyOnsetWithin15ms() {
        val phaseSec = 0.08
        val pcm = realisticKicks(bpm = 105.0, phaseSec = phaseSec)
        val result = KickGrid.analyze(pcm)
        val errMs = wrappedMs(result.kickPhaseSec - phaseSec, 60.0 / 105.0)
        assertTrue(
            "click+delayed body must lock to the beater, err=${errMs}ms " +
                "spread=${result.onsetPeakSpreadSec * 1000.0}ms phase=${result.kickPhaseSec}",
            abs(errMs) <= 15.0,
        )
        assertTrue(
            "onset-vs-peak spread should show a late body, spread=${result.onsetPeakSpreadSec * 1000.0}ms",
            result.onsetPeakSpreadSec >= 0.025,
        )
    }

    @Test
    fun longDecayingKickOnsetNotBodyPeak() {
        val phaseSec = 0.08
        val pcm = kicks(bpm = 105.0, phaseSec = phaseSec, decaySec = 0.22, f0 = 60.0)
        val result = KickGrid.analyze(pcm)
        val errMs = wrappedMs(result.kickPhaseSec - phaseSec, 60.0 / 105.0)
        assertTrue(
            "a/b) long kick phase error ${errMs}ms (body 220ms) phase=${result.kickPhaseSec}",
            abs(errMs) <= hopMs() + 5.0,
        )
    }

    @Test
    fun kickPlusSnareDoesNotLockHalfway() {
        val phaseSec = 0.08
        val period = 60.0 / 105.0
        val pcm = kicksAndSnares(bpm = 105.0, kickPhaseSec = phaseSec)
        val result = KickGrid.analyze(pcm)
        val errMs = wrappedMs(result.kickPhaseSec - phaseSec, period)
        val halfMs = period * 500.0
        assertTrue(
            "b) kick+snare phase error ${errMs}ms (half-beat=${halfMs}ms) phase=${result.kickPhaseSec}",
            abs(errMs) <= hopMs() + 8.0,
        )
    }

    @Test
    fun pipeMappingAtSpeedOneMatchesKnownKickWhenPhaseForced() {
        val sr = 44100
        val kickAt = 0.08
        val pcm = kicks(bpm = 105.0, phaseSec = kickAt, decaySec = 0.04, f0 = 70.0)
        val engine = engine(105f)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = 105f,
                beats = DoubleArray(0),
                downbeatOffset = kickAt,
                duration = pcm.samples.toDouble() / sr,
                sampleRate = sr,
                kickPhaseSec = kickAt,
            ),
        )
        engine.enabled = false
        val music = pipeMix(pcm, engine, speed = 1f)
        val kickFrame = firstOnsetFrame(music, sr)
        engine.enabled = true
        val click = pipeMix(silenceLike(pcm), engine, speed = 1f)
        val clickFrame = firstOnsetFrame(click, sr)
        val errMs = (clickFrame - kickFrame) * 1000.0 / sr
        assertTrue(
            "c) forced-phase pipe click-kick ${errMs}ms (click=$clickFrame kick=$kickFrame)",
            abs(errMs) <= 5.0,
        )
    }

    @Test
    fun integerVsFractionalBpmDoesNotShiftFirstBeat() {
        val trueBpm = 104.6
        val phaseSec = 0.08
        val pcm = kicks(bpm = trueBpm, phaseSec = phaseSec, decaySec = 0.04, f0 = 70.0)
        val asInt = engine(105f)
        asInt.setGridOriginUs(
            BeatClock.gridOriginUs(
                TempoMath.secondsToMediaUs(phaseSec),
                0L,
                BeatClock.intervalUs(105f),
            ),
        )
        val asFrac = engine(104.6f)
        asFrac.setGridOriginUs(
            BeatClock.gridOriginUs(
                TempoMath.secondsToMediaUs(phaseSec),
                0L,
                BeatClock.intervalUs(104.6f),
            ),
        )
        val intFirst = firstClickUs(asInt)
        val fracFirst = firstClickUs(asFrac)
        val errMs = (intFirst - fracFirst) / 1000.0
        assertTrue(
            "d) first-beat integer vs fractional ${errMs}ms (int=$intFirst frac=$fracFirst)",
            abs(errMs) <= 5.0,
        )
    }

    @Test
    fun fullPipeClickLandsOnKickAtSpeedOne() {
        val sr = 44100
        val kickAt = 0.08
        val pcm = rampedKicks(bpm = 105.0, phaseSec = kickAt, attackSec = 0.15)
        val analyzed = KickGrid.analyze(pcm)
        val engine = engine(analyzed.bpm)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = analyzed.bpm,
                beats = DoubleArray(0),
                downbeatOffset = analyzed.kickPhaseSec,
                duration = pcm.samples.toDouble() / sr,
                sampleRate = sr,
                kickPhaseSec = analyzed.kickPhaseSec,
            ),
        )
        engine.enabled = true
        val click = pipeMix(silenceLike(pcm), engine, speed = 1f)
        val kickFrame = (kickAt * sr).toInt()
        val clickFrame = firstOnsetFrame(click, sr)
        val errMs = (clickFrame - kickFrame) * 1000.0 / sr
        assertTrue(
            "full pipe click-kick ${errMs}ms (clickFrame=$clickFrame kickFrame=$kickFrame phase=${analyzed.kickPhaseSec} bpm=${analyzed.bpm})",
            abs(errMs) <= hopMs() + 8.0,
        )
    }

    private fun hopsMs(): Double = KickGrid.HOP * 1000.0 / 44100.0

    private fun hopMs(): Double = hopsMs()

    private fun wrappedMs(deltaSec: Double, periodSec: Double): Double {
        val p = periodSec.coerceAtLeast(1e-9)
        var m = deltaSec % p
        if (m < 0.0) m += p
        val wrapped = min(m, p - m)
        val signed = if (m <= p - m) m else m - p
        return signed * 1000.0
    }

    private fun engine(bpm: Float): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = bpm
        engine.enabled = true
        return engine
    }

    private fun firstClickUs(engine: MetronomeClickEngine): Long {
        val dest = ByteBuffer.allocateDirect(44100 * 4).order(ByteOrder.nativeOrder())
        dest.limit(dest.capacity())
        ClickLayer.render(dest, engine, 0L, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val shorts = dest.asShortBuffer()
        for (i in 0 until shorts.limit() / 2) {
            if (abs(shorts.get(i * 2).toInt()) > 6000) {
                return BeatClock.sampleToMediaUs(i.toLong(), 44100)
            }
        }
        return -1L
    }

    private fun pipeMix(pcm: StereoPcm, engine: MetronomeClickEngine, speed: Float): ByteBuffer {
        val frames = pcm.samples
        val src = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (pcm.left[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            src.putShort(s)
            src.putShort(s)
        }
        src.flip()
        val pipe = MetronomePcmPipe(engine)
        pipe.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.sampleRate)
                .setChannelCount(2)
                .build(),
        )
        pipe.softwareSpeed = speed
        val sink = IdentityAudioSink(bytesPerAccept = Int.MAX_VALUE)
        var offset = 0
        val chunk = 1024
        while (offset < frames) {
            val n = minOf(chunk, frames - offset)
            val slice = src.duplicate()
            slice.position(offset * 4)
            slice.limit((offset + n) * 4)
            val pts = BeatClock.sampleToMediaUs(offset.toLong(), pcm.sampleRate)
            var guard = 0
            while (guard < 10_000) {
                if (pipe.handleBuffer(slice, pts, 1, sink::handleBuffer)) break
                guard++
            }
            offset += n
        }
        return sink.lastSubmittedAsBuffer()
    }

    private fun silenceLike(pcm: StereoPcm): StereoPcm =
        StereoPcm(pcm.sampleRate, FloatArray(pcm.samples), FloatArray(pcm.samples))

    private fun firstOnsetFrame(pcm16: ByteBuffer, sampleRate: Int): Int {
        val shorts = pcm16.duplicate().order(pcm16.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        // Kick is low-band; click is 2 kHz. Prefer the first loud transient of either.
        var max = 1
        for (i in 0 until frames) {
            max = maxOf(max, abs(shorts.get(i * 2).toInt()))
        }
        val thresh = (max * 0.35).toInt().coerceAtLeast(4000)
        for (i in 1 until frames) {
            val prev = abs(shorts.get((i - 1) * 2).toInt())
            val cur = abs(shorts.get(i * 2).toInt())
            if (cur >= thresh && cur > prev) return i
        }
        return 0
    }

    companion object {
        fun kicks(
            bpm: Double,
            phaseSec: Double,
            decaySec: Double,
            f0: Double,
            bars: Int = 8,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.3
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickLen = (sampleRate * decaySec).toInt().coerceAtLeast(64)
            val tau = decaySec * 0.35 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx < 0 || idx >= samples) continue
                    val env = exp(-i / tau)
                    val s = (env * 0.95 * sin(2.0 * PI * f0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun rampedKicks(
            bpm: Double,
            phaseSec: Double,
            attackSec: Double,
            bars: Int = 8,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.4
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val attack = (attackSec * sampleRate).toInt().coerceAtLeast(8)
            val tail = (0.08 * sampleRate).toInt()
            val n = attack + tail
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until n) {
                    val idx = start + i
                    if (idx < 0 || idx >= samples) continue
                    val env = if (i < attack) {
                        i.toDouble() / attack
                    } else {
                        exp(-(i - attack) / (0.025 * sampleRate))
                    }
                    val s = (env * 0.95 * sin(2.0 * PI * 55.0 * i / sampleRate)).toFloat()
                    left[idx] = s
                    right[idx] = s
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun realisticKicks(
            bpm: Double,
            phaseSec: Double,
            bars: Int = 8,
            sampleRate: Int = 44100,
        ): StereoPcm {
            val period = 60.0 / bpm
            val beats = bars * 4
            val durationSec = phaseSec + beats * period + 0.5
            val samples = (durationSec * sampleRate).toInt()
            val left = FloatArray(samples)
            val right = FloatArray(samples)
            val clickAttack = (0.0004 * sampleRate).toInt().coerceAtLeast(1)
            val clickLen = (0.006 * sampleRate).toInt().coerceAtLeast(8)
            val clickTau = 0.002 * sampleRate
            val bodyDelay = (0.045 * sampleRate).toInt()
            val bodyAttack = (0.090 * sampleRate).toInt()
            val bodyLen = bodyAttack + (0.180 * sampleRate).toInt()
            val bodyTau = 0.060 * sampleRate
            for (b in 0 until beats) {
                val start = ((phaseSec + b * period) * sampleRate).toInt()
                for (i in 0 until clickLen) {
                    val idx = start + i
                    if (idx < 0 || idx >= samples) continue
                    val env = if (i < clickAttack) {
                        i.toDouble() / clickAttack
                    } else {
                        exp(-(i - clickAttack) / clickTau)
                    }
                    val s = (env * 0.7 * sin(2.0 * PI * 2500.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
                for (i in 0 until bodyLen) {
                    val idx = start + bodyDelay + i
                    if (idx < 0 || idx >= samples) continue
                    val env = if (i < bodyAttack) {
                        i.toDouble() / bodyAttack
                    } else {
                        exp(-(i - bodyAttack) / bodyTau)
                    }
                    val s = (env * 0.95 * sin(2.0 * PI * 50.0 * i / sampleRate)).toFloat()
                    left[idx] += s
                    right[idx] += s
                }
            }
            return StereoPcm(sampleRate, left, right)
        }

        fun kicksAndSnares(bpm: Double, kickPhaseSec: Double, sampleRate: Int = 44100): StereoPcm {
            val period = 60.0 / bpm
            val kicks = kicks(bpm, kickPhaseSec, decaySec = 0.18, f0 = 55.0, sampleRate = sampleRate)
            val snarePhase = kickPhaseSec + period / 2.0
            val snares = kicks(bpm, snarePhase, decaySec = 0.08, f0 = 180.0, sampleRate = sampleRate)
            val n = min(kicks.samples, snares.samples)
            val left = FloatArray(n)
            val right = FloatArray(n)
            for (i in 0 until n) {
                left[i] = kicks.left[i] + 0.55f * snares.left[i]
                right[i] = kicks.right[i] + 0.55f * snares.right[i]
            }
            return StereoPcm(sampleRate, left, right)
        }
    }
}
