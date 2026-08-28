package dev.ipf.whitenoise.android.share

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ipf.whitenoise.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InboundShareTaskReuseDeviceTest {
    @Test
    fun repeatedExternalFileSharesReuseOneTaskAndKeepOnlyNewestRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent =
            checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext.startActivity(launchIntent)
        val initial = awaitResumedMainActivity()
        val taskId = initial.taskId

        val firstUri = externalFileUri("first.bin")
        dispatchExternalShare(firstUri)
        val firstDelivery = awaitPendingShare(firstUri)
        val firstRequestId = checkNotNull(firstDelivery.pendingInboundShareRequestForTest).requestId
        assertEquals(taskId, firstDelivery.taskId)
        assertEquals(1, whiteNoiseTaskCount(targetContext))

        instrumentation.runOnMainSync { firstDelivery.moveTaskToBack(true) }
        val secondUri = externalFileUri("second.bin")
        dispatchExternalShare(secondUri)
        val secondDelivery = awaitPendingShare(secondUri)
        val secondRequest = checkNotNull(secondDelivery.pendingInboundShareRequestForTest)
        assertSame(firstDelivery, secondDelivery)
        assertTrue(secondRequest.requestId != firstRequestId)
        assertEquals(listOf(secondUri), secondRequest.payload.streamUris)
        assertEquals(1, whiteNoiseTaskCount(targetContext))

        instrumentation.runOnMainSync { secondDelivery.recreate() }
        val recreated = awaitResumedMainActivity(excluding = secondDelivery)
        assertNotSame(secondDelivery, recreated)
        assertEquals(secondRequest.requestId, recreated.pendingInboundShareRequestForTest?.requestId)
        assertEquals(1, whiteNoiseTaskCount(targetContext))

        instrumentation.runOnMainSync {
            recreated.acknowledgeInboundShareForTest(secondRequest.requestId)
            recreated.recreate()
        }
        val afterAcknowledgement = awaitResumedMainActivity(excluding = recreated)
        assertNull(afterAcknowledgement.pendingInboundShareRequestForTest)
        assertEquals(1, whiteNoiseTaskCount(targetContext))
    }

    private fun dispatchExternalShare(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.startActivity(
            Intent(instrumentation.context, ExternalShareDispatchActivity::class.java)
                .putExtra(ExternalShareDispatchActivity.EXTRA_TARGET_PACKAGE, instrumentation.targetContext.packageName)
                .putExtra(ExternalShareDispatchActivity.EXTRA_STREAM_URI, uri.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun externalFileUri(name: String): Uri {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val file = File(testContext.cacheDir, name)
        file.writeBytes(byteArrayOf(1, 2, 3))
        return FileProvider.getUriForFile(
            testContext,
            "${testContext.packageName}.external-share-test-files",
            file,
        )
    }

    private fun awaitPendingShare(uri: Uri): MainActivity =
        awaitResumedMainActivity { activity ->
            activity.pendingInboundShareRequestForTest?.payload?.streamUris == listOf(uri)
        }

    private fun awaitResumedMainActivity(
        excluding: MainActivity? = null,
        predicate: (MainActivity) -> Boolean = { true },
    ): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var match: MainActivity? = null
            instrumentation.runOnMainSync {
                match =
                    ActivityLifecycleMonitorRegistry
                        .getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .firstOrNull { activity -> activity !== excluding && predicate(activity) }
            }
            match?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        error("Timed out waiting for a resumed MainActivity")
    }

    private fun whiteNoiseTaskCount(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return activityManager.appTasks.count { task ->
            val info = task.taskInfo ?: return@count false
            info.baseActivity?.packageName == context.packageName ||
                info.topActivity?.packageName == context.packageName
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
