package dev.ipf.whitenoise.android.maestro

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private const val ATTACHMENT_SLOT_TAG_PREFIX = "conversation.composer.attachment."
private const val CAPTION = "Camera lock caption"
private const val WAIT_MILLIS = 30_000L
private const val JPEG_EDGE = 32
private const val JPEG_QUALITY = 90
private const val LOCKED_DWELL_MILLIS = 1_500L

/**
 * Real MainActivity, native account and platform activity-result path: a camera result that lands
 * around app-lock disposal is restored to its conversation exactly once after unlock (#3354).
 *
 * The camera is stubbed by an [Instrumentation.ActivityMonitor] — the platform then delivers the
 * stub result through the ordinary `sendActivityResult` path. The lock is shown through the same
 * reflection seam the unit suite uses, because the isolated emulator has no device credential, and
 * cleared through [WhiteNoiseAppState.markAppUnlockSucceeded], the production unlock completion.
 * Grant CAMERA to the fixture package with `adb shell pm grant` before running it.
 */
@ManualDeviceFixture
class CameraCaptureAppLockDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A capture accepted while the lock disposes the shell is staged once, beside the queued photo and caption. */
    @Test fun captureAcrossAppLockRestoresOneStagedPhotoWithCaption() =
        runBlocking {
            val context = instrumentation.targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            check(InstrumentationRegistry.getArguments().getString("cameraLockE2e") == "true") {
                "Select cameraLockE2e=true in the isolated fixture package"
            }
            journey(context)
        }

    /** Opens a disposable native account and group, mounts the real app and always tears both down. */
    private suspend fun journey(context: Context) {
        val root = File(context.filesDir, "camera-lock-${UUID.randomUUID()}").apply { mkdirs() }
        MarmotAndroid.initialize(context)
        // The Maestro host never claims the UI-automation connection, so the operator grants CAMERA up front.
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Grant CAMERA first: adb shell pm grant ${context.packageName} ${Manifest.permission.CAMERA}"
        }
        LoopbackNostrRelay().use { relay ->
            val native = openNative(File(root, "owner"), relay.url)
            var app: WhiteNoiseAppState? = null
            var activity: ActivityScenario<MainActivity>? = null
            val camera = StubCamera(context)
            instrumentation.addMonitor(camera)
            try {
                val account = native.createIdentity(listOf(relay.url), listOf(relay.url))
                val group = native.createGroup(account.label, "Camera lock fixture", emptyList(), null)
                val state = createState(context, root, native, listOf(account))
                app = state
                camera.state = state
                (context.applicationContext as MaestroFixtureApplication).fixtureState = state
                withTimeout(WAIT_MILLIS) { state.bootstrap() }
                assertEquals(AppPhase.Ready, state.phase)
                activity = ActivityScenario.launch(MainActivity::class.java)
                captureAcrossLock(state, native, account.label, group, camera)
            } finally {
                instrumentation.removeMonitor(camera)
                activity?.close()
                closeState(app)
                withContext(NonCancellable) { native.shutdownAndClose() }
                context.deleteSharedPreferences(root.name)
                check(root.deleteRecursively())
            }
        }
    }

    /** Queues one unlocked capture, then crosses the lock with a second and verifies the restored shelf. */
    private suspend fun captureAcrossLock(
        state: WhiteNoiseAppState,
        native: Marmot,
        account: String,
        group: String,
        camera: StubCamera,
    ) {
        openChatAndTypeCaption(group)
        val messagesBefore = timelineCount(native, account, group)
        capture()
        awaitSlots(1)

        camera.lockOnNextCapture = true
        capture()
        compose.waitUntil(WAIT_MILLIS) { state.appLockScreenVisible }
        compose.waitUntil(WAIT_MILLIS) { slotCount() == 0 }
        // Hold the lock across several frames so the result and disposal settle while protected content is gone.
        delay(LOCKED_DWELL_MILLIS)
        compose.waitForIdle()
        assertTrue("protected shelf must stay uncomposed while locked", slotCount() == 0)
        withContext(Dispatchers.Main.immediate) {
            state.markAppUnlockSucceeded(dismissRetainedVisibleConversation = false)
        }

        awaitSlots(2)
        compose.waitForIdle()
        assertEquals("a duplicate result must not add another slot", 2, slotCount())
        assertEquals(2, camera.launches)
        assertReadableJpeg(instrumentation.targetContext, camera.outputs.last())
        assertTrue(
            "the existing caption must survive lock disposal",
            compose
                .onAllNodesWithText(CAPTION, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertEquals("nothing may be sent automatically", messagesBefore, timelineCount(native, account, group))
    }

    /** Opens the fixture conversation from the chat list and types an unsent caption into its composer. */
    private fun openChatAndTypeCaption(group: String) {
        waitTag("chat.row.$group")
        compose.onNodeWithTag("chat.row.$group").performClick()
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(CAPTION)
        compose.waitForIdle()
    }

    /** Uses the public attachment control and its Camera entry, exactly as a person would. */
    private fun capture() {
        val context = instrumentation.targetContext
        val attach = context.getString(R.string.attach_options)
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription(attach, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription(attach, useUnmergedTree = true).onFirst().performClick()
        val camera = context.getString(R.string.attachment_camera)
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithText(camera).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(camera).onFirst().performClick()
    }

    /** Waits for exactly [count] staged shelf slots. */
    private fun awaitSlots(count: Int) {
        compose.waitUntil(WAIT_MILLIS) { slotCount() == count }
    }

    /** Counts shelf slots by their indexed tag, excluding the shelf container itself. */
    private fun slotCount(): Int =
        compose
            .onAllNodes(
                SemanticsMatcher("attachment slot") { node ->
                    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(ATTACHMENT_SLOT_TAG_PREFIX) == true
                },
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .size

    /** Bounds waiting for a production screen rather than assuming animation or native lookup timing. */
    private fun waitTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}

/**
 * Answers ACTION_IMAGE_CAPTURE in-process: writes real JPEG bytes to EXTRA_OUTPUT and returns
 * RESULT_OK through the platform result path, optionally raising the app lock first.
 */
private class StubCamera(
    private val context: Context,
) : Instrumentation.ActivityMonitor() {
    @Volatile var state: WhiteNoiseAppState? = null

    @Volatile var lockOnNextCapture = false

    @Volatile var launches = 0

    val outputs = mutableListOf<Uri>()

    /** Intercepts only the camera intent, leaving every other activity start to the platform. */
    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
        val output = checkNotNull(IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java))
        checkNotNull(context.contentResolver.openOutputStream(output)).use { it.write(jpegBytes()) }
        outputs += output
        launches++
        if (lockOnNextCapture) {
            lockOnNextCapture = false
            checkNotNull(state).setAppLockScreenVisibleForDeviceTest(true)
        }
        return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
    }
}

/** Encodes a small real JPEG so the staged slot exercises the decoder, not a placeholder. */
private fun jpegBytes(): ByteArray {
    val bitmap = Bitmap.createBitmap(JPEG_EDGE, JPEG_EDGE, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        bitmap.recycle()
        stream.toByteArray()
    }
}

/** The capture output must still hold the camera's JPEG after the lock crossing. */
private fun assertReadableJpeg(
    context: Context,
    uri: Uri,
) {
    val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
    assertTrue("capture output must be non-empty", bytes.size > 2)
    assertTrue("capture output must be a JPEG", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
}

/** Mirrors the unit suite's lock seam — the isolated emulator deliberately has no device credential. */
private fun WhiteNoiseAppState.setAppLockScreenVisibleForDeviceTest(visible: Boolean) {
    val delegate = WhiteNoiseAppState::class.java.getDeclaredField("appLockScreenVisible\$delegate")
    delegate.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (delegate.get(this) as MutableState<Boolean>).value = visible
}

/** Reads the native timeline size so an automatic send cannot hide behind the restored shelf. */
private suspend fun timelineCount(
    native: Marmot,
    account: String,
    group: String,
): Int =
    withContext(Dispatchers.IO) {
        native
            .timelineMessages(account, TimelineMessageQueryFfi(group, null, null, null, null, null, 100u))
            .messages
            .size
    }

/** Creates a real account-bound state with background push disabled only in the isolated fixture. */
private suspend fun createState(
    context: Context,
    root: File,
    native: Marmot,
    accounts: List<AccountSummaryFfi>,
) = withContext(Dispatchers.Main.immediate) {
    WhiteNoiseAppState(
        context,
        DraftStore.forContext(context),
        { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex },
        accounts,
        accounts[0].label,
        profileReader = { id -> withContext(Dispatchers.IO) { native.userProfile(id) } },
        profileRefreshRequest = { id -> withContext(Dispatchers.IO) { native.refreshProfile(id, emptyList()) } },
        marmotRuntimeFactory = { AppMarmotRuntime(File(root, "owner").path, native) },
        schedulePushWakeRecovery = { false },
        preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
    )
}

/** Starts a real native client whose endpoints are restricted to the loopback fixture. */
private suspend fun openNative(
    root: File,
    relay: String,
): Marmot =
    Marmot
        .newWithConfiguration(
            root.path,
            listOf(relay),
            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
        ).also { it.start() }

/** Drains all fixture jobs before native storage is released. */
private suspend fun closeState(app: WhiteNoiseAppState?) =
    withContext(NonCancellable) {
        withTimeout(15_000) {
            withContext(Dispatchers.Main.immediate) { app?.accountSetup?.close() }
            app?.stopNotificationListenerForAccountTeardown()
            app
                ?.mutationsScope
                ?.coroutineContext
                ?.get(Job)
                ?.cancelAndJoin()
        }
    }
