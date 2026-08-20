package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreviewAuditCredentialIsolationTest {
    @Test
    fun previewFlavorKeepsAuditEndpointAndTokenEmpty() {
        val source = buildGradleSource()
        val previewBlock = source.substringAfter("create(\"preview\") {").substringBefore("create(\"production\") {")

        assertTrue(
            previewBlock.contains(
                "buildConfigField(\"String\", \"WHITENOISE_AUDIT_LOG_ENDPOINT\", \"\".asBuildConfigString())",
            ),
        )
        assertTrue(
            previewBlock.contains(
                "buildConfigField(\"String\", \"WHITENOISE_AUDIT_LOG_AUTH_TOKEN\", \"\".asBuildConfigString())",
            ),
        )
        assertTrue(
            previewBlock.contains(
                "buildConfigField(\"String\", \"WHITENOISE_OTLP_ENDPOINT\", \"\".asBuildConfigString())",
            ),
        )
        assertTrue(
            previewBlock.contains(
                "buildConfigField(\"String\", \"WHITENOISE_OTLP_AUTH_TOKEN\", \"\".asBuildConfigString())",
            ),
        )
        assertFalse(previewBlock.contains("environmentRuntimeConfigProperty"))
        assertFalse(previewBlock.contains("runtimeConfigProperty"))
        assertFalse(previewBlock.contains("AUDIT_LOG_ENDPOINT)"))
        assertFalse(previewBlock.contains("AUDIT_LOG_AUTH_TOKEN)"))
    }

    private fun buildGradleSource(): String =
        sequenceOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing app/build.gradle.kts")
}
