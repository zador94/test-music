package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class WavReaderKickGridTest {
    @Test
    fun readStereoRoundtripThenKickGridStampsVersion() {
        val pcm = KickGridTest.lowBandKicks(bpm = 104.6, phaseSec = 0.08, bars = 4, beatsPerBar = 4)
        val file = File.createTempFile("drums", ".wav")
        try {
            WavWriter(file, pcm.sampleRate, channels = 2).use { writer ->
                writer.writePlanarFloatStereo(pcm.left, pcm.right)
            }
            val read = WavReader.readStereo(file)
            assertEquals(pcm.sampleRate, read.sampleRate)
            assertEquals(pcm.samples, read.samples)
            assertTrue(abs(read.left[100] - pcm.left[100]) < 1e-5f)
            val refined = KickGrid.refine(
                TrackAnalysis.fallbackGrid(read.samples, read.sampleRate).copy(kickGridVersion = 0),
                read,
            )
            assertEquals(KickGrid.VERSION, refined.kickGridVersion)
            assertTrue(!KickGrid.needsRefresh(refined))
        } finally {
            file.delete()
        }
    }
}
