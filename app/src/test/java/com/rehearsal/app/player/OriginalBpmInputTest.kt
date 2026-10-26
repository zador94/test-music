package com.rehearsal.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalBpmInputTest {
    @Test
    fun partialAndEmptyOriginalBpmDoNotApply() {
        assertNull(PlaybackMath.parseOriginalBpm(""))
        assertNull(PlaybackMath.parseOriginalBpm("."))
        assertNull(PlaybackMath.parseOriginalBpm("1"))
        assertNull(PlaybackMath.parseOriginalBpm("10"))
        assertNull(PlaybackMath.parseOriginalBpm("12"))
        assertNull(PlaybackMath.parseOriginalBpm("29"))
        assertNull(PlaybackMath.parseOriginalBpm("241"))
        assertNull(PlaybackMath.parseOriginalBpm("abc"))
    }

    @Test
    fun typing102FromDefault120OnlyAppliesWhenComplete() {
        val keystrokes = listOf("1", "10", "102")
        val applied = keystrokes.map { PlaybackMath.parseOriginalBpm(it) }
        assertEquals(listOf(null, null, 102f), applied)
    }

    @Test
    fun firstKeystrokeDoesNotApplyOrChangeSpeed() {
        val draft = PlaybackMath.sanitizeBpmDraft("1")
        val commit = PlaybackMath.commitOriginalBpm(draft, 120f)
        assertNull("first char in Темп must not call setPlaybackSpeed", commit.applyBpm)
        assertEquals("120.0", commit.displayText)
        assertEquals(1f, PlaybackMath.playbackSpeed(120f, 120f), 1e-5f)
        listOf("", "1", "10", "12", ".").forEach { key ->
            assertNull(PlaybackMath.commitOriginalBpm(key, 120f).applyBpm)
        }
        val done = PlaybackMath.commitOriginalBpm("102", 120f)
        assertEquals(102f, done.applyBpm)
        val speed = PlaybackMath.playbackSpeed(120f, done.applyBpm!!)
        assertTrue(speed.isFinite())
        assertTrue(speed in PlaybackMath.MIN_SPEED..PlaybackMath.MAX_SPEED)
    }

    @Test
    fun inProgressTextMustNotBuildInvertedSliderRange() {
        // Старый UI: originalBpmText.toFloatOrNull() на «12» → min 30, max 24 → coerceIn crash.
        val typed = 12f
        val oldMin = (typed * 0.5f).coerceAtLeast(30f)
        val oldMax = (typed * 2.0f).coerceAtMost(240f)
        assertTrue("regression fixture: typing 12 inverted the slider", oldMin > oldMax)

        val range = PlaybackMath.metronomeSliderRange(120f)
        assertTrue(range.start <= range.endInclusive)
        val value = PlaybackMath.clampMetronomeBpm(120f, 120f)
        assertTrue(value in range.start..range.endInclusive)
        PlaybackMath.clampMetronomeBpm(120f, 12f)
        PlaybackMath.clampMetronomeBpm(120f, 1f)
        PlaybackMath.clampMetronomeBpm(Float.NaN, Float.POSITIVE_INFINITY)
    }

    @Test
    fun decimalOriginalBpmParsesAndCommitsOneTenth() {
        assertEquals(104.6f, PlaybackMath.parseOriginalBpm("104.6"))
        val commit = PlaybackMath.commitOriginalBpm("104.6", 120f)
        assertEquals(104.6f, commit.applyBpm)
        assertEquals("104.6", commit.displayText)
        assertEquals(1f, PlaybackMath.playbackSpeed(104.6f, commit.applyBpm!!), 1e-5f)
        assertEquals(104f, PlaybackMath.parseOriginalBpm("104."))
        assertEquals(104f, PlaybackMath.parseOriginalBpm("104"))
    }

    @Test
    fun applying102WhilePlayingDoesNotThrowOrProduceInvalidSpeed() {
        var appliedSpeed = 1f
        fun applyOriginal(original: Float, metronome: Float) {
            val parsed = PlaybackMath.parseOriginalBpm(original.toInt().toString())
            assertNotNull(parsed)
            val speed = PlaybackMath.playbackSpeed(metronome, parsed!!)
            assertTrue(speed.isFinite())
            assertTrue(speed in PlaybackMath.MIN_SPEED..PlaybackMath.MAX_SPEED)
            appliedSpeed = speed
        }
        applyOriginal(120f, 120f)
        applyOriginal(102f, 120f)
        assertEquals(120f / 102f, appliedSpeed, 1e-5f)
    }

    @Test
    fun repeatedDecreasingSpeedsWhilePlayingDoNotThrow() {
        var last = 1f
        for (metro in 120 downTo 60) {
            val speed = PlaybackMath.playbackSpeed(metro.toFloat(), 102f)
            assertTrue("speed=$speed at metro=$metro", speed.isFinite())
            assertTrue(speed in PlaybackMath.MIN_SPEED..PlaybackMath.MAX_SPEED)
            last = speed
        }
        assertEquals(60f / 102f, last, 1e-5f)
    }
}
