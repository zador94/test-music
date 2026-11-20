package com.rehearsal.app.player

import com.rehearsal.app.audio.BeatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopTest {
    private val beatUs = BeatClock.intervalUs(120f)

    @Test
    fun regionShorterThanOneBeatStaysOff() {
        assertNull(AbLoop.arm(0L, 499L, beatUs, 60_000L))
        assertNull(AbLoop.arm(20_000L, 10_000L, beatUs, 60_000L))
        assertNull(AbLoop.arm(10_000L, 10_000L, beatUs, 60_000L))
        val loop = AbLoop.arm(0L, 500L, beatUs, 60_000L)
        assertEquals(0L, loop!!.aMs)
        assertEquals(500L, loop.bMs)
    }

    @Test
    fun playbackAtBJumpsAndJustBeforeStays() {
        val loop = AbLoop.arm(10_000L, 20_000L, beatUs, 180_000L)!!
        assertFalse(AbLoop.jumpsToA(9_000L, loop))
        assertFalse(AbLoop.jumpsToA(19_999L, loop))
        assertTrue(AbLoop.jumpsToA(20_000L, loop))
    }

    @Test
    fun snapLandsOnTheGrid() {
        assertEquals(0L, AbLoop.snapMs(100L, 0L, beatUs, 60_000L))
        assertEquals(500L, AbLoop.snapMs(300L, 0L, beatUs, 60_000L))
        assertEquals(500L, AbLoop.snapMs(250L, 0L, beatUs, 60_000L))
        assertEquals(500L, AbLoop.snapMs(500L, 0L, beatUs, 60_000L))
        assertEquals(80L, AbLoop.snapMs(0L, 80_000L, beatUs, 60_000L))
        assertEquals(580L, AbLoop.snapMs(400L, 80_000L, beatUs, 60_000L))
        assertEquals(1_000L, AbLoop.snapMs(5_000L, 0L, beatUs, 1_000L))
    }

    @Test
    fun appliedScaleIsTheGapBetweenAAndB() {
        val loop = AbLoop.arm(20_000L, 30_000L, beatUs, 120_000L)!!
        assertEquals(10_000L, AbLoop.spanMs(loop))
        assertEquals(0L, AbLoop.localMs(20_000L, loop))
        assertEquals(5_000L, AbLoop.localMs(25_000L, loop))
        assertEquals(10_000L, AbLoop.localMs(30_000L, loop))
        assertEquals(25_000L, AbLoop.songMs(5_000L, loop))
        assertEquals(20_000L, AbLoop.songMs(0L, loop))
        assertEquals(30_000L, AbLoop.songMs(10_000L, loop))
    }

    @Test
    fun seekBeforeAWhileLoopIsOnLandsOnA() {
        val loop = AbLoop.arm(27_000L, 37_000L, beatUs, 120_000L)!!
        assertEquals(0L, AbLoop.localMs(23_000L, loop))
        assertEquals(27_000L, AbLoop.clampSongMs(23_000L, loop))
        assertEquals(30_000L, AbLoop.clampSongMs(30_000L, loop))
        assertEquals(36_999L, AbLoop.clampSongMs(50_000L, loop))
    }

    @Test
    fun gateRemembersAppliedLoopUntilReset() {
        val gate = AbLoopGate()
        assertNull(gate.current())
        val loop = AbLoop.arm(20_000L, 30_000L, beatUs, 120_000L)!!
        gate.apply(loop)
        assertEquals(loop, gate.current())
        gate.apply(null)
        assertNull(gate.current())
        gate.apply(loop)
        gate.reset()
        assertNull(gate.current())
    }
}
