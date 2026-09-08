package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidPrPreviewWorkflowCoverageTest {
    @Test
    fun optimizerDiagnosticsAreBoundToTheSameUnsignedPreviewWithoutEnteringPublisherInput() {
        val builder = workflowSource("android-pr-apk.yml")
        val publisher = workflowSource("android-pr-preview-publish.yml")

        assertTrue(
            "the optimized preview mapping must exist before artifact staging",
            "test -s \"\$mapping\"" in builder,
        )
        assertTrue(
            "diagnostics must retain the exact optimized mapping separately from the APK candidate",
            "cp \"\$mapping\" preview-diagnostic/mapping.txt" in builder &&
                "path: preview-diagnostic/" in builder,
        )
        assertTrue(
            "diagnostics must bind PR, channel, head, run and unsigned APK digest",
            "unsigned_apk_sha256=\$apk_sha256" in builder &&
                "head_sha=\$PREVIEW_HEAD_SHA" in builder &&
                "channel=\$PR_PREVIEW_CHANNEL" in builder &&
                "run_number=\$GITHUB_RUN_NUMBER" in builder,
        )
        assertTrue(
            "mapping bytes must carry their own checksum",
            "(cd preview-diagnostic && sha256sum mapping.txt > SHA256SUMS)" in builder,
        )
        assertTrue(
            "stable and isolated diagnostic artifacts must be namespaced away from signable candidates",
            "name: pr-preview-\${{ matrix.channel }}-diagnostics" in builder,
        )
        assertFalse(
            "the privileged publisher must never download or process untrusted mapping diagnostics",
            "-diagnostics" in publisher,
        )
    }

    private fun workflowSource(name: String): String =
        listOf(
            File("../.github/workflows/$name"),
            File(".github/workflows/$name"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing workflow: $name")
}
