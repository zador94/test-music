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
    fun outputLatencyMsFromBufferSize() {
        assertEquals(80, ClickClock.outputLatencyMs(bufferSizeInFrames = 3528, sampleRate = 44100))
        assertEquals(0, ClickClock.outputLatencyMs(bufferSizeInFrames = 0, sampleRate = 44100))
        assertEquals(ClickClock.MAX_OUTPUT_LATENCY_MS, ClickClock.outputLatencyMs(1_000_000, 44100))
    }

    @Test
    fun queuedLatencyMsFromWrittenMinusHead() {
        assertEquals(80, ClickClock.queuedLatencyMs(3528, playbackHeadPosition = 0, sampleRate = 44100))
        assertEquals(0, ClickClock.queuedLatencyMs(3528, playbackHeadPosition = 3528, sampleRate = 44100))
        assertEquals(0, ClickClock.queuedLatencyMs(100, playbackHeadPosition = 400, sampleRate = 44100))
    }

    @Test
    fun writeLatencyMsCapsQueueToBufferSoStuckHeadCannotRace() {
        assertEquals(80, ClickClock.writeLatencyMs(queuedLatencyMs = 400, bufferLatencyMs = 80))
        assertEquals(40, ClickClock.writeLatencyMs(queuedLatencyMs = 40, bufferLatencyMs = 80))
        assertEquals(0, ClickClock.writeLatencyMs(queuedLatencyMs = 0, bufferLatencyMs = 80))
        assertEquals(120, ClickClock.writeLatencyMs(queuedLatencyMs = 120, bufferLatencyMs = 0))
    }

    @Test
    fun nextWriteMediaUsStepsByChunkNotByGrowingQueue() {
        val advance = ClickLayer.mediaAdvanceUs(1f)
        var cursor = ClickClock.nextWriteMediaUs(Long.MIN_VALUE, targetUs = 0L, advance)
        assertEquals(0L, cursor)
        var queuedUs = 0L
        repeat(20) { i ->
            queuedUs = minOf(queuedUs + advance, ClickClock.MAX_OUTPUT_LATENCY_MS * 1_000L)
            val wallUs = (i + 1) * advance
            cursor = ClickClock.nextWriteMediaUs(cursor, wallUs + queuedUs, advance)
        }
        assertEquals(20 * advance, cursor)
        val seekTarget = 5_000_000L
        assertEquals(seekTarget, ClickClock.nextWriteMediaUs(cursor, seekTarget, advance))
    }

    @Test
    fun clickWriteStaysLockedToPlayheadNotFreeRun() {
        val heard = 1_000_000L
        val queuedMs = ClickClock.queuedLatencyMs(3528, 0, 44100)
        val writeAt = ClickClock.scheduledMediaUs(heard, 1f, queuedMs)
        val freeRun = heard + 10 * ClickLayer.mediaAdvanceUs(1f)
        assertEquals(1_080_000L, writeAt)
        assertTrue("free-run cursor would limp vs the song", freeRun > writeAt + 500_000L)
    }

    @Test
    fun scheduledCursorAddsOutputLatencyInMediaTime() {
        assertEquals(80_000L, ClickClock.scheduledMediaUs(0L, speed = 1f, outputLatencyMs = 80))
        assertEquals(160_000L, ClickClock.scheduledMediaUs(0L, speed = 2f, outputLatencyMs = 80))
        assertEquals(1_040_000L, ClickClock.scheduledMediaUs(1_000_000L, speed = 0.5f, outputLatencyMs = 80))
        assertEquals(
            0L,
            ClickClock.scheduledMediaUs(0L, speed = 1f, outputLatencyMs = -12),
        )
        val capped = ClickClock.scheduledMediaUs(0L, speed = 1f, outputLatencyMs = 10_000)
        assertEquals(ClickClock.MAX_OUTPUT_LATENCY_MS * 1_000L, capped)
    }

    @Test
    fun nonFiniteSpeedFallsBackToOne() {
        val origin = ClickClock.Origin(mediaUs = 0L, wallNs = 0L, speed = Float.NaN, playing = true)
        val snap = ClickClock.snapshot(origin, nowNs = 1_000_000_000L)
        assertEquals(1_000_000L, snap.positionUs)
        assertEquals(1f, snap.speed, 1e-4f)
    }
}
