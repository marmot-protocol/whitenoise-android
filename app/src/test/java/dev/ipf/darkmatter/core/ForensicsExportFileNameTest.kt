package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ForensicsExportFileNameTest {
    @Test
    fun publicFileNameUsesModeStableShortGroupPrefixAndTimestamp() {
        assertEquals(
            "darkmatter-forensics-public-abcdef123456-1710000000000.json",
            ForensicsExportFileName.build(
                groupIdHex = "abcdef1234567890",
                mode = "public",
                nowMillis = 1_710_000_000_000L,
            ),
        )
    }

    @Test
    fun sensitiveFileNameUsesModeStableShortGroupPrefixAndTimestamp() {
        assertEquals(
            "darkmatter-forensics-sensitive-abcdef123456-1710000000000.json",
            ForensicsExportFileName.build(
                groupIdHex = "abcdef1234567890",
                mode = "sensitive",
                nowMillis = 1_710_000_000_000L,
            ),
        )
    }

    @Test
    fun fileNameFallsBackWhenGroupIdHasNoSafeCharacters() {
        assertEquals(
            "darkmatter-forensics-public-group-42.json",
            ForensicsExportFileName.build(groupIdHex = "!!!", mode = "public", nowMillis = 42L),
        )
    }
}
