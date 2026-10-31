package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 0.3.42: [DrumPhase.align] не двигает всю сетку одним phaseSec.
 * Каждую долю — к онсету микса, не к телу drums.wav. periodSec/bpm те же.
 */
class DrumPhaseLockTest {
    @Test
    fun alignDoesNotRewriteEmptyBeatsFromDrumsPhase() {
        val period = 0.800117626775
        val drumsBody = 0.576448941793
        val drums = KickOffsetDiagnosisTest.kicks(
            bpm = 60.0 / period,
            phaseSec = drumsBody,
            decaySec = 0.04,
            f0 = 55.0,
            bars = 8,
        )
        val mixPcm = KickOffsetDiagnosisTest.kicks(
            bpm = 60.0 / period,
            phaseSec = 0.376448941793,
            decaySec = 0.04,
            f0 = 70.0,
            bars = 8,
        )
        val energyComb = DrumPhase.energyCombPhase(drums, period)
        assertTrue(
            "drums energy comb still on 0.576, got $energyComb",
            abs(wrapped(energyComb - drumsBody, period)) < 0.06,
        )
        val aligned = DrumPhase.align(
            analysis(75f, period, drumsBody, drums, DoubleArray(0)),
            drums,
            mixPcm,
        )
        assertEquals("Skyfall periodSec must not change", period, aligned.periodSec, 0.0)
        assertEquals(75f, aligned.bpm, 0.0f)
        assertEquals(KickGrid.VERSION, aligned.kickGridVersion)
        assertEquals(0, aligned.beats.size)
        assertEquals(
            "empty beats[]: origin stays, drums body does not become the grid",
            drumsBody,
            aligned.phaseSec,
            0.0,
        )
        assertEquals(0.0, aligned.downbeatOffset, 0.0)
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
    }

    @Test
    fun alignSnapsBeatsToMixNotDrumsBody() {
        val period = 0.5
        val mixAttack = 0.08
        val drumsBody = 0.28
        val mixPcm = KickOffsetDiagnosisTest.kicks(
            bpm = 120.0,
            phaseSec = mixAttack,
            decaySec = 0.04,
            f0 = 70.0,
            bars = 8,
        )
        val drums = KickOffsetDiagnosisTest.kicks(
            bpm = 120.0,
            phaseSec = drumsBody,
            decaySec = 0.04,
            f0 = 55.0,
            bars = 8,
        )
        val draft = DoubleArray(16) { i -> mixAttack + i * period }
        val aligned = DrumPhase.align(
            analysis(120f, period, mixAttack, mixPcm, draft),
            drums,
            mixPcm,
        )
        assertEquals(period, aligned.periodSec, 0.0)
        assertEquals(120f, aligned.bpm, 0.0f)
        assertEquals(0.0, aligned.downbeatOffset, 0.0)
        assertTrue("need snapped beats, was ${aligned.beats.size}", aligned.beats.size >= 8)
        for (i in 0 until minOf(8, aligned.beats.size)) {
            val want = mixAttack + i * period
            val errMix = abs(aligned.beats[i] - want)
            val errDrums = abs(aligned.beats[i] - (drumsBody + i * period))
            assertTrue(
                "beat[$i]=${aligned.beats[i]} must stay on mix $want, not drums body. " +
                    "Δmix=${errMix * 1000}ms Δdrums=${errDrums * 1000}ms",
                errMix < 0.030,
            )
            assertTrue("must not rewrite the grid onto drums body", errDrums > 0.10)
        }
        val origin = aligned.phaseSec
        assertTrue("origin is first beat in the period, was $origin", abs(origin - mixAttack) < 0.030)
        assertEquals(aligned.phaseSec, aligned.kickPhaseSec, 1e-12)
    }

    @Test
    fun alignKeepsPeriodBpmAndBeatClassOffset() {
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
        val draft = DoubleArray(12) { i -> mixAttack + i * period }
        val aligned = DrumPhase.align(
            analysis(105f, period, mixAttack, mixPcm, draft),
            drums,
            mixPcm,
        )
        assertEquals("Titanic periodSec must not change", period, aligned.periodSec, 0.0)
        assertEquals(105f, aligned.bpm, 0.0f)
        assertEquals(0.0, aligned.downbeatOffset, 0.0)
        assertEquals(KickGrid.VERSION, aligned.kickGridVersion)
        val json = aligned.toJson()
        val parsed = TrackAnalysis.fromJson(json)!!
        assertEquals(period, parsed.periodSec, 1e-12)
        assertEquals(105f, parsed.bpm, 1e-3f)
        assertEquals(aligned.beats.size, parsed.beats.size)
        assertEquals(0.0, parsed.downbeatOffset, 0.0)
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
            downbeatOffset = 0.0,
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
        assertEquals(0, engine.beatTimesUs.size)
    }

    private fun analysis(
        bpm: Float,
        period: Double,
        mixPhase: Double,
        pcm: StereoPcm,
        beats: DoubleArray,
    ): TrackAnalysis = TrackAnalysis(
        bpm = bpm,
        beats = beats,
        downbeatOffset = 0.0,
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
