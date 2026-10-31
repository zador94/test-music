package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.3.41: phaseSec — слышимая атака микса, если тело Demucs стабильно позже.
 * Литералы 0.576 / 0.076 — гребёнка энергии стема, не клик.
 * Лаг измеряется, не константа 200 мс. periodSec микса не меняется.
 */
class DrumPhaseLockTest {
    @Test
    fun skyfallMixAttackWhenDrumsBodyLater() {
        val period = 0.800117626775
        val drumsBody = 0.576448941793
        val mixAttack = drumsBody - 0.20
        val mixPcm = KickOffsetDiagnosisTest.rampedKicks(
            bpm = 60.0 / period,
            phaseSec = mixAttack,
            attackSec = 0.20,
            bars = 8,
        )
        val drums = KickOffsetDiagnosisTest.kicks(
            bpm = 60.0 / period,
            phaseSec = drumsBody,
            decaySec = 0.04,
            f0 = 55.0,
            bars = 8,
        )
        val energyComb = DrumPhase.energyCombPhase(drums, period)
        assertTrue(
            "drums energy comb still on 0.576, got $energyComb",
            abs(wrapped(energyComb - drumsBody, period)) < 0.06,
        )
        val lag = DrumPhase.stableBodyLagSec(mixPcm, drums, period)
        assertNotNull("Demucs body must be stably later than mix attack, lag=$lag", lag)
        assertTrue("lag is measured, not 200ms constant: $lag", abs(lag!! - 0.20) < 0.05)
        val aligned = DrumPhase.align(
            analysis(75f, period, drumsBody, mixPcm),
            drums,
            mixPcm,
        )
        assertEquals("Skyfall periodSec must not change", period, aligned.periodSec, 0.0)
        assertEquals(75f, aligned.bpm, 0.0f)
        assertEquals(KickGrid.VERSION, aligned.kickGridVersion)
        val fromBody = wrapped(aligned.phaseSec - drumsBody, period)
        val fromAttack = wrapped(aligned.phaseSec - mixAttack, period)
        assertTrue(
            "Skyfall phaseSec from mix attack, not drums 0.576. " +
                "got=${aligned.phaseSec} Δbody=$fromBody Δattack=$fromAttack",
            abs(fromAttack) < 0.05,
        )
        assertTrue("must leave 0.576 by tenths, Δ=$fromBody", abs(fromBody) in 0.12..0.30)
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
    }

    @Test
    fun titanicMixAttackWhenDrumsNotDelayed() {
        val period = 0.571245460987
        val mixAttack = 0.075738712844
        val mixPcm = KickOffsetDiagnosisTest.kicks(
            bpm = 60.0 / period,
            phaseSec = mixAttack,
            decaySec = 0.04,
            f0 = 70.0,
            bars = 8,
        )
        val drums = KickOffsetDiagnosisTest.kicks(
            bpm = 60.0 / period,
            phaseSec = mixAttack,
            decaySec = 0.04,
            f0 = 70.0,
            bars = 8,
        )
        val lag = DrumPhase.stableBodyLagSec(mixPcm, drums, period)
        assertNull("no stable Demucs delay when mix and drums agree, lag=$lag", lag)
        val aligned = DrumPhase.align(
            analysis(105f, period, mixAttack, mixPcm),
            drums,
            mixPcm,
        )
        assertEquals("Titanic periodSec must not change", period, aligned.periodSec, 0.0)
        assertEquals(105f, aligned.bpm, 0.0f)
        val fromMix = wrapped(aligned.phaseSec - mixAttack, period)
        assertTrue(
            "Titanic stays on mix attack 0.076, not a 0.19s stem guess. " +
                "got=${aligned.phaseSec} Δ=$fromMix",
            abs(fromMix) < 0.04,
        )
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
        val json = aligned.toJson()
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(period, parsed.periodSec, 1e-12)
        assertEquals(aligned.phaseSec, parsed.phaseSec, 1e-12)
        assertEquals(105f, parsed.bpm, 1e-3f)
    }

    @Test
    fun demucsLagIsMeasuredNotHardcoded200ms() {
        val period = 0.800117626775
        val drumsBody = 0.576448941793
        for (lagSec in doubleArrayOf(0.12, 0.28)) {
            val mixAttack = drumsBody - lagSec
            val mixPcm = KickOffsetDiagnosisTest.rampedKicks(
                bpm = 60.0 / period,
                phaseSec = mixAttack,
                attackSec = lagSec,
                bars = 8,
            )
            val drums = KickOffsetDiagnosisTest.kicks(
                bpm = 60.0 / period,
                phaseSec = drumsBody,
                decaySec = 0.04,
                f0 = 55.0,
                bars = 8,
            )
            val measured = DrumPhase.stableBodyLagSec(mixPcm, drums, period)
            assertNotNull("lag $lagSec must be measured, got $measured", measured)
            assertTrue(
                "measured $measured must follow $lagSec, not 0.200",
                abs(measured!! - lagSec) < 0.05,
            )
            val aligned = DrumPhase.align(
                analysis(75f, period, drumsBody, mixPcm),
                drums,
                mixPcm,
            )
            val fromAttack = wrapped(aligned.phaseSec - mixAttack, period)
            assertTrue(
                "phase follows mix attack at lag=$lagSec, got=${aligned.phaseSec}",
                abs(fromAttack) < 0.05,
            )
            assertEquals(period, aligned.periodSec, 0.0)
        }
    }

    @Test
    fun stftHopShiftIsNotAKickHit() {
        val period = 0.571245460987
        val mixPhase = 0.075738712844
        val hop = KickGrid.HOP / 44100.0
        assertTrue("STFT hop ~12–16ms", hop in 0.010..0.016)
        val hopShift = wrapped(mixPhase + hop - mixPhase, period)
        assertTrue("a hop nudge is hundredths, not tenths", abs(hopShift) < 0.02)
    }

    @Test
    fun clickReadsPhaseSecNotKickPhaseWhenBothPresent() {
        val analysis = TrackAnalysis(
            bpm = 75f,
            beats = DoubleArray(0),
            downbeatOffset = 0.576448941793,
            duration = 8.0,
            kickPhaseSec = 0.376448941793,
            periodSec = 0.800117626775,
            phaseSec = 0.576448941793,
        )
        assertEquals(0.576448941793, analysis.clickPhaseSec(), 0.0)
        val engine = MetronomeClickEngine()
        engine.setAnalysis(analysis)
        assertEquals(TempoMath.secondsToMediaUs(0.576448941793), engine.kickPhaseUs)
        val mixWritten = analysis.copy(phaseSec = 0.376448941793, kickPhaseSec = 0.376448941793)
        engine.setAnalysis(mixWritten)
        assertEquals(TempoMath.secondsToMediaUs(0.376448941793), engine.kickPhaseUs)
    }

    private fun analysis(
        bpm: Float,
        period: Double,
        mixPhase: Double,
        pcm: StereoPcm,
    ): TrackAnalysis = TrackAnalysis(
        bpm = bpm,
        beats = DoubleArray(0),
        downbeatOffset = mixPhase,
        duration = pcm.samples.toDouble() / pcm.sampleRate,
        sampleRate = pcm.sampleRate,
        kickPhaseSec = mixPhase,
        periodSec = period,
        phaseSec = mixPhase,
    )

    private fun wrapped(delta: Double, period: Double): Double {
        val p = period.coerceAtLeast(1e-9)
        var m = delta % p
        if (m < 0.0) m += p
        return if (m <= p - m) m else m - p
    }
}
