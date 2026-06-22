package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfilePseudonymGeneratorTest {
    @Test
    fun seedDerivationMatchesMarmotCoreDefaultPseudonyms() {
        assertEquals(
            "Agile Lynx",
            ProfilePseudonymGenerator.fromSeed("0".repeat(64)),
        )
        assertEquals(
            "Silver Swan",
            ProfilePseudonymGenerator.fromSeed("1".repeat(64)),
        )
        assertEquals(
            "Swift Swan",
            ProfilePseudonymGenerator.fromSeed("ab".repeat(32)),
        )
    }

    @Test
    fun entropyIsHexEncodedBeforeUsingTheCoreSelector() {
        assertEquals(
            ProfilePseudonymGenerator.fromSeed("00".repeat(32)),
            ProfilePseudonymGenerator.fromEntropy(ByteArray(32) { 0x00 }),
        )
        assertEquals(
            ProfilePseudonymGenerator.fromSeed("ff".repeat(32)),
            ProfilePseudonymGenerator.fromEntropy(ByteArray(32) { 0xff.toByte() }),
        )
    }

    @Test
    fun differentSeedsCanProduceDifferentPseudonyms() {
        assertNotEquals(
            ProfilePseudonymGenerator.fromSeed("0".repeat(64)),
            ProfilePseudonymGenerator.fromSeed("1".repeat(64)),
        )
    }
}
