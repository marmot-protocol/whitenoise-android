package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidReleaseRuntimeWorkflowCoverageTest {
    /** The release-runtime aggregate, which no ruleset requires, covers both ART lanes. */
    @Test
    fun runtimeCheckAggregatesProductionAndAndroid17StagingVerification() {
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

    /** Both shipping lanes budget the APK they verify, and a budget failure never skips ART verification. */
    @Test
    fun shippingRuntimeLanesEnforcePostR8DexMethodBudgets() {
        val workflow = workflowSource()

        assertDexMethodBudget(
            job = workflow.jobSection("verify-production"),
            apkOutput = "steps.apk.outputs.path",
            variant = "productionZapstoreRelease",
            emulatorStep = "Verify release APK on ART",
        )
        assertDexMethodBudget(
            job = workflow.jobSection("verify-staging-android17"),
            apkOutput = "steps.apks.outputs.arm64_v8a_path",
            variant = "stagingZapstoreRelease",
            emulatorStep = "Verify staging APK on Android 17 ART",
        )
        assertTrue(
            "budget inputs must trigger the runtime workflow on pull requests",
            "- 'scripts/dex_method_budget.py'" in workflow &&
                "- 'config/dex-method-budgets.json'" in workflow,
        )
    }

    /**
     * The required Android CI check builds every shipping R8 variant in each merge group and fails on its
     * DEX method budget, so an over-budget method cannot merge even though release-runtime is not required.
     */
    @Test
    fun requiredCiCheckBlocksOverBudgetShippingMethodsBeforeMerge() {
        val ci = androidCiSource()
        val buildContracts = ci.jobSection("build-contracts")
        val phases = buildContracts.lineSequence().first { it.trimStart().startsWith("phase: ") }
        assertTrue(
            "every merge group must build both shipping R8 variants",
            "(github.event_name == 'merge_group' || " in phases &&
                "'[\"tooling\", \"baseline\", \"productionZapstoreRelease\", \"stagingZapstoreRelease\"]'" in phases,
        )
        val budgetStart = buildContracts.indexOf("- name: Enforce shipping post-R8 DEX method budget")
        assertTrue("the required check must enforce the shipping budgets", budgetStart >= 0)
        val budgetStep = buildContracts.stepAt(budgetStart)
        assertTrue(
            "a shipping budget failure must fail the required check",
            "if: endsWith(matrix.phase, 'ZapstoreRelease')" in budgetStep &&
                "continue-on-error" !in budgetStep &&
                "|| true" !in budgetStep,
        )
        assertTrue(
            "the budget must read the R8 mapping of the variant it measures",
            "--mapping \"app/build/outputs/mapping/\$DEX_BUDGET_VARIANT/mapping.txt\"" in budgetStep &&
                "--variant \"\$DEX_BUDGET_VARIANT\"" in budgetStep,
        )
        val aggregate = ci.jobSection("validate")
        assertTrue(
            "build contracts must feed the ruleset's required check",
            "name: Compile, test, ktlint, detekt, Android lint" in aggregate &&
                Regex("""needs: \[[^\]]*\bbuild-contracts\b""").containsMatchIn(aggregate),
        )
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
        val appLogTags = verifierScript.substringAfter("app_log_tags=\"").substringBefore('"')
        val dynamicValuePatterns = verifierScript.substringAfter("dynamic_value_patterns=\"").substringBefore('"')
        assertTrue(
            "the release log guard must reject a standalone raw error field",
            Regex("($appLogTags).*($dynamicValuePatterns)").containsMatchIn("DMSend: error=timeout"),
        )
    }

    /**
     * Asserts one lane budgets its located APK with the same build's mapping before the emulator starts,
     * still runs ART verification after a budget failure, and fails the job at the end.
     */
    private fun assertDexMethodBudget(
        job: String,
        apkOutput: String,
        variant: String,
        emulatorStep: String,
    ) {
        val budgetStart = job.indexOf("- name: Enforce post-R8 DEX method budgets")
        assertTrue("$variant must enforce post-R8 DEX method budgets", budgetStart >= 0)
        val budgetStep = job.stepAt(budgetStart)
        assertTrue("$variant must measure the located APK", "\${{ $apkOutput }}" in budgetStep)
        assertTrue(
            "$variant must name methods with the mapping from the same build",
            "--mapping app/build/outputs/mapping/$variant/mapping.txt" in budgetStep &&
                "--variant $variant" in budgetStep,
        )
        assertTrue("$variant must record the exact source commit", "\${{ github.sha }}" in budgetStep)
        assertTrue(
            "$variant must measure before the emulator installs the APK",
            budgetStart < job.indexOf("- name: $emulatorStep"),
        )
        assertTrue(
            "a $variant budget failure must not skip ART verification",
            "\n        id: dex_budget\n" in budgetStep && "continue-on-error: true" in budgetStep,
        )
        assertTrue(
            "ART verification of $variant must run whatever the budget found",
            "\n        if:" !in job.stepAt(job.indexOf("- name: $emulatorStep")),
        )
        assertTrue(
            "the $variant budget step must not be disabled",
            "\n        if:" !in budgetStep && "|| true" !in budgetStep,
        )
        val failStart = job.indexOf("- name: Fail on post-R8 DEX method budget")
        assertTrue(
            "$variant must fail the job after ART verification when its budget failed",
            failStart > job.indexOf("- name: $emulatorStep") &&
                "steps.dex_budget.outcome == 'failure'" in job.stepAt(failStart) &&
                "exit 1" in job.stepAt(failStart),
        )
    }

    /** Returns the workflow step that starts at [start], up to the next step in the same job. */
    private fun String.stepAt(start: Int): String = substring(start).substringBefore("\n      - name:")

    /** Asserts both ART lanes feed the release-runtime aggregate under its established check name. */
    private fun assertRequiredCheckAggregation(aggregateJob: String) {
        assertTrue(
            "the production lane must feed the established aggregate check",
            "needs.verify-production.result" in aggregateJob,
        )
        assertTrue(
            "the Android 17 staging lane must feed the established aggregate check",
            "needs.verify-staging-android17.result" in aggregateJob,
        )
        assertTrue(
            "the established aggregate check name must remain stable",
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

    /** Reads the required Android CI workflow from the module or repository working directory. */
    private fun androidCiSource(): String =
        listOf(
            File("../.github/workflows/android-ci.yml"),
            File(".github/workflows/android-ci.yml"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing Android CI workflow")

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
