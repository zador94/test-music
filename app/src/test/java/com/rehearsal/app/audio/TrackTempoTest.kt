package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Прикладные кейсы поверх порта beat-sense: Skyfall / весна / фанк / KickGrid.
 */
class TrackTempoTest {
    @Test
    fun tempoOfTrackSeventyFiveClicks() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 75.0, bars = 8, beatsPerBar = 4)
        val tr = TrackTempo.tempoOfTrack(pcm)
        println("synthetic 75 clicks bpm=${tr.bpm} phase=${tr.phaseSec}")
        assertEquals("75 clicks, was ${tr.bpm}", 75.0, tr.bpm, 2.0)
        assertTrue("must not be 131/150/153, was ${tr.bpm}", tr.bpm < 110.0)
    }

    @Test
    fun tempoOfTrackOneOhFiveClicks() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 105.0, bars = 8, beatsPerBar = 4)
        val tr = TrackTempo.tempoOfTrack(pcm)
        println("synthetic 105 clicks bpm=${tr.bpm} phase=${tr.phaseSec}")
        assertEquals("105 clicks, was ${tr.bpm}", 105.0, tr.bpm, 2.0)
        assertTrue("must not be 210, was ${tr.bpm}", abs(tr.bpm - 210.0) > 20.0)
    }

    @Test
    fun tempoOfTrackOneTwentyClicks() {
        val pcm = TempoDetectorTest.stereoClicks(bpm = 120.0, bars = 8, beatsPerBar = 4)
        val tr = TrackTempo.tempoOfTrack(pcm)
        assertEquals("120 clicks, was ${tr.bpm}", 120.0, tr.bpm, 2.0)
    }

    @Test
    fun skyfallLikeSeventyFiveWithHatsIsNot153() {
        val pcm = BalladTempoTest.balladGroove(bpm = 75.0, hats = true, pianoBleed = true)
        val mix = TempoDetector.analyze(pcm)
        assertTrue("Skyfall-like mix was ${mix.bpm}", abs(mix.bpm - 153f) > 8f)
        assertTrue("Skyfall-like mix was ${mix.bpm}", abs(mix.bpm - 131f) > 8f)
        assertEquals("Skyfall-like ~75, was ${mix.bpm}", 75.0, mix.bpm.toDouble(), 2.0)
    }

    @Test
    fun funkOneOhFourPointSixIsNot210() {
        val pcm = KickFunkGridTest.funkGroove(bpm = 104.6, phaseSec = 0.08, bars = 8)
        val mix = TempoDetector.analyze(pcm)
        assertEquals("funk mix, was ${mix.bpm}", 104.6, mix.bpm.toDouble(), 3.0)
        assertTrue("funk must not be 210, was ${mix.bpm}", abs(mix.bpm - 210f) > 20f)
    }

    @Test
    fun refineKeepsFileTempoInsteadOfDrumsOctave() {
        val file = TempoDetector.analyze(TempoDetectorTest.stereoClicks(77.5, 8, 4))
        val drums = TempoDetectorTest.stereoClicks(155.0, 8, 4)
        val refined = KickGrid.refine(file.copy(bpm = 77.5f), drums)
        assertEquals("file 77.5 must not become drums 155, was ${refined.bpm}", 77.5, refined.bpm.toDouble(), 1.0)
        assertTrue("stale 153 still folds", abs(TempoMath.foldDoubleBallad(153.0) - 76.5) < 0.05)
    }
}
