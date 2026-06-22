package dev.ipf.darkmatter.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalVerTest {
    @Test
    fun comparesCalVerSegmentsNumerically() {
        assertTrue(CalVer.compare("2026.6.20", "2026.6.10") > 0)
        assertTrue(CalVer.compare("2026.10.1", "2026.6.30") > 0)
        assertTrue(CalVer.compare("2027.1", "2026.12.99") > 0)
        assertEquals(0, CalVer.compare("2026.6", "2026.6.0"))
    }

    @Test
    fun ignoresDebugSuffixWhenComparingInstalledVersionName() {
        assertEquals(0, CalVer.compare("2026.6.21", "2026.6.21-debug"))
        assertTrue(CalVer.compare("2026.6.22", "2026.6.21-debug") > 0)
    }

    @Test
    fun countsDistinctReleaseVersionsNewerThanInstalled() {
        assertEquals(
            2,
            CalVer.releasesBehind(
                "2026.6.10",
                listOf("2026.6.10", "2026.6.20", "2026.6.20", "2026.7.1"),
            ),
        )
    }
}
