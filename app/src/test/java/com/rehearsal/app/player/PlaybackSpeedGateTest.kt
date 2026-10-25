package com.rehearsal.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedGateTest {
    @Test
    fun repeatedSpeedDownWhilePlayingDoesNotPauseOrSpamSetPlaybackSpeed() {
        var now = 0L
        val gate = PlaybackSpeedGate(minIntervalMs = 80L, nowMs = { now })
        val applied = ArrayList<Float>()
        var paused = false
        var reloaded = false
        var errors = 0

        fun pushToPlayer(speed: Float) {
            if (!speed.isFinite() || speed !in PlaybackMath.MIN_SPEED..PlaybackMath.MAX_SPEED) {
                errors++
                return
            }
            applied.add(speed)
        }

        // Слайдер 120→60 за ~160 мс, ~80 тиков — как onValueChange при drag вниз.
        val original = 120f
        for (i in 0 until 80) {
            now = i * 2L
            val metro = 120f - i * (60f / 79f)
            val speed = PlaybackMath.playbackSpeed(metro, original)
            gate.request(speed)?.let(::pushToPlayer)
        }
        now = 400L
        gate.poll()?.let(::pushToPlayer)

        assertFalse(paused)
        assertFalse(reloaded)
        assertEquals(0, errors)
        assertTrue("must apply at least the first and the latest speed-down", applied.size >= 2)
        assertTrue(
            "must not call setPlaybackSpeed on every slider tick (was ${applied.size})",
            applied.size < 12,
        )
        applied.forEach { s ->
            assertTrue(s.isFinite())
            assertTrue(s in PlaybackMath.MIN_SPEED..PlaybackMath.MAX_SPEED)
        }
        val latestRequested = PlaybackMath.playbackSpeed(60f, original)
        assertEquals(PlaybackSpeedGate.quantize(latestRequested)!!, applied.last(), 1e-5f)
        assertTrue(applied.last() < applied.first())
    }

    @Test
    fun speedUpAndDownStayFiniteAndDoNotReload() {
        var now = 0L
        val gate = PlaybackSpeedGate(minIntervalMs = 50L, nowMs = { now })
        var reloads = 0
        val speeds = ArrayList<Float>()
        val metros = (120..180) + (179 downTo 90)
        metros.forEachIndexed { i, metro ->
            now = i * 10L
            gate.request(PlaybackMath.playbackSpeed(metro.toFloat(), 120f))?.let { speeds.add(it) }
        }
        now += 200
        gate.poll()?.let { speeds.add(it) }
        assertEquals(0, reloads)
        assertTrue(speeds.isNotEmpty())
        speeds.forEach {
            assertTrue(it.isFinite())
            assertTrue(it in 0.5f..2.0f)
        }
    }

    @Test
    fun nanAndZeroNeverLeaveTheGate() {
        val gate = PlaybackSpeedGate(nowMs = { 1_000L })
        assertEquals(null, gate.request(Float.NaN))
        assertEquals(null, gate.request(Float.NEGATIVE_INFINITY))
        val ok = gate.request(1f, force = true)
        assertEquals(1f, ok)
    }
}
