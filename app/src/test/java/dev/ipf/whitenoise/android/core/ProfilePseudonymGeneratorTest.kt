package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfilePseudonymGeneratorTest {
    @Test
    fun seedDerivationMatchesMarmotCoreDefaultPseudonyms() {
        assertEquals(
            "Solar Mallard",
            ProfilePseudonymGenerator.fromSeed("0".repeat(64)),
        )
        assertEquals(
            "Lively Crab",
            ProfilePseudonymGenerator.fromSeed("1".repeat(64)),
        )
        assertEquals(
            "Eager Heron",
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
        assertEquals(128, ProfilePseudonymGenerator.adjectiveCount)
        assertEquals(128, ProfilePseudonymGenerator.nounCount)
        assertEquals(
            "7b590b35e66471b78299ccc60cbf4710bddbe75c8d307c5324c5d8f9fa0c37d6",
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
            ProfilePseudonymGenerator.random(excluding = " Solar Mallard ") {
                entropies[roll++]
            },
        )
        assertEquals(2, roll)
    }

    @Test
    fun randomFallsBackToNextPseudonymWhenRetriesKeepColliding() {
        var rollCount = 0

        assertEquals(
            "Solar Manatee",
            ProfilePseudonymGenerator.random(excluding = "Solar Mallard") {
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
