package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildIdentityTest {
    @Test
    fun previewAndStagingKeepFullSourceIdentity() {
        val sha = "a1".repeat(20)
        for (version in listOf("2026.11-preview-pr1234-a1a1a1a", "2026.11-staging-a1a1a1a")) {
            assertEquals("White Noise $version (23)\nCommit: $sha\nPR: #1234", buildIdentity(version, 23, sha, "1234"))
        }
    }

    @Test
    fun stagingWithoutPrAndProductionKeepOnlyKnownCommit() {
        val sha = "F0".repeat(20)
        for (version in listOf("2026.11-staging-f0f0f0f", "2026.11")) {
            assertEquals("White Noise $version (23)\nCommit: $sha", buildIdentity(version, 23, sha, ""))
        }
    }

    @Test
    fun localAndMalformedMetadataNeverClaimAnIdentity() {
        for (sha in listOf("", "local", "abcdef0", "g".repeat(40), "a".repeat(41))) {
            for (pr in listOf("", "0", "-1", "1a", "01", "1\nPR: #2")) {
                assertEquals("White Noise local (1)", buildIdentity("local", 1, sha, pr))
            }
        }
    }
}
