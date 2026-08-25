package dev.autopilot.terminal.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCollectorAndDigestTest {

    @Test
    fun collectorExtractsExitCode() {
        val c = OutputCollector()
        c.onChunk("hello\n".toByteArray())
        assertNull(c.pollExitCode())
        c.onChunk("__EXIT_CODE:0__\n".toByteArray())
        assertEquals(0, c.pollExitCode())
        assertTrue(c.text().contains("hello"))
    }

    @Test
    fun collectorHandlesNonZeroExit() {
        val c = OutputCollector()
        c.onChunk("traceback...\n__EXIT_CODE:1__".toByteArray())
        assertEquals(1, c.pollExitCode())
    }

    @Test
    fun digestKeepsShortOutputAsIs() {
        val r = CommandRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val text = "line1\nline2"
        assertEquals(text, r.digest(text))
    }

    @Test
    fun digestTruncatesLongOutputKeepingHeadAndTail() {
        val r = CommandRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val head = "H".repeat(5000)
        val tail = "T".repeat(5000)
        val out = r.digest("$head\n$tail")
        assertFalse(out.length > CommandRunner.MAX_OUTPUT_CHARS + 100)
        assertTrue(out.contains("已截断"))
        assertTrue(out.startsWith("HHHH"))
        assertTrue(out.endsWith("TTTT"))
    }

    @Test
    fun markerRegexRemovesMarkerFromDigest() {
        val r = CommandRunner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val cleaned = r.digest("done __EXIT_CODE:0__")
        assertFalse(cleaned.contains("__EXIT_CODE"))
        assertTrue(cleaned.contains("done"))
    }
}

class ModelConfigMaskTest {

    @org.junit.Test
    fun maskedConfigNeverContainsRawKey() {
        val cfg = dev.autopilot.terminal.data.ModelConfig(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-super-secret-value-123",
            model = "test-model"
        )
        val masked = cfg.masked().toString()
        org.junit.Assert.assertFalse(masked.contains("sk-super-secret-value-123"))
        org.junit.Assert.assertTrue(masked.contains("***"))
    }
}
