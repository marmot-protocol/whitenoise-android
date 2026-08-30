package dev.ipf.whitenoise.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WorkManagerStartupInitializationTest {
    @Test
    fun applicationProvidesOnDemandWorkManagerConfiguration() {
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(WhiteNoiseApplication::class.java))

        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("androidx.startup.InitializationProvider"))
        assertTrue(manifest.contains("androidx.work.WorkManagerInitializer"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
    }

    @Test
    fun mergedManifestDoesNotRegisterWorkManagerInitializer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider =
            context.packageManager.getProviderInfo(
                ComponentName(context, InitializationProvider::class.java),
                PackageManager.GET_META_DATA,
            )

        assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
        assertTrue(provider.metaData?.containsKey("androidx.emoji2.text.EmojiCompatInitializer") == true)
        assertTrue(provider.metaData?.containsKey("androidx.lifecycle.ProcessLifecycleInitializer") == true)
    }

    @Test
    fun periodicWorkSchedulingLeavesTheStartupThread() {
        val source = projectFile("src/main/java/dev/ipf/whitenoise/android/WhiteNoiseApplication.kt").readText()
        val onCreate = source.functionBody("onCreate")
        val scheduling = source.substringAfter("internal fun ensurePeriodicWorkScheduled(")

        assertTrue(onCreate.contains("ensurePeriodicWorkScheduled()"))
        assertTrue(scheduling.contains("backgroundWorkSchedulingGate.start"))
        assertTrue(scheduling.contains("applicationScope.launch(Dispatchers.Default)"))
        assertTrue(scheduling.contains("DisappearingMessageSweepWorker.schedule(this)"))
        assertTrue(scheduling.contains("AppUpdateWorker.schedule(this)"))
        assertFalse(onCreate.contains("Worker.schedule("))
    }

    @Test
    fun periodicWorkInitializationCoalescesRepeatedCalls() {
        val gate = BackgroundWorkSchedulingGate()
        val invocations = AtomicInteger()
        val schedule: () -> Unit = { invocations.incrementAndGet() }

        assertTrue(gate.start(schedule))
        assertFalse(gate.start(schedule))

        assertEquals(1, invocations.get())
    }

    @Test
    fun periodicWorkInitializationCanRetryAfterFailure() {
        val gate = BackgroundWorkSchedulingGate()
        val invocations = AtomicInteger()

        assertTrue(gate.start { invocations.incrementAndGet() })
        gate.resetAfterFailure()
        assertTrue(gate.start { invocations.incrementAndGet() })

        assertEquals(2, invocations.get())
    }

    @Test
    fun periodicWorkIdentityAndPoliciesStayStable() {
        val disappearing =
            projectFile("src/main/java/dev/ipf/whitenoise/android/state/DisappearingMessageSweepWorker.kt").readText()
        val updates = projectFile("src/main/java/dev/ipf/whitenoise/android/updates/AppUpdateWorker.kt").readText()

        assertTrue(disappearing.contains("private const val UNIQUE_WORK_NAME = \"disappearing_message_sweep\""))
        assertTrue(disappearing.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue(updates.contains("private const val UNIQUE_WORK_NAME = \"darkmatter-zapstore-update-check\""))
        assertTrue(updates.contains("ExistingPeriodicWorkPolicy.UPDATE"))
    }

    private fun projectFile(relativePath: String): File =
        listOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $relativePath")

    private fun String.functionBody(name: String): String {
        val signatureStart = indexOf("fun $name(")
        require(signatureStart >= 0) { "Missing function $name" }
        val bodyStart = indexOf('{', signatureStart)
        require(bodyStart >= 0) { "Missing body for $name" }

        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(bodyStart + 1, index)
                }
            }
        }
        error("Unbalanced body for $name")
    }
}
