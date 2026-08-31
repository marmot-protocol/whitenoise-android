package dev.ipf.whitenoise.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.notifications.NotificationAction
import dev.ipf.whitenoise.android.notifications.NotificationActionKind
import dev.ipf.whitenoise.android.notifications.NotificationMarkReadWorker
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun periodicWorkSchedulingStartsAtTheSplashHandoff() {
        val application = projectFile("src/main/java/dev/ipf/whitenoise/android/WhiteNoiseApplication.kt").readText()
        val activity = projectFile("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt").readText()
        val handoff = activity.substringAfter("private fun holdSplashThroughBootstrap(")
        val postFrame = activity.substringAfter("private fun schedulePeriodicWorkAfterFirstFrame()")

        assertFalse(application.functionBody("onCreate").contains("ensurePeriodicWorkScheduled()"))
        assertTrue(handoff.contains("schedulePeriodicWorkAfterFirstFrame()"))
        assertTrue(postFrame.contains("window.decorView.postOnAnimation"))
        assertTrue(postFrame.contains("(application as WhiteNoiseApplication).ensurePeriodicWorkScheduled()"))
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
    fun periodicWorkInitializationCanRetryAfterSchedulingFailure() {
        val application = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val invocations = AtomicInteger()

        application.ensurePeriodicWorkScheduled(scope) {
            invocations.incrementAndGet()
            throw IllegalStateException("enqueue failed")
        }
        scope.advanceUntilIdle()
        application.ensurePeriodicWorkScheduled(scope) {
            invocations.incrementAndGet()
            emptyList()
        }
        scope.advanceUntilIdle()

        assertEquals(2, invocations.get())
    }

    @Test
    fun periodicWorkInitializationRegistersBothUniqueWorks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val application = context.applicationContext as WhiteNoiseApplication
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)

        application.ensurePeriodicWorkScheduled(scope)
        scope.advanceUntilIdle()

        val workManager = WorkManager.getInstance(context)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("disappearing_message_sweep").get().size)
        assertEquals(
            if (BuildConfig.SELF_UPDATE_ENABLED) 1 else 0,
            workManager.getWorkInfosForUniqueWork("darkmatter-zapstore-update-check").get().size,
        )
    }

    @Test
    fun coldNotificationActionEnqueueInitializesWorkManagerThroughApplicationProvider() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
            val action =
                NotificationAction(
                    kind = NotificationActionKind.MARK_READ,
                    target =
                        NotificationTarget(
                            accountRef = "cold-account",
                            groupIdHex = "cold-group",
                            messageIdHex = "cold-message",
                            kind = NotificationTargetKind.MESSAGE,
                        ),
                    notificationTag = "cold-account|cold-group",
                    notificationId = 2324,
                )

            assertTrue(application is Configuration.Provider)
            assertTrue(NotificationMarkReadWorker.enqueue(application, action))

            val workManager = WorkManager.getInstance(application)
            assertEquals(
                1,
                workManager
                    .getWorkInfosForUniqueWork(NotificationMarkReadWorker.notificationMarkReadWorkName(action))
                    .get()
                    .size,
            )
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
