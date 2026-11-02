package com.qcenaman.clustersaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptsTest {
    @Test
    fun serverCommandHasSplitAndRpc() {
        val s = Scripts.startServer(
            "Qwen3-14B-Q4_K_M.gguf",
            listOf("127.0.0.1:50052", "192.168.1.20:50052"),
            listOf(8976, 3585),
            4096,
            4,
        )
        assertTrue(s.contains("--rpc 127.0.0.1:50052,192.168.1.20:50052"))
        assertTrue(s.contains("--tensor-split 8976,3585"))
        assertTrue(s.contains("-ngl 999"))
        assertTrue(s.contains("\$HOME/models/Qwen3-14B-Q4_K_M.gguf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeFileName() {
        Scripts.prepareModel("https://x/y.gguf", "a b;rm -rf.gguf")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadEndpoint() {
        Scripts.startServer("m.gguf", listOf("1.2.3.4:5;reboot"), listOf(1), 4096, 4)
    }

    @Test
    fun quoting() {
        assertEquals("'it'\\''s'", Scripts.quote("it's"))
    }

    @Test
    fun parseSize() {
        assertEquals(1234L, Scripts.parseSize("downloading\nSIZE=1234\n"))
        assertNull(Scripts.parseSize("nothing"))
    }

    @Test
    fun fileFromUrl() {
        assertEquals("m.gguf", Scripts.fileFromUrl("https://huggingface.co/a/b/resolve/main/m.gguf?download=true"))
        assertTrue(Scripts.isValidModelFile("Qwen3-1.7B-Q4_K_M.gguf"))
        assertFalse(Scripts.isValidModelFile("readme.md"))
    }

    @Test
    fun rpcBindsWhereAsked() {
        assertTrue(Scripts.startRpc("0.0.0.0", 50052, 6).contains("rpc-server -H 0.0.0.0 -p 50052 -t 6 -c"))
    }
}
