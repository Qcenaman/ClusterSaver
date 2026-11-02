package com.qcenaman.clustersaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezePlanTest {
    @Test
    fun parsesPmOutput() {
        val out = "package:com.whatsapp\npackage:com.spotify.music\n\nWARNING junk\npackage:bad name\n"
        assertEquals(listOf("com.whatsapp", "com.spotify.music"), FreezePlan.parsePackageList(out))
    }

    @Test
    fun neverFreezesProtectedOrKeptApps() {
        val installed = listOf("com.termux", "moe.shizuku.privileged.api", "com.whatsapp", "com.spotify.music", "com.qcenaman.clustersaver")
        val keep = FreezePlan.parseKeep("com.whatsapp, nonsense")
        assertEquals(listOf("com.spotify.music"), FreezePlan.candidates(installed, keep))
    }

    @Test
    fun scriptsSuspendAndRestore() {
        val f = FreezePlan.freezeScript(listOf("com.spotify.music", "evil;reboot"), 0)
        assertTrue(f.contains("pm suspend --user 0 com.spotify.music"))
        assertFalse(f.contains("reboot"))
        assertTrue(FreezePlan.restoreScript(listOf("com.spotify.music"), 0).contains("pm unsuspend --user 0 com.spotify.music"))
    }
}
