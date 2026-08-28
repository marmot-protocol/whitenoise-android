package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidReleaseRuntimeWorkflowCoverageTest {
    @Test
    fun requiredRuntimeCheckAggregatesProductionAndAndroid17StagingVerification() {
        val workflow = workflowSource()
        val productionJob = workflow.jobSection("verify-production")
        val stagingJob = workflow.jobSection("verify-staging-android17")
        val aggregateJob = workflow.jobSection("verify")

        assertProductionCoverage(productionJob)
        assertStagingArtifactCoverage(stagingJob)
        assertAndroid17EmulatorCoverage(stagingJob)
        assertRequiredCheckAggregation(aggregateJob)
    }

    @Test
    fun releaseRuntimeVerifierRetriesTransientAndroid17ServiceFailures() {
        assertRuntimeVerifierResilience(verifierSource())
    }

    @Test
    fun productionFirebaseExemptionCannotAuthorizePlayPublication() {
        val buildScript = appBuildScriptSource()

        assertTrue(
            "the Firebase exemption must require the disposable runtime workflow",
            "System.getenv(\"GITHUB_WORKFLOW\") == \"Android Release Runtime Verify\"" in buildScript,
        )
        assertTrue(
            "the Firebase exemption must allow only the production Zapstore assemble task",
            "requestedTasks.all { it == \"assembleProductionZapstoreRelease\" }" in buildScript,
        )
    }

    private fun assertProductionCoverage(productionJob: String) {
        assertTrue(
            "production release verification must remain covered",
            "assembleProductionZapstoreRelease" in productionJob,
        )
        assertTrue(
            "the disposable runtime APK must use the narrowly scoped Firebase exemption",
            "-Pwhitenoise.allowUnconfiguredProductionFirebaseForReleaseRuntimeTest=true" in productionJob,
        )
    }

    private fun assertStagingArtifactCoverage(stagingJob: String) {
        assertTrue("the crashing staging variant must be built", "assembleStagingZapstoreRelease" in stagingJob)
        assertTrue(
            "the shipping ARM64 staging artifact must be located",
            "for abi in arm64-v8a x86_64" in stagingJob,
        )
        assertTrue(
            "the emulator must verify bytecode identical to the ARM64 artifact",
            "Require ARM64 verifier DEX parity" in stagingJob &&
                "cmp --" in stagingJob,
        )
        assertTrue(
            "the staging application id must be passed to the verifier",
            "dev.ipf.whitenoise.android.staging" in stagingJob,
        )
    }

    private fun assertAndroid17EmulatorCoverage(stagingJob: String) {
        assertTrue("Android 17 ART must verify staging", "api-level: '37.0'" in stagingJob)
        assertTrue(
            "Android 17 must use its supported 16 KB-page system image",
            "target: google_apis_ps16k" in stagingJob,
        )
        assertTrue(
            "Android 17 must use the stable Pixel hardware profile",
            "profile: pixel_6" in stagingJob,
        )
        assertTrue(
            "Android 17 AVDs require at least 4 GB RAM",
            "ram-size: 4096M" in stagingJob,
        )
        assertTrue(
            "the staging APK needs an AVD data partition large enough to install",
            "disk-size: 8G" in stagingJob,
        )
        assertTrue(
            "API 37 must enable direct graphics memory during boot",
            "-feature GLDirectMem" in stagingJob,
        )
        assertTrue(
            "API 37 AVD creation must use current SDK cmdline-tools",
            "Update SDK cmdline-tools for API 37" in stagingJob &&
                "cmdline-tools;latest" in stagingJob,
        )
        assertTrue(
            "SurfaceFlinger must be stabilized before Android 17 finishes booting",
            "pre-emulator-launch-script: ./scripts/stabilize-android17-emulator.sh" in stagingJob &&
                "REQUIRE_ANDROID17_EMULATOR_STABILIZED=true" in stagingJob &&
                "Test Android 17 stabilization handshake" in stagingJob &&
                "./scripts/test-android17-emulator-stabilization.sh" in stagingJob,
        )
        assertTrue(
            "the runner unlock must wait for Android 17's input service",
            "Retry Android 17 input-service readiness" in stagingJob &&
                "scripts/android17-adb-wrapper.sh" in stagingJob &&
                "platform-tools/adb" in stagingJob,
        )
        assertTrue(
            "the Android 17 helper scripts must be executable",
            executableScriptExists("android17-adb-wrapper.sh") &&
                executableScriptExists("stabilize-android17-emulator.sh") &&
                executableScriptExists("test-android17-emulator-stabilization.sh"),
        )
    }

    private fun assertRuntimeVerifierResilience(verifierScript: String) {
        assertTrue(
            "the verifier must wait for Android 17's package and settings services",
            "wait_for_android_services" in verifierScript &&
                "getprop sys.boot_completed" in verifierScript &&
                "cmd package list packages android" in verifierScript &&
                "settings get global device_provisioned" in verifierScript,
        )
        assertTrue(
            "the verifier must reject an unstabilized API 37 emulator",
            "REQUIRE_ANDROID17_EMULATOR_STABILIZED" in verifierScript &&
                "android17-emulator-stabilize.request" in verifierScript &&
                "android17-emulator-stabilize.status" in verifierScript &&
                "getprop debug.sf.luma_sampling" in verifierScript,
        )
        assertTrue(
            "the verifier must retry transient Android 17 package-service failures",
            "Broken pipe" in verifierScript &&
                "Failure calling service package" in verifierScript &&
                "PackageManagerInternal.freeStorage" in verifierScript &&
                "before system providers are installed" in verifierScript,
        )
        assertTrue(
            "the verifier must wait for the installed launcher to become visible",
            "wait_for_launcher" in verifierScript &&
                "launcher-resolve.txt" in verifierScript,
        )
        assertTrue(
            "the verifier must retry transient Android 17 Activity-service failures",
            "Failure calling service activity" in verifierScript,
        )
        assertTrue(
            "a not-yet-visible app process must not bypass the bounded PID readiness loop",
            "pidof \"\$application_id\" 2> /dev/null | tr -d '\\r' || true" in verifierScript,
        )
        assertTrue(
            "production runtime verification must reject WNPerf and dynamic app-log value shapes",
            "production release emitted WNPerf diagnostics" in verifierScript &&
                "dynamic_value_patterns" in verifierScript &&
                "known dynamic identifier/error log pattern" in verifierScript,
        )
    }

    private fun assertRequiredCheckAggregation(aggregateJob: String) {
        assertTrue(
            "the production lane must feed the established required check",
            "needs.verify-production.result" in aggregateJob,
        )
        assertTrue(
            "the Android 17 staging lane must feed the established required check",
            "needs.verify-staging-android17.result" in aggregateJob,
        )
        assertTrue(
            "the established required check name must remain stable",
            "name: Verify minified APK on ART" in aggregateJob,
        )
    }

    private fun String.jobSection(jobId: String): String {
        val marker = "\n  $jobId:\n"
        val start = indexOf(marker)
        require(start >= 0) { "Missing workflow job: $jobId" }
        val contentStart = start + marker.length
        val nextJob = Regex("\n  [A-Za-z0-9_-]+:\n").find(this, contentStart)?.range?.first ?: length
        return substring(contentStart, nextJob)
    }

    private fun workflowSource(): String =
        listOf(
            File("../.github/workflows/android-release-runtime.yml"),
            File(".github/workflows/android-release-runtime.yml"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing Android release runtime workflow")

    private fun verifierSource(): String =
        listOf(
            File("../scripts/verify-release-runtime.sh"),
            File("scripts/verify-release-runtime.sh"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing release runtime verifier script")

    private fun appBuildScriptSource(): String =
        listOf(
            File("../app/build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing app Gradle build script")

    private fun executableScriptExists(name: String): Boolean =
        listOf(File("../scripts/$name"), File("scripts/$name"))
            .any { it.isFile && it.canExecute() }
}
