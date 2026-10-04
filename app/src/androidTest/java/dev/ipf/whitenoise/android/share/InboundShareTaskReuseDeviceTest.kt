package dev.ipf.whitenoise.android.share

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WarmResumeRenderedSurface
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.ui.share.SHARE_CHAT_PICKER_SCREEN_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Repeated external shares reuse one task, keep only the newest request and draw the picker before returning. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class InboundShareTaskReuseDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

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

        dispatchExternalShare("first.bin")
        val firstDelivery = awaitPendingShare("first.bin")
        val firstRequestId = checkNotNull(firstDelivery.pendingInboundShareRequestForTest).requestId
        assertEquals(taskId, firstDelivery.taskId)
        assertEquals(1, whiteNoiseTaskCount(targetContext))

        instrumentation.runOnMainSync { firstDelivery.moveTaskToBack(true) }
        dispatchExternalShare("second.bin")
        val secondDelivery = awaitPendingShare("second.bin")
        val secondRequest = checkNotNull(secondDelivery.pendingInboundShareRequestForTest)
        assertSame(firstDelivery, secondDelivery)
        assertTrue(secondRequest.requestId != firstRequestId)
        val secondStreamUri = secondRequest.payload.streamUris.single()
        assertEquals("second.bin", sharedName(secondStreamUri))
        assertEquals(
            "${targetContext.packageName}.private-share",
            secondStreamUri.authority,
        )
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

    /**
     * Verifies warm text and multi-file intents draw the picker before any
     * underlying route, preserve external URI grants, and return to the exact
     * prior in-memory route on committed Back.
     */
    @Test
    @ManualDeviceFixture
    fun warmTextAndMultipleFileSharesDrawPickerFirstAndCancelToThePriorRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent =
            checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        targetContext.startActivity(launchIntent)
        val initial = awaitResumedMainActivity()
        val application = targetContext as WhiteNoiseApplication
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            application.appState.phase != AppPhase.Bootstrapping
        }
        assumeTrue(
            "Requires an authenticated, unlocked device fixture",
            application.appState.phase == AppPhase.Ready && !application.appState.appLockScreenVisible,
        )
        val priorRoute = application.mainShellProcessState.selectedChat.value

        WarmResumeTrace.resetRenderedSurfaceFrames()
        dispatchExternalText("first-frame text")
        val textDelivery = awaitPendingText("first-frame text")
        assertPickerOwnsFirstDraw()
        instrumentation.runOnMainSync { textDelivery.onBackPressedDispatcher.onBackPressed() }
        awaitNoPendingShare(textDelivery)
        assertSame(priorRoute, application.mainShellProcessState.selectedChat.value)

        WarmResumeTrace.resetRenderedSurfaceFrames()
        dispatchExternalMultiple("first.bin", "second.bin")
        val multipleDelivery = awaitPendingStreams(listOf("first.bin", "second.bin"))
        val streams = checkNotNull(multipleDelivery.pendingInboundShareRequestForTest).payload.streamUris
        streams.forEach { uri ->
            targetContext.contentResolver.openInputStream(uri)?.use { input ->
                assertEquals(1, input.read())
            } ?: error("Missing retained read grant for $uri")
        }
        assertPickerOwnsFirstDraw()
        instrumentation.runOnMainSync { multipleDelivery.onBackPressedDispatcher.onBackPressed() }
        awaitNoPendingShare(multipleDelivery)
        assertSame(priorRoute, application.mainShellProcessState.selectedChat.value)
    }

    /** Real cross-UID grant boundary, private provider and destination recovery, without a signed-in fixture. */
    @Test
    fun privateImportSurvivesRevokedGrantAndDestinationStoreRecreation() =
        kotlinx.coroutines.runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            dispatchExternalMultiple("intake.md", "arbitrary.bin")
            val delivery = awaitPendingStreams(listOf("intake.md", "arbitrary.bin"))
            val imported = checkNotNull(delivery.pendingInboundShareRequestForTest)
            val files = PrivateShareFiles(target)
            assertTrue(imported.payload.importErrors.isEmpty())
            assertEquals(2, imported.payload.streamUris.size)
            val sources =
                listOf("intake.md", "arbitrary.bin").map { name ->
                    ExternalShareTestFileProvider.uriFor(
                        instrumentation.context,
                        java.io.File(instrumentation.context.cacheDir, name),
                    )
                }
            target.contentResolver
                .query(
                    sources.first(),
                    arrayOf("private_readable"),
                    null,
                    arrayOf(
                        imported.payload.streamUris
                            .first()
                            .toString(),
                    ),
                    null,
                )!!
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("A separate provider UID must not read private intake", 0, cursor.getInt(0))
                }
            sources.forEach { source ->
                target.contentResolver.query(source, arrayOf("revoke_read_grant"), null, null, null)!!.close()
                try {
                    target.contentResolver.openInputStream(source)
                    org.junit.Assert.fail("External grant should have been revoked")
                } catch (_: SecurityException) {
                }
            }
            imported.payload.streamUris.forEach { uri ->
                target.contentResolver.openInputStream(uri)!!.use { input ->
                    org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
                }
            }
            assertEquals("text/markdown", target.contentResolver.getType(imported.payload.streamUris.first()))
            files.leases.saveShelf("disposable-account", "disposable-chat", imported.payload.streamUris)
            files.leases.releaseRequest(imported.requestId)
            assertEquals(
                imported.payload.streamUris,
                PrivateShareFiles(target).leases.loadShelf("disposable-account", "disposable-chat"),
            )
            assertTrue(files.leases.loadShelf("different-account", "disposable-chat").isEmpty())
            files.leases.saveShelf("disposable-account", "disposable-chat", emptyList())
            imported.payload.streamUris.forEach { assertNull(files.resolve(it)) }
            instrumentation.runOnMainSync { delivery.acknowledgeInboundShareForTest(imported.requestId) }
        }

    private fun dispatchExternalShare(streamName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.startActivity(
            Intent(instrumentation.context, ExternalShareDispatchActivity::class.java)
                .putExtra(ExternalShareDispatchActivity.EXTRA_TARGET_PACKAGE, instrumentation.targetContext.packageName)
                .putExtra(ExternalShareDispatchActivity.EXTRA_STREAM_NAME, streamName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Dispatches a text-only share from the separate instrumentation APK. */
    private fun dispatchExternalText(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.startActivity(
            Intent(instrumentation.context, ExternalShareDispatchActivity::class.java)
                .putExtra(ExternalShareDispatchActivity.EXTRA_TARGET_PACKAGE, instrumentation.targetContext.packageName)
                .putExtra(ExternalShareDispatchActivity.EXTRA_SHARE_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Dispatches multiple provider-backed streams with temporary external read grants. */
    private fun dispatchExternalMultiple(vararg streamNames: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.startActivity(
            Intent(instrumentation.context, ExternalShareDispatchActivity::class.java)
                .putExtra(ExternalShareDispatchActivity.EXTRA_TARGET_PACKAGE, instrumentation.targetContext.packageName)
                .putExtra(ExternalShareDispatchActivity.EXTRA_STREAM_NAMES, streamNames)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun sharedName(uri: android.net.Uri): String? {
        if (uri.authority?.endsWith(".private-share") != true) return uri.lastPathSegment
        return InstrumentationRegistry
            .getInstrumentation()
            .targetContext.contentResolver
            .query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun awaitPendingShare(streamName: String): MainActivity =
        awaitResumedMainActivity { activity ->
            activity.pendingInboundShareRequestForTest
                ?.payload
                ?.streamUris
                ?.singleOrNull()
                ?.let(::sharedName) == streamName &&
                activity.pendingInboundShareRequestForTest?.payload?.importReady == true
        }

    /** Waits for the exact text request delivered through `onNewIntent`. */
    private fun awaitPendingText(text: String): MainActivity =
        awaitResumedMainActivity { activity ->
            activity.pendingInboundShareRequestForTest?.payload?.text == text
        }

    /** Waits until every ordered stream name is retained by the target Activity. */
    private fun awaitPendingStreams(streamNames: List<String>): MainActivity =
        awaitResumedMainActivity { activity ->
            activity.pendingInboundShareRequestForTest
                ?.payload
                ?.streamUris
                ?.map(::sharedName) == streamNames &&
                activity.pendingInboundShareRequestForTest?.payload?.importReady == true
        }

    /** Asserts actual draw evidence, not only synchronous intent state. */
    private fun assertPickerOwnsFirstDraw() {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            WarmResumeTrace.renderedSurfaceFrames().isNotEmpty()
        }
        val frames = WarmResumeTrace.renderedSurfaceFrames()
        assertEquals(
            "Unexpected rendered surface sequence: $frames",
            WarmResumeRenderedSurface.SharePicker,
            frames.first().surface,
        )
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG).assertExists()
    }

    /** Waits for dismissal to clear both one-shot and picker-owned in-memory state. */
    private fun awaitNoPendingShare(activity: MainActivity) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            activity.pendingInboundShareRequestForTest == null
        }
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
