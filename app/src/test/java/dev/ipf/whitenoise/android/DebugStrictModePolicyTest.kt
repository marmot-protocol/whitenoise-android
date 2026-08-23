package dev.ipf.whitenoise.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugStrictModePolicyTest {
    @After
    fun resetBridge() {
        RuntimePolicyHooks.resetForTest()
    }

    @Test
    fun runtimeHooksDelegateSlowCallsAndScopedDiskReadExemptions() {
        val events = mutableListOf<String>()
        RuntimePolicyHooks.install(
            object : RuntimePolicyBridge {
                override fun noteSlowCall(operation: String) {
                    events += "slow:$operation"
                }

                override fun <T> allowThreadDiskReads(block: () -> T): T {
                    events += "disk:start"
                    return block().also { events += "disk:end" }
                }
            },
        )

        RuntimePolicyHooks.noteSlowCall("ffi")
        val result = RuntimePolicyHooks.allowThreadDiskReads { "theme" }

        assertEquals("theme", result)
        assertEquals(listOf("slow:ffi", "disk:start", "disk:end"), events)
    }

    @Test
    fun debugSourceOwnsTheCompleteLogOnlyPolicyAndReleaseSourcesReferenceNoStrictMode() {
        val debugSource = source("src/debug/java/dev/ipf/whitenoise/android/DebugWhiteNoiseApplication.kt").readText()
        val mainKotlin = source("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        listOf(
            "detectDiskReads()",
            "detectDiskWrites()",
            "detectNetwork()",
            "detectUnbufferedIo()",
            "detectCustomSlowCalls()",
            "detectLeakedClosableObjects()",
            "detectLeakedRegistrationObjects()",
            "penaltyLog()",
        ).forEach { requirement -> assertTrue("missing $requirement", requirement in debugSource) }
        assertFalse("debug policy must not crash ordinary builds", "penaltyDeath" in debugSource)
        assertTrue(
            "StrictMode must stay out of release/main bytecode",
            mainKotlin.none { file ->
                val source = file.readText()
                "import android.os.StrictMode" in source || Regex("""\bStrictMode\.""").containsMatchIn(source)
            },
        )
    }

    @Test
    fun deliberateThemeReadAndSynchronousFfiBoundaryUseTheDebugHooks() {
        val activity = source("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt").readText()
        val appState = source("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt").readText()
        val debugManifest = source("src/debug/AndroidManifest.xml").readText()

        assertTrue("RuntimePolicyHooks.allowThreadDiskReads(::readPersistedThemeMode)" in activity)
        assertTrue("RuntimePolicyHooks.noteSlowCall(\"marmot-ffi-access\")" in appState)
        assertTrue("android:name=\".DebugWhiteNoiseApplication\"" in debugManifest)
    }

    private fun source(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull(File::exists)
            ?: error("Missing $relative")
}
