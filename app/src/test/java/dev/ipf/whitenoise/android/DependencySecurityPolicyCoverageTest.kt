package dev.ipf.whitenoise.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guava stays at or above its deserialization fix (GHSA-xxph-c9ww-hj94), and both the
 * Gradle floor and the CI verifier read Guava's -android and -jre editions as releases,
 * so raising a request keeps its edition and a prerelease of the fix is still refused.
 */
class DependencySecurityPolicyCoverageTest {
    private val root = invariantGateRepositoryRoot()
    private val policy = JSONObject(File(root, "gradle/dependency-security.json").readText())
    private val settings = File(root, "gradle/dependency-security.settings.gradle").readText()
    private val verifier = File(root, "scripts/verify_dependency_security.py").readText()

    @Test
    fun guavaFloorIncludesTheDeserializationFix() {
        val floor = policy.getJSONObject("minimum_versions").getString(GUAVA)
        val parts = floor.split('.').map(String::toInt)
        val comparison =
            (0 until maxOf(parts.size, FIXED.size))
                .map { (parts.getOrNull(it) ?: 0).compareTo(FIXED.getOrNull(it) ?: 0) }
                .firstOrNull { it != 0 } ?: 0
        assertTrue("Guava floor $floor is below 33.7.2", comparison >= 0)
    }

    @Test
    fun guavaEditionsAreDeclaredForTheFloor() {
        val editions = policy.getJSONObject("edition_suffixes").getJSONArray(GUAVA)
        assertEquals(listOf("-android", "-jre"), (0 until editions.length()).map(editions::getString))
    }

    @Test
    fun gradleRaiseRejectAndReportAllApplyTheEdition() {
        assertTrue(
            "raising must keep the requested edition",
            "details.useVersion(SecurityVersions.fixedVersion(details.requested.version, minimum, editions[coordinate]))" in
                settings,
        )
        assertTrue(
            "candidate rejection must compare the release without its edition",
            "SecurityVersions.below(selection.candidate.version, minimum, editions[coordinate])" in settings,
        )
        assertTrue(
            "the resolved-graph violation check must compare the release without its edition",
            "SecurityVersions.below(component.version, minimum, editions[coordinate])" in settings,
        )
        assertFalse(
            "a raise must never drop the edition and request a version Guava does not publish",
            "details.useVersion(minimum)" in settings,
        )
        assertTrue("a malformed edition policy must fail the build", "Invalid dependency security edition policy" in settings)
    }

    @Test
    fun ciVerifierAppliesTheSameEditions() {
        assertTrue(
            "the selected-evidence check must compare the release without its edition",
            "below(component[\"version\"], minimums[key], (editions or {}).get(key))" in verifier,
        )
        assertTrue("the verifier must read the policy's editions", "policy.get(\"edition_suffixes\", {})" in verifier)
        assertTrue("a malformed edition policy must fail verification", "invalid security edition policy" in verifier)
    }

    private companion object {
        const val GUAVA = "com.google.guava:guava"
        val FIXED = listOf(33, 7, 2)
    }
}
