package com.rehearsal.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Тело бочки в ударных, щелчок доли в другой дорожке.
 * Клик должен сесть на щелчок. Если дорожки совпали — остаётся на теле.
 */
class HeardAttackPhaseTest {
    @Test
    fun lateDrumBodyUsesTheEarlierAttack() {
        val attack = 0.30
        val lead = 0.170
        val drums = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = attack + lead, bars = 16, beatsPerBar = 4)
        val other = sharpClicks(drums, atSec = attack, bpm = 105.0, beats = 16 * 4)
        val grid = requireNotNull(
            DrumPhase.phaseOnHeardAttack(
                drumsLow = KickGrid.lowBandEnergy(drums, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                heard = KickGrid.highpassEnergy(other, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                sampleRate = drums.sampleRate,
                hintBpm = 105.2f,
            ),
        )
        val period = 60.0 / grid.bpm
        val err = wrapped(grid.phaseSec - attack, period)
        assertTrue(
            "phase should be the earlier attack, phase=${grid.phaseSec} err=${err * 1000}ms",
            abs(err) < 0.040,
        )
        val bodyErr = wrapped(grid.phaseSec - (attack + lead), period)
        assertTrue("phase still on the late body, err=${bodyErr * 1000}ms", abs(bodyErr) > 0.100)
    }

    @Test
    fun alignedStemsStayOnTheKick() {
        val at = 0.30
        val drums = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = at, bars = 16, beatsPerBar = 4)
        val low = KickGrid.lowBandEnergy(drums, PulseTempo.HOP, KickGrid.LOWPASS_HZ)
        val grid = requireNotNull(
            DrumPhase.phaseOnHeardAttack(low, low, drums.sampleRate, 105.2f),
        )
        val err = wrapped(grid.phaseSec - at, 60.0 / grid.bpm)
        assertTrue("aligned stems moved off the kick, err=${err * 1000}ms", abs(err) < 0.040)
    }

    @Test
    fun phaseSitsOnThePeakNotTheRisingEdge() {
        val rise = 0.30
        val peak = rise + 0.050
        val body = rise + 0.170
        val drums = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = body, bars = 16, beatsPerBar = 4)
        val other = sharpClicks(drums, atSec = rise, bpm = 105.0, beats = 16 * 4, amplitude = 0.35f)
        addClicks(other, atSec = peak, bpm = 105.0, beats = 16 * 4, amplitude = 0.95f)
        val grid = requireNotNull(
            DrumPhase.phaseOnHeardAttack(
                drumsLow = KickGrid.lowBandEnergy(drums, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                heard = KickGrid.highpassEnergy(other, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                sampleRate = drums.sampleRate,
                hintBpm = 105.2f,
            ),
        )
        val period = 60.0 / grid.bpm
        val err = wrapped(grid.phaseSec - peak, period)
        assertTrue(
            "phase stuck on the rising edge, phase=${grid.phaseSec} err=${err * 1000}ms",
            abs(err) < 0.040,
        )
        val riseErr = wrapped(grid.phaseSec - rise, period)
        assertTrue("phase still on the start of the attack, err=${riseErr * 1000}ms", abs(riseErr) > 0.025)
    }

    @Test
    fun decayingAttackSitsAfterItsTip() {
        val peak = 0.30
        val body = peak + 0.170
        val drums = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = body, bars = 16, beatsPerBar = 4)
        val other = sustained(drums, atSec = peak, holdSec = 0.040, bpm = 105.0, beats = 16 * 4)
        val grid = requireNotNull(
            DrumPhase.phaseOnHeardAttack(
                drumsLow = KickGrid.lowBandEnergy(drums, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                heard = KickGrid.highpassEnergy(other, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                sampleRate = drums.sampleRate,
                hintBpm = 105.2f,
            ),
        )
        val period = 60.0 / grid.bpm
        val afterTip = wrapped(grid.phaseSec - peak, period)
        assertTrue(
            "phase stayed on the tip, phase=${grid.phaseSec} after=${afterTip * 1000}ms",
            afterTip > 0.008,
        )
        assertTrue(
            "phase left the attack, after=${afterTip * 1000}ms",
            afterTip < 0.040,
        )
    }

    private fun sustained(shape: StereoPcm, atSec: Double, holdSec: Double, bpm: Double, beats: Int): StereoPcm {
        val sr = shape.sampleRate
        val left = FloatArray(shape.samples)
        val right = FloatArray(shape.samples)
        val period = 60.0 / bpm
        val hold = (holdSec * sr).toInt()
        for (n in 0 until beats) {
            val at = ((atSec + n * period) * sr).toInt()
            for (i in 0 until hold) {
                val idx = at + i
                if (idx < 0 || idx >= left.size) continue
                val s = (sin(2.0 * PI * 3500.0 * i / sr) * 0.8).toFloat()
                left[idx] = s
                right[idx] = s
            }
        }
        return StereoPcm(sr, left, right)
    }

    @Test
    fun louderNearBumpDoesNotHideEarlierClick() {
        val attack = 0.30
        val body = attack + 0.170
        val drums = KickGridTest.lowBandKicks(bpm = 105.0, phaseSec = body, bars = 16, beatsPerBar = 4)
        val other = sharpClicks(drums, atSec = body - 0.080, bpm = 105.0, beats = 16 * 4, amplitude = 0.95f)
        addClicks(other, atSec = attack, bpm = 105.0, beats = 16 * 4, amplitude = 0.55f)
        val grid = requireNotNull(
            DrumPhase.phaseOnHeardAttack(
                drumsLow = KickGrid.lowBandEnergy(drums, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                heard = KickGrid.highpassEnergy(other, PulseTempo.HOP, KickGrid.LOWPASS_HZ),
                sampleRate = drums.sampleRate,
                hintBpm = 105.2f,
            ),
        )
        val period = 60.0 / grid.bpm
        val err = wrapped(grid.phaseSec - attack, period)
        assertTrue(
            "loud bump 80ms before the body hid the real attack, phase=${grid.phaseSec} err=${err * 1000}ms",
            abs(err) < 0.040,
        )
    }

    private fun sharpClicks(
        shape: StereoPcm,
        atSec: Double,
        bpm: Double,
        beats: Int,
        amplitude: Float = 0.9f,
    ): StereoPcm {
        val pcm = StereoPcm(shape.sampleRate, FloatArray(shape.samples), FloatArray(shape.samples))
        addClicks(pcm, atSec, bpm, beats, amplitude)
        return pcm
    }

    private fun addClicks(pcm: StereoPcm, atSec: Double, bpm: Double, beats: Int, amplitude: Float) {
        val sr = pcm.sampleRate
        val period = 60.0 / bpm
        val clickLen = (0.004 * sr).toInt()
        for (n in 0 until beats) {
            val at = ((atSec + n * period) * sr).toInt()
            for (i in 0 until clickLen) {
                val idx = at + i
                if (idx < 0 || idx >= pcm.samples) continue
                val s = (sin(2.0 * PI * 3500.0 * i / sr) * amplitude).toFloat()
                pcm.left[idx] = s
                pcm.right[idx] = s
            }
        }
    }

    private fun wrapped(delta: Double, period: Double): Double {
        var m = delta % period
        if (m > period / 2.0) m -= period
        if (m < -period / 2.0) m += period
        return m
    }
}
