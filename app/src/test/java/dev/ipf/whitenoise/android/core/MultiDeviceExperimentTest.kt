package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.invariantGateRepositoryRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the linked-device build switch and the single place the runtime is opened. */
class MultiDeviceExperimentTest {
    /** Builds ship linked devices, matching the device groups MDK opens every runtime with. */
    @Test
    fun theSwitchIsOn() {
        assertTrue(MULTI_DEVICE_EXPERIMENT)
    }

    /** The switch is a source literal, so no build input can stand behind the constant. */
    @Test
    fun theSwitchIsALiteralInSource() {
        val source = appModuleSource("src/main/java/dev/ipf/whitenoise/android/core/MultiDeviceExperiment.kt")
        val declarations = source.lines().filter { it.contains("MULTI_DEVICE_EXPERIMENT =") }

        assertEquals(listOf("internal const val MULTI_DEVICE_EXPERIMENT = true"), declarations)
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

    /** Only the client wrapper names an MDK constructor, so every opener of the root opens it the same way. */
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

    /** Every Kotlin file compiled into the app's main source set. */
    private fun mainSources(): List<File> {
        val sources = appModuleFile("src/main/java").walkTopDown()
        return sources.filter { it.isFile && it.extension == "kt" }.toList()
    }
}
