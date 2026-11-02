package com.qcenaman.clustersaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val host = Member("127.0.0.1:50052", "Tab", 10_000, 100)
    private val s24 = Member("192.168.1.20:50052", "S24", 7_000, 60)
    private val flip = Member("192.168.1.21:50052", "Flip", 4_000, 100)
    private val tiny = Member("192.168.1.22:50052", "Note9", 1_200, 100)

    @Test
    fun usableRespectsReserveAndShare() {
        assertEquals(8_976, Planner.usableMb(host))
        assertEquals(3_585, Planner.usableMb(s24))
        assertEquals(0, Planner.usableMb(Member("x", "x", 500, 100)))
    }

    @Test
    fun selectDropsTinyDevicesAndPutsHostFirst() {
        val sel = Planner.select(listOf(flip, tiny, s24, host))
        assertEquals(listOf(host, s24, flip), sel)
        assertEquals(listOf(8_976L, 3_585L, 2_976L), Planner.tensorSplit(sel))
    }

    @Test
    fun fitCheck() {
        val all = listOf(host, s24, flip)
        assertTrue(Planner.fits(all, 9L * 1024 * 1024 * 1024))
        assertFalse(Planner.fits(all, 18L * 1024 * 1024 * 1024))
    }

    @Test
    fun lostMembers() {
        val active = listOf(host.endpoint, s24.endpoint, flip.endpoint)
        val seen = mapOf(
            s24.endpoint to PeerSeen(leaving = true, lastSeenMs = 1_000),
            flip.endpoint to PeerSeen(leaving = false, lastSeenMs = 1_000),
        )
        assertEquals(listOf(s24.endpoint), Planner.lostMembers(active, seen, 5_000, 20_000))
        assertEquals(listOf(s24.endpoint, flip.endpoint), Planner.lostMembers(active, seen, 30_000, 20_000))
        assertEquals(listOf(flip.endpoint), Planner.lostMembers(listOf(host.endpoint, flip.endpoint), emptyMap(), 0, 20_000))
    }
}
