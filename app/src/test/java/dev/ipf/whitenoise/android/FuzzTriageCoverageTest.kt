package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every target the scheduled campaign fuzzes must be triageable, and a finding the
 * triage script cannot map still leaves a digest-only metadata receipt.
 */
class FuzzTriageCoverageTest {
    private val root = invariantGateRepositoryRoot()
    private val triage = File(root, "scripts/fuzz-triage.sh").readText()

    @Test
    fun everyCampaignTargetHasATriageMapping() {
        val targets = campaignTargets()
        assertTrue("campaign declares no targets", targets.isNotEmpty())
        val subtargetNames = triage.shellFunction("subtarget_name_for_id")
        val mappings = triage.shellFunction("lookup_mapping")
        val usage = triage.shellFunction("usage")
        targets.forEach { target ->
            val pattern = ":fuzz:$target)"
            assertTrue("$target lacks a subtarget name", pattern in subtargetNames)
            assertTrue("$target lacks a test mapping", pattern in mappings)
            assertTrue("$target lacks a fuzz test", "ENTRY_METHOD=\"$target\"" in mappings)
            assertTrue("$target missing from usage", ":fuzz:$target\n" in usage)
            assertTrue(
                "$target has no fuzz test source",
                File(root, "fuzz/src/test/kotlin").walk().any { file ->
                    file.extension == "kt" && "fun $target(" in file.readText()
                },
            )
        }
    }

    @Test
    fun selfCheckVerifiesCampaignMappings() {
        assertTrue(
            "self-check must map every campaign target",
            "self_check_campaign_mappings" in triage.shellFunction("self_check"),
        )
    }

    @Test
    fun unsupportedFindingWritesDigestOnlyReceiptBeforeExit() {
        val main = triage.substringAfter("if [[ -n \"\$REPRO\" ]]; then")
        val resolveFailure =
            main
                .substringAfter("if ! resolve_subtarget ")
                .substringBefore("\n  fi\n")
        val digestBeforeResolve = main.substringBefore("if ! resolve_subtarget ")
        assertTrue("digest must be computed before resolution", "REPRO_DIGEST=\"\$(digest " in digestBeforeResolve)
        assertTrue("unsupported status missing", "TRIAGE_STATUS=\"unsupported_target\"" in resolveFailure)
        assertTrue(
            "receipt must be written before exit",
            resolveFailure.indexOf("write_triage_metadata") in 0 until resolveFailure.indexOf("exit 1"),
        )
        assertTrue("unsupported input must not be copied", "cp " !in resolveFailure)
    }

    private fun campaignTargets(): List<String> =
        File(root, "scripts/fuzz-run-campaign.sh")
            .readText()
            .substringAfter("\nTARGETS=(\n")
            .substringBefore("\n)")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun String.shellFunction(name: String): String {
        val start = indexOf("\n$name() {\n")
        require(start >= 0) { "Missing shell function $name" }
        val end = indexOf("\n}\n", start)
        require(end > start) { "Unterminated shell function $name" }
        return substring(start, end)
    }
}
