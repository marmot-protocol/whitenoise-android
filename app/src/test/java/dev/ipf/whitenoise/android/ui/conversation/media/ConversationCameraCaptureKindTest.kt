package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The camera reserves a photo output and a video output; only the one it reports is staged, the other is removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationCameraCaptureKindTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camera = FakeCamera()
    private val files = PendingCameraCaptureFiles()
    private val staged = mutableListOf<Uri>()
    private var failures = 0
    private var launch: (() -> Unit)? = null

    /** Releases every hold and removes whatever outputs a test left behind. */
    @After
    fun tearDown() {
        files.releaseAll()
        listOfNotNull(camera.outputs?.photo, camera.outputs?.video).forEach { output(it).delete() }
    }

    /** A recorded video goes to the shelf as the `.mp4` output, and the unused photo output is removed. */
    @Test
    fun recordedVideoIsStagedAndThePhotoOutputRemoved() {
        launchCamera()
        val outputs = checkNotNull(camera.outputs)
        write(outputs.video)

        composeRule.runOnIdle { camera.deliver(CameraCaptureKind.Video) }

        composeRule.runOnIdle {
            assertEquals(listOf(outputs.video), staged)
            assertEquals(0, failures)
        }
        assertTrue(checkNotNull(outputs.video.lastPathSegment).endsWith(".mp4"))
        assertTrue(output(outputs.video).exists())
        assertFalse(output(outputs.photo).exists())
    }

    /** A photo goes to the shelf as the `.jpg` output, and the unused video output is removed. */
    @Test
    fun photoIsStagedAndTheVideoOutputRemoved() {
        launchCamera()
        val outputs = checkNotNull(camera.outputs)
        write(outputs.photo)

        composeRule.runOnIdle { camera.deliver(CameraCaptureKind.Photo) }

        composeRule.runOnIdle { assertEquals(listOf(outputs.photo), staged) }
        assertTrue(checkNotNull(outputs.photo.lastPathSegment).endsWith(".jpg"))
        assertFalse(output(outputs.video).exists())
    }

    /** Closing the camera without capturing stages nothing, reports nothing and removes both outputs. */
    @Test
    fun closingTheCameraRemovesBothOutputs() {
        launchCamera()
        val outputs = checkNotNull(camera.outputs)

        composeRule.runOnIdle { camera.deliver(null) }

        composeRule.runOnIdle {
            assertEquals(emptyList<Uri>(), staged)
            assertEquals(0, failures)
        }
        assertFalse(output(outputs.photo).exists())
        assertFalse(output(outputs.video).exists())
    }

    /** A reported video whose output is empty fails visibly, stages nothing and removes both outputs. */
    @Test
    fun anEmptyReportedOutputFailsAndRemovesBoth() {
        launchCamera()
        val outputs = checkNotNull(camera.outputs)

        composeRule.runOnIdle { camera.deliver(CameraCaptureKind.Video) }

        composeRule.runOnIdle {
            assertEquals(emptyList<Uri>(), staged)
            assertEquals(1, failures)
        }
        assertFalse(output(outputs.photo).exists())
        assertFalse(output(outputs.video).exists())
    }

    /** Mounts the capture launcher over the fake camera and opens the camera once. */
    private fun launchCamera() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides camera,
                LocalPendingCameraCaptureFiles provides files,
            ) {
                launch =
                    rememberConversationCameraCapture(
                        origin = "account:group",
                        onCaptured = { uri -> staged.add(uri) },
                        onFailure = { failures++ },
                        isCurrent = { true },
                        onRejected = {},
                    )
            }
        }
        composeRule.runOnIdle { checkNotNull(launch).invoke() }
        composeRule.waitForIdle()
    }

    /** Writes capture bytes into [uri], as the camera does for the output it reports. */
    private fun write(uri: Uri) {
        context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
    }

    /** The private file behind a camera output [uri]. */
    private fun output(uri: Uri): File = File(context.cacheDir, "camera/${uri.lastPathSegment}")
}

/** Records the camera launch instead of starting the camera, then returns the result a test chooses. */
private class FakeCamera :
    ActivityResultRegistry(),
    ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    private var requestCode = 0
    var outputs: CameraOutputs? = null

    /** Keeps the outputs the launch reserved and the request to answer. */
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        this.requestCode = requestCode
        outputs = input as CameraOutputs
    }

    /** Returns [kind] as captured, or closes the camera without capturing when it is null. */
    fun deliver(kind: CameraCaptureKind?) {
        dispatchResult(
            requestCode,
            if (kind != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            kind?.let(CaptureConversationMedia::result),
        )
    }
}
