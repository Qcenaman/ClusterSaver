package com.qcenaman.clustersaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementTest {
    @Test
    fun roundTrip() {
        val a = Announcement("S24|Ultra", "192.168.1.20", 50052, 6100, 60, ready = true, leaving = false)
        val b = Announcement.decode(a.encode())!!
        assertEquals("S24 Ultra", b.name)
        assertEquals("192.168.1.20:50052", b.endpoint)
        assertEquals(6100, b.freeMb)
        assertEquals(60, b.sharePct)
        assertEquals(true, b.ready)
        assertEquals(false, b.leaving)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Announcement.decode("hello"))
        assertNull(Announcement.decode("CS1|x|not-an-ip|50052|1|1|1|0"))
        assertNull(Announcement.decode("CS1|x|10.0.0.2|99999|1|1|1|0"))
        assertNull(Announcement.decode("CS2|x|10.0.0.2|50052|1|1|1|0"))
    }
}
