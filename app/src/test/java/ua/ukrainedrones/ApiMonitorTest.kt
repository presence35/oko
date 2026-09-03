package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.data.parseSystemLog
import ua.ukrainedrones.data.serializeSystemLog

class ApiMonitorTest {

    @Test
    fun `serialize round trip`() {
        val entries = listOf(
            SystemEntry(1000L, SystemEntryKind.SDK_CHANGED, "SHA256: abc -> def"),
            SystemEntry(2000L, SystemEntryKind.SDK_CHECK_FAILED, "HTTP 503")
        )
        val raw = serializeSystemLog(entries)
        val parsed = parseSystemLog(raw)
        assertEquals(2, parsed.size)
        assertEquals(1000L, parsed[0].atMillis)
        assertEquals(SystemEntryKind.SDK_CHANGED, parsed[0].kind)
        assertEquals("SHA256: abc -> def", parsed[0].detail)
        assertEquals(2000L, parsed[1].atMillis)
        assertEquals(SystemEntryKind.SDK_CHECK_FAILED, parsed[1].kind)
        assertEquals("HTTP 503", parsed[1].detail)
    }

    @Test
    fun `parseSystemLog skips malformed lines`() {
        val raw = "not-a-number|SDK_CHANGED|detail\n2000|SDK_CHANGED|ok\nbad|data"
        val parsed = parseSystemLog(raw)
        assertEquals(1, parsed.size)
        assertEquals(2000L, parsed[0].atMillis)
    }

    @Test
    fun `parseSystemLog handles empty input`() {
        assertEquals(0, parseSystemLog("").size)
        assertEquals(0, parseSystemLog("\n\n").size)
    }

    @Test
    fun `parseSystemLog caps at MAX_ENTRIES`() {
        val lines = (1..200).map { "$it|SDK_CHANGED|detail $it" }.joinToString("\n")
        val parsed = parseSystemLog(lines)
        assertEquals(ApiMonitor.MAX_ENTRIES, parsed.size)
        assertEquals(101L, parsed[0].atMillis)
    }

    @Test
    fun `serialize handles special characters in detail`() {
        val entry = SystemEntry(100L, SystemEntryKind.SDK_CHANGED, "SHA256: aa:bb -> cc:dd (new)")
        val raw = serializeSystemLog(listOf(entry))
        val parsed = parseSystemLog(raw)
        assertEquals(1, parsed.size)
        assertEquals("SHA256: aa:bb -> cc:dd (new)", parsed[0].detail)
    }
}
