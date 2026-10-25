package com.rehearsal.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickClockTest {
    @Test
    fun pausedDoesNotAdvanceMediaTime() {
        val origin = ClickClock.Origin(
            mediaUs = 1_250_000L,
            wallNs = 1_000_000_000L,
            speed = 1.5f,
            playing = false,
        )
        val snap = ClickClock.snapshot(origin, nowNs = 5_000_000_000L)
        assertEquals(1_250_000L, snap.positionUs)
        assertFalse(snap.playing)
        assertEquals(1.5f, snap.speed, 1e-4f)
    }

    @Test
    fun playingInterpolatesSongTimeLikeWeb() {
        val origin = ClickClock.Origin(
            mediaUs = 500_000L,
            wallNs = 0L,
            speed = 1f,
            playing = true,
        )
        val oneSec = ClickClock.snapshot(origin, nowNs = 1_000_000_000L)
        assertEquals(1_500_000L, oneSec.positionUs)
        assertTrue(oneSec.playing)
    }

    @Test
    fun speedScalesElapsedMediaTime() {
        val origin = ClickClock.Origin(
            mediaUs = 0L,
            wallNs = 0L,
            speed = 2f,
            playing = true,
        )
        val halfSecWall = ClickClock.snapshot(origin, nowNs = 500_000_000L)
        assertEquals(1_000_000L, halfSecWall.positionUs)
        val slow = ClickClock.snapshot(origin.copy(speed = 0.5f), nowNs = 1_000_000_000L)
        assertEquals(500_000L, slow.positionUs)
    }

    @Test
    fun snapshotDoesNotNeedAPlayer() {
        val snap = ClickClock.snapshot(ClickClock.Origin(playing = true), nowNs = 0L)
        assertEquals(0L, snap.positionUs)
        assertTrue(snap.playing)
    }

    @Test
    fun workerThreadCanReadSnapshotWithoutThrowing() {
        val origin = ClickClock.Origin(mediaUs = 0L, wallNs = 0L, speed = 1f, playing = true)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread({
            try {
                repeat(200) {
                    ClickClock.snapshot(origin, System.nanoTime())
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "metronome-click")
        worker.start()
        worker.join()
        assertEquals("click worker must not crash on snapshot", null, error.get())
    }

    @Test
    fun nonFiniteSpeedFallsBackToOne() {
        val origin = ClickClock.Origin(mediaUs = 0L, wallNs = 0L, speed = Float.NaN, playing = true)
        val snap = ClickClock.snapshot(origin, nowNs = 1_000_000_000L)
        assertEquals(1_000_000L, snap.positionUs)
        assertEquals(1f, snap.speed, 1e-4f)
    }
}
