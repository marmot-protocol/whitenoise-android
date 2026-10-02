package dev.ipf.whitenoise.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps `docs/invariant-gates.md` consistent with every `*CoverageTest.kt` gate in the repository. */
class InvariantGateRegistryTest {
    private val alphaPath = "app/src/test/java/dev/ipf/whitenoise/android/AlphaCoverageTest.kt"
    private val betaPath = "app/src/test/java/dev/ipf/whitenoise/android/ui/BetaCoverageTest.kt"

    /** The checked-in registry lists every coverage test exactly once, and every row points at a real test. */
    @Test
    fun checkedInRegistryMatchesEveryCoverageTest() {
        val root = invariantGateRepositoryRoot()
        val rows = parseInvariantGateRegistry(File(root, INVARIANT_GATE_REGISTRY_PATH).readText())
        val coverageTests = discoverCoverageTests(root)

        assertTrue("expected the coverage-test inventory to be discovered", coverageTests.isNotEmpty())
        assertEquals(emptyList<String>(), invariantGateRegistryErrors(rows, coverageTests) { File(root, it).isFile })
        assertEquals("registry rows must stay sorted by gate name", rows.map { it.name }.sorted(), rows.map { it.name })
    }

    /** A consistent fixture registry produces no errors, proving the failure cases below are not vacuous. */
    @Test
    fun consistentFixturePasses() {
        val rows = parseInvariantGateRegistry(registry(row("AlphaCoverageTest", alphaPath)))

        assertEquals(emptyList<String>(), invariantGateRegistryErrors(rows, setOf(alphaPath)) { true })
    }

    /** A coverage test that exists on disk without a registry row fails the check. */
    @Test
    fun unregisteredCoverageTestFails() {
        val rows = parseInvariantGateRegistry(registry(row("AlphaCoverageTest", alphaPath)))

        assertEquals(
            listOf("unregistered coverage test $betaPath, add it to $INVARIANT_GATE_REGISTRY_PATH"),
            invariantGateRegistryErrors(rows, setOf(alphaPath, betaPath)) { true },
        )
    }

    /** Registering the same gate twice, by name or by path, fails the check. */
    @Test
    fun duplicateRegistryEntriesFail() {
        val rows =
            parseInvariantGateRegistry(
                registry(row("AlphaCoverageTest", alphaPath), row("AlphaCoverageTest", alphaPath)),
            )

        assertEquals(
            listOf(
                "duplicate registry entry for gate AlphaCoverageTest",
                "duplicate registry entry for path $alphaPath",
            ),
            invariantGateRegistryErrors(rows, setOf(alphaPath)) { true },
        )
    }

    /** A row whose test file was deleted or renamed fails instead of silently vouching for missing coverage. */
    @Test
    fun registryEntryForMissingTestFails() {
        val rows = parseInvariantGateRegistry(registry(row("AlphaCoverageTest", alphaPath)))

        assertEquals(
            listOf("line 4: registered test $alphaPath does not exist"),
            invariantGateRegistryErrors(rows, emptySet()) { false },
        )
    }

    /** A row whose gate name differs from its file name, or that leaves a cell blank, fails the check. */
    @Test
    fun mismatchedNameAndBlankOwnerFail() {
        val rows =
            parseInvariantGateRegistry(
                registry(row("WrongCoverageTest", alphaPath), row("BetaCoverageTest", betaPath, owner = " ")),
            )

        assertEquals(
            listOf(
                "line 4: WrongCoverageTest does not match its file $alphaPath",
                "line 5: BetaCoverageTest needs an invariant and an owner",
            ),
            invariantGateRegistryErrors(rows, setOf(alphaPath, betaPath)) { true },
        )
    }

    /** Only Kotlin tests inside a test source set can be gates, so production or tooling files are rejected. */
    @Test
    fun registryEntryOutsideATestSourceSetFails() {
        val mainActivity = "app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"
        val escape = "app/src/test/../main/java/EscapeCoverageTest.kt"
        val device = "app/src/androidTest/java/dev/ipf/whitenoise/android/DeviceCoverageTest.kt"
        val rows =
            parseInvariantGateRegistry(
                registry(
                    row("MainActivity", mainActivity),
                    row("EscapeCoverageTest", escape),
                    row("DeviceCoverageTest", device),
                ),
            )

        assertEquals(
            listOf(
                "line 4: $mainActivity must be a Kotlin test under app/src/test or app/src/androidTest",
                "line 5: $escape must be a Kotlin test under app/src/test or app/src/androidTest",
            ),
            invariantGateRegistryErrors(rows, setOf(device)) { true },
        )
    }

    /** Malformed rows and missing table markers are rejected rather than skipped. */
    @Test
    fun malformedRegistryIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            parseInvariantGateRegistry(registry("| AlphaCoverageTest | rule | owner |"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseInvariantGateRegistry(registry("| [`AlphaCoverageTest`](../$alphaPath) | rule only |"))
        }
        assertThrows(IllegalArgumentException::class.java) { parseInvariantGateRegistry("| Gate |") }
    }

    /** Wraps fixture rows in the same markers and header the checked-in registry uses. */
    private fun registry(vararg rows: String): String =
        listOf(
            "<!-- invariant-gates:start -->",
            "| Gate | Invariant | Owning primitive or boundary |",
            "| --- | --- | --- |",
            *rows,
            "<!-- invariant-gates:end -->",
        ).joinToString("\n")

    /** Builds one fixture registry row linked relative to `docs/`, like the checked-in rows. */
    private fun row(
        name: String,
        path: String,
        owner: String = "Owner",
    ): String = "| [`$name`](../$path) | Rule. | $owner |"
}
