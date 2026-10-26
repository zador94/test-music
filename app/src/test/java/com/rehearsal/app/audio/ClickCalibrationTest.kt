package com.rehearsal.app.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ClickCalibrationTest {
    @Test
    fun clampAndMicros() {
        assertEquals(0, ClickCalibration.clampMs(0))
        assertEquals(-400, ClickCalibration.clampMs(-400))
        assertEquals(-500, ClickCalibration.clampMs(-900))
        assertEquals(500, ClickCalibration.clampMs(900))
        assertEquals(80_000L, ClickCalibration.toUs(80))
        assertEquals(-150_000L, ClickCalibration.toUs(-150))
        assertEquals(-180_000L, ClickCalibration.toUs(-180))
        assertEquals(-500_000L, ClickCalibration.toUs(-500))
        assertEquals(-180, ClickCalibration.DEFAULT_MS)
        assertEquals("click_shift_ms_v28", ClickCalibration.KEY_SHIFT_MS)
        assertTrue(ClickCalibration.KEY_SHIFT_MS != ClickCalibration.LEGACY_KEY_SHIFT_MS)
        assertTrue(ClickCalibration.KEY_SHIFT_MS != ClickCalibration.LEGACY_KEY_SHIFT_MS_V21)
        assertEquals(199, ClickCalibration.sliderSteps())
    }

    @Test
    fun missingCurrentKeyUsesDeviceDefaultNotLegacy() {
        assertEquals(-180, ClickCalibration.resolveStoredMs(hasCurrentKey = false, currentValue = 0))
        assertEquals(-180, ClickCalibration.resolveStoredMs(hasCurrentKey = false, currentValue = -170))
        assertEquals(-180, ClickCalibration.resolveStoredMs(hasCurrentKey = false, currentValue = 40))
        assertEquals(-80, ClickCalibration.resolveStoredMs(hasCurrentKey = true, currentValue = -80))
        assertEquals(-180, ClickCalibration.resolveStoredMs(hasCurrentKey = true, currentValue = -180))
        assertEquals(-500, ClickCalibration.resolveStoredMs(hasCurrentKey = true, currentValue = -900))
    }

    @Test
    fun calibrationOffsetShiftsClicks() {
        val bpm = 120f
        val engine = engine(bpm)
        engine.setAnalysis(
            TrackAnalysis(
                bpm = bpm,
                beats = DoubleArray(0),
                downbeatOffset = 0.0,
                duration = 4.0,
                kickPhaseSec = 0.08,
            ),
        )
        val base = clickTimes(engine, seconds = 2)
        assertTrue(base.any { abs(it - 80_000L) < 8_000L })

        engine.calibrationUs = ClickCalibration.toUs(40)
        assertEquals(120_000L, engine.gridOriginUs)
        val later = clickTimes(engine, seconds = 2)
        assertTrue("plus ms must move click later, was $later", later.any { abs(it - 120_000L) < 8_000L })
        assertTrue(later.none { abs(it - 80_000L) < 8_000L })

        engine.calibrationUs = ClickCalibration.toUs(-80)
        assertEquals(0L, engine.gridOriginUs)
        val zero = clickTimes(engine, seconds = 2)
        assertTrue("minus 80ms from 80ms phase lands on t=0, was $zero", zero.any { it < 8_000L })
    }

    @Test
    fun gridOriginAddsCalibrationModInterval() {
        val interval = BeatClock.intervalUs(120f)
        assertEquals(80_000L, BeatClock.gridOriginUs(80_000L, 0L, interval))
        assertEquals(120_000L, BeatClock.gridOriginUs(80_000L, 40_000L, interval))
        assertEquals(0L, BeatClock.gridOriginUs(80_000L, -80_000L, interval))
        assertEquals(interval - 50_000L, BeatClock.gridOriginUs(0L, -50_000L, interval))
    }

    private fun engine(originalBpm: Float): MetronomeClickEngine {
        val engine = MetronomeClickEngine()
        engine.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(ClickLayer.SAMPLE_RATE)
                .setChannelCount(2)
                .build(),
        )
        engine.originalBpm = originalBpm
        engine.enabled = true
        return engine
    }

    private fun clickTimes(engine: MetronomeClickEngine, seconds: Int): List<Long> {
        val dest = ByteBuffer.allocateDirect(ClickLayer.SAMPLE_RATE * seconds * ClickLayer.BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder())
        dest.limit(dest.capacity())
        dest.position(0)
        ClickLayer.render(dest, engine, 0L, 1f)
        dest.position(0)
        dest.limit(dest.capacity())
        val shorts = dest.duplicate().order(dest.order()).asShortBuffer()
        val frames = shorts.limit() / 2
        val threshold = 6000
        val minGap = ClickLayer.SAMPLE_RATE / 20
        val times = ArrayList<Long>()
        var i = 0
        while (i < frames) {
            val s = abs(shorts.get(i * 2).toInt())
            if (s > threshold) {
                times.add(BeatClock.sampleToMediaUs(i.toLong(), ClickLayer.SAMPLE_RATE))
                i += minGap
            } else {
                i++
            }
        }
        return times
    }
}
