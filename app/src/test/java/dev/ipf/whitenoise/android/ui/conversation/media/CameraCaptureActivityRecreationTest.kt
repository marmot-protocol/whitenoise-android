package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the real camera launcher in a real ComponentActivity whose ActivityResultRegistry is saved and
 * restored by recreation while the app lock hides the conversation. The recreated Activity receives
 * the camera result, and after unlock exactly one readable photo joins the shelf with the draft intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraCaptureActivityRecreationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val captures = PendingCameraCaptureFiles()
    private val existing = Uri.parse("content://fixture/existing")
    private val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    private val locked = mutableStateOf(false)
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var launch: (() -> Unit)? = null
    private var editDraft: (() -> Unit)? = null
    private var visible = emptyList<Uri>()
    private var caption = ""
    private var reply: String? = null
    private var failures = 0
    private lateinit var output: Uri
    private var requestCode = 0

    /** Starts a real Activity hosting the protected-shell fixture. */
    @Before
    fun setUp() {
        captures.retainOwner("4:capture-account")
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        show()
    }

    /** Closes the Activity and removes every private capture output. */
    @After
    fun tearDown() {
        scenario.close()
        captures.releaseAll()
        if (::output.isInitialized) File(context.cacheDir, "camera/${output.lastPathSegment}").delete()
    }

    /** A result dispatched into the recreated Activity while locked stages one readable photo after unlock. */
    @Test
    fun resultIntoRecreatedActivityWhileLockedStagesOnce() {
        captureAndWrite()
        setLocked(true)
        recreate()
        dispatchIntoCurrentActivity()
        setLocked(false)
        assertOneStagedPhotoWithDraft()
        assertDuplicateIgnored()
    }

    /** A result parked by the locked Activity survives recreation in its saved registry and stages once. */
    @Test
    fun resultParkedBeforeRecreationStagesOnceAfterUnlock() {
        captureAndWrite()
        setLocked(true)
        dispatchIntoCurrentActivity()
        recreate()
        setLocked(false)
        assertOneStagedPhotoWithDraft()
        assertDuplicateIgnored()
    }

    /** A photo accepted before the lock and recreation stays on the restored shelf exactly once. */
    @Test
    fun resultBeforeLockSurvivesRecreation() {
        captureAndWrite()
        dispatchIntoCurrentActivity()
        setLocked(true)
        recreate()
        setLocked(false)
        assertOneStagedPhotoWithDraft()
        assertDuplicateIgnored()
    }

    /** A configuration-change recreation while unlocked keeps the shelf, caption and reply in place. */
    @Test
    fun unlockedRecreationKeepsTheProtectedDraft() {
        captureAndWrite()
        dispatchIntoCurrentActivity()
        recreate()
        assertOneStagedPhotoWithDraft()
    }

    /** Sets the fixture as the current Activity's content, as a freshly created Activity does. */
    private fun show() {
        scenario.onActivity { activity -> activity.setContent { Fixture() } }
        composeRule.waitForIdle()
    }

    /** The protected subtree: an existing queued photo, a caption, a reply target and the camera launcher. */
    @Composable
    private fun Fixture() {
        CompositionLocalProvider(LocalPendingCameraCaptureFiles provides captures) {
            val state = rememberProtectedShellSavedState("capture-account", 4, suspended = locked.value)
            if (!locked.value) {
                state.holder.SaveableStateProvider(state.key) {
                    var slots by rememberSaveable(stateSaver = PendingMediaSlotListSaver) {
                        mutableStateOf(appendPendingMediaSlots(emptyList(), listOf(existing), MAX_SLOTS))
                    }
                    var draftCaption by rememberSaveable { mutableStateOf("") }
                    var draftReply by rememberSaveable { mutableStateOf<String?>(null) }
                    launch =
                        rememberConversationCameraCapture(
                            origin = "capture-account:capture-group",
                            onCaptured = { uri ->
                                val updated = appendPendingMediaSlots(slots, listOf(uri), MAX_SLOTS)
                                val accepted = updated.size > slots.size
                                slots = updated
                                accepted
                            },
                            onFailure = { failures++ },
                            isCurrent = { true },
                            onRejected = { failures++ },
                        )
                    editDraft = {
                        draftCaption = "caption kept across the lock"
                        draftReply = "reply-target"
                    }
                    visible = slots.map { it.uri }
                    caption = draftCaption
                    reply = draftReply
                }
            }
        }
    }

    /** Writes a draft, launches the camera and writes the photo bytes the camera app would produce. */
    private fun captureAndWrite() {
        composeRule.runOnIdle {
            checkNotNull(editDraft).invoke()
            checkNotNull(launch).invoke()
        }
        composeRule.waitForIdle()
        scenario.onActivity { activity ->
            val started = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
            requestCode = started.requestCode
            output =
                checkNotNull(IntentCompat.getParcelableExtra(started.intent, MediaStore.EXTRA_OUTPUT, Uri::class.java))
        }
        context.contentResolver.openOutputStream(output)!!.use { it.write(photoBytes) }
    }

    /** Delivers RESULT_OK through the current Activity's own registry, as onActivityResult does. */
    private fun dispatchIntoCurrentActivity() {
        scenario.onActivity { activity ->
            assertTrue(
                "the registry must still map the camera request code",
                activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent()),
            )
        }
        composeRule.waitForIdle()
    }

    /** Recreates the Activity, saving and restoring its result registry and composition state. */
    private fun recreate() {
        scenario.recreate()
        show()
    }

    /** Shows or hides the lock gate and waits for the protected subtree to follow. */
    private fun setLocked(value: Boolean) {
        composeRule.runOnIdle { locked.value = value }
        composeRule.waitForIdle()
    }

    /** Exactly one new readable photo follows the existing slot, and the caption and reply survive. */
    private fun assertOneStagedPhotoWithDraft() {
        composeRule.runOnIdle {
            assertEquals(listOf(existing, output), visible)
            assertEquals("caption kept across the lock", caption)
            assertEquals("reply-target", reply)
            assertEquals(0, failures)
        }
        context.contentResolver.openInputStream(output)!!.use { assertArrayEquals(photoBytes, it.readBytes()) }
    }

    /** A second platform delivery of the same result cannot add another slot. */
    private fun assertDuplicateIgnored() {
        scenario.onActivity { activity ->
            activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent())
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf(existing, output), visible) }
    }
}

private const val MAX_SLOTS = 10
