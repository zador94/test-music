package com.rehearsal.app

import com.rehearsal.app.audio.WavWriter
import com.rehearsal.app.data.StemId
import java.io.File
import kotlin.math.sin

object WavFixtures {
    const val SAMPLE_RATE = 44100
    const val FRAMES = 4410

    fun writeStemSet(dir: File): Map<StemId, File> {
        dir.mkdirs()
        return StemId.entries.associateWith { id ->
            val file = File(dir, id.fileName)
            val left = FloatArray(FRAMES)
            val right = FloatArray(FRAMES)
            val amp = amplitude(id)
            val freq = frequency(id)
            for (i in 0 until FRAMES) {
                val s = (sin(2.0 * Math.PI * freq * i / SAMPLE_RATE) * amp).toFloat()
                left[i] = s
                right[i] = s * 0.9f
            }
            WavWriter(file, SAMPLE_RATE, channels = 2).use { it.writePlanarFloatStereo(left, right) }
            file
        }
    }

    fun amplitude(id: StemId): Float = when (id) {
        StemId.VOCALS -> 0.80f
        StemId.DRUMS -> 0.45f
        StemId.BASS -> 0.35f
        StemId.OTHER -> 0.25f
    }

    private fun frequency(id: StemId): Double = when (id) {
        StemId.VOCALS -> 440.0
        StemId.DRUMS -> 180.0
        StemId.BASS -> 80.0
        StemId.OTHER -> 700.0
    }
}
