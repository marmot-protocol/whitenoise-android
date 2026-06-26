package dev.ipf.whitenoise.android.core

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
    fun wordListsStayInLockstepWithMarmotCore() {
        assertEquals(32, ProfilePseudonymGenerator.adjectiveCount)
        assertEquals(32, ProfilePseudonymGenerator.nounCount)
        assertEquals(
            "749c028c82d85652c6078f5d4c3c42766fa5b64078d2422754d988dfd4aee4db",
            ProfilePseudonymGenerator.wordListFingerprint(),
        )
    }

    @Test
    fun randomRetriesWhenGeneratedPseudonymMatchesExcluded() {
        var roll = 0
        val entropies =
            listOf(
                ByteArray(32) { 0x00 },
                ByteArray(32) { 0x01 },
            )

        assertEquals(
            ProfilePseudonymGenerator.fromEntropy(entropies[1]),
            ProfilePseudonymGenerator.random(excluding = " Agile Lynx ") {
                entropies[roll++]
            },
        )
        assertEquals(2, roll)
    }

    @Test
    fun randomFallsBackToNextPseudonymWhenRetriesKeepColliding() {
        var rollCount = 0

        assertEquals(
            "Agile Moose",
            ProfilePseudonymGenerator.random(excluding = "Agile Lynx") {
                rollCount += 1
                ByteArray(32) { 0x00 }
            },
        )
        assertEquals(8, rollCount)
    }

    @Test
    fun differentSeedsCanProduceDifferentPseudonyms() {
        assertNotEquals(
            ProfilePseudonymGenerator.fromSeed("0".repeat(64)),
            ProfilePseudonymGenerator.fromSeed("1".repeat(64)),
        )
    }
}
