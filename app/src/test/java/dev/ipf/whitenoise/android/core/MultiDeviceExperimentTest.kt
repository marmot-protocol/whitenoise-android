package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.invariantGateRepositoryRoot
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.BootstrapStage
import dev.ipf.whitenoise.android.state.BootstrapStageFailure
import dev.ipf.whitenoise.android.state.failureDetail
import dev.ipf.whitenoise.android.state.startupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the linked-device build switch, the constructor it selects and the refusal an ordinary build reports. */
class MultiDeviceExperimentTest {
    private data class Opened(
        val constructor: String,
        val rootPath: String,
        val relayUrls: List<String>,
        val options: Any,
    )

    /** Opens through [openMarmot] with recording constructors so a test sees which one ran and what it received. */
    private fun open(experimental: Boolean): Opened {
        val options = Any()
        return openMarmot(
            experimental = experimental,
            rootPath = "/root",
            relayUrls = listOf("wss://relay.example"),
            options = options,
            ordinary = { root, relays, received -> Opened("ordinary", root, relays, received) },
            experimentalDeviceGroups = { root, relays, received -> Opened("experimental", root, relays, received) },
        )
    }

    /** Master builds compile the feature off. */
    @Test
    fun theSwitchIsOff() {
        assertFalse(MULTI_DEVICE_EXPERIMENT)
    }

    /** The switch is a source literal, so no build input can stand behind the constant. */
    @Test
    fun theSwitchIsALiteralInSource() {
        val source = appModuleSource("src/main/java/dev/ipf/whitenoise/android/core/MultiDeviceExperiment.kt")
        val declarations = source.lines().filter { it.contains("MULTI_DEVICE_EXPERIMENT =") }

        assertEquals(listOf("internal const val MULTI_DEVICE_EXPERIMENT = false"), declarations)
    }

    /** Neither the Gradle build nor the manifest names the switch, so no property, intent or deep link reaches it. */
    @Test
    fun noBuildOrManifestInputNamesTheSwitch() {
        val root = invariantGateRepositoryRoot()
        val inputs =
            listOf("app/build.gradle.kts", "build.gradle.kts", "gradle.properties", "app/src/main/AndroidManifest.xml")
                .map { File(root, it) }
                .filter(File::exists)

        assertTrue(inputs.isNotEmpty())
        inputs.forEach { input ->
            assertFalse(input.path, input.readText().contains("MULTI_DEVICE_EXPERIMENT", ignoreCase = true))
        }
    }

    /** With the switch off the ordinary constructor runs and receives the caller's arguments unchanged. */
    @Test
    fun theOrdinaryConstructorOpensWhenTheSwitchIsOff() {
        val opened = open(experimental = false)

        assertEquals("ordinary", opened.constructor)
        assertEquals("/root", opened.rootPath)
        assertEquals(listOf("wss://relay.example"), opened.relayUrls)
    }

    /** With the switch on the device-group constructor runs with the same root, relays and options. */
    @Test
    fun theDeviceGroupConstructorOpensWhenTheSwitchIsOnWithTheSameArguments() {
        val ordinary = open(experimental = false)
        val experimental = open(experimental = true)

        assertEquals("experimental", experimental.constructor)
        assertEquals(ordinary.rootPath, experimental.rootPath)
        assertEquals(ordinary.relayUrls, experimental.relayUrls)
        assertEquals(ordinary.options.javaClass, experimental.options.javaClass)
    }

    /** Only the client wrapper names an MDK constructor, so one selection covers every opener of the root. */
    @Test
    fun onlyTheClientWrapperCallsAnMdkConstructor() {
        val constructors = Regex("""Marmot(\.Companion)?(\.|::)newWith\w+|[^\w.]Marmot\(""")
        val callers =
            mainSources()
                .filter { constructors.containsMatchIn(it.readText()) }
                .map(File::getName)

        assertEquals(listOf("MarmotClient.kt"), callers)
    }

    /** The foreground runtime and the cold worker preflight are the two openers, and both use the client wrapper. */
    @Test
    fun theForegroundRuntimeAndTheWorkerPreflightOpenThroughTheClientWrapper() {
        val callers =
            mainSources()
                .filter { it.name != "MarmotClient.kt" && Regex("""\bMarmotClient\(""").containsMatchIn(it.readText()) }
                .map(File::getName)
                .sorted()

        assertEquals(listOf("AppState.kt", "DisappearingMessageSweepWorker.kt"), callers)
    }

    /** MDK's runtime error with the exact refusal details is recognised. */
    @Test
    fun theExactRefusalDetailsAreRecognised() {
        assertTrue(MarmotKitException.Runtime("experimental_device_groups_required").requiresExperimentalDeviceGroups())
    }

    /** A refusal raised by runtime start is still recognised through its startup-stage wrapper. */
    @Test
    fun aRefusalWrappedByAStartupStageIsRecognised() {
        val refusal = MarmotKitException.Runtime(EXPERIMENTAL_DEVICE_GROUPS_REQUIRED)

        assertTrue(BootstrapStageFailure(BootstrapStage.RUNTIME_START, refusal).requiresExperimentalDeviceGroups())
    }

    /** Other runtime details, near matches and other error types are not treated as the refusal. */
    @Test
    fun otherFailuresAreNotTheRefusal() {
        val others =
            listOf(
                MarmotKitException.Runtime("experimental_device_groups_unavailable"),
                MarmotKitException.Runtime("read_marker_failed:experimental_device_groups_required"),
                MarmotKitException.Runtime(" experimental_device_groups_required"),
                IllegalStateException("experimental_device_groups_required"),
            )

        others.forEach { assertFalse(it.toString(), it.requiresExperimentalDeviceGroups()) }
    }

    /** A refused account gets the compatible-build copy in place of generic retry copy or a caller's own detail. */
    @Test
    fun aRefusalReplacesTheFailureDetailWithTheCompatibleBuildCopy() {
        val refusal = MarmotKitException.Runtime(EXPERIMENTAL_DEVICE_GROUPS_REQUIRED)
        val expected = AppText.Resource(R.string.error_linked_devices_build_required)

        assertEquals(expected, failureDetail(refusal, AppText.Resource(R.string.error_try_again)))
        assertEquals(expected, failureDetail(refusal, AppText.Plain("caller detail")))
    }

    /** A refusal at startup names the remedy and offers no retry, because the same build would fail again. */
    @Test
    fun aRefusalAtStartupIsNotRetryable() {
        val refusal = MarmotKitException.Runtime(EXPERIMENTAL_DEVICE_GROUPS_REQUIRED)
        val failure = BootstrapStageFailure(BootstrapStage.RUNTIME_START, refusal)
        val presentation = startupFailure(failure, startupDetail = null)

        assertEquals(AppText.Resource(R.string.error_linked_devices_build_required), presentation.message)
        assertFalse(presentation.retryable)
        assertTrue(presentation.report.contains("operation=APP_BOOTSTRAP"))
        assertFalse(presentation.report.contains(EXPERIMENTAL_DEVICE_GROUPS_REQUIRED))
    }

    /** Any other startup failure keeps the generic copy and its retry. */
    @Test
    fun anOrdinaryStartupFailureStaysRetryable() {
        val presentation = startupFailure(IllegalStateException("boom"), startupDetail = null)

        assertEquals(AppText.Resource(R.string.error_try_again), presentation.message)
        assertTrue(presentation.retryable)
    }

    /** Every Kotlin file compiled into the app's main source set. */
    private fun mainSources(): List<File> {
        val sources = appModuleFile("src/main/java").walkTopDown()
        return sources.filter { it.isFile && it.extension == "kt" }.toList()
    }
}
