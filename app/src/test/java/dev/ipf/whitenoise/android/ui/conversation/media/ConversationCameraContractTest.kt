package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The camera launch carries both reserved outputs, and only a captured kind counts as a result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationCameraContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contract = CaptureConversationMedia()
    private val outputs =
        CameraOutputs(
            photo = Uri.parse("content://camera/photo.jpg"),
            video = Uri.parse("content://camera/video.mp4"),
        )

    /** The launch opens the in-app camera with the photo and video outputs it may write. */
    @Test
    fun launchOpensTheInAppCameraWithBothOutputs() {
        val intent = contract.createIntent(context, outputs)

        assertEquals(ConversationCameraActivity::class.java.name, intent.component?.className)
        assertEquals(outputs, CaptureConversationMedia.outputs(intent))
    }

    /** A launch missing either output has none, so the camera closes instead of writing anywhere else. */
    @Test
    fun aLaunchMissingAnOutputHasNone() {
        val photoOnly = Intent().putExtra(CaptureConversationMedia.EXTRA_PHOTO_OUTPUT, outputs.photo)

        assertNull(CaptureConversationMedia.outputs(photoOnly))
    }

    /** Each captured kind round-trips; a cancelled, unnamed or unknown result captures nothing. */
    @Test
    fun onlyACapturedKindIsAResult() {
        CameraCaptureKind.entries.forEach { kind ->
            assertEquals(kind, contract.parseResult(Activity.RESULT_OK, CaptureConversationMedia.result(kind)))
        }
        val photo = CaptureConversationMedia.result(CameraCaptureKind.Photo)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, photo))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
        val unknown = Intent().putExtra(CaptureConversationMedia.EXTRA_CAPTURED_KIND, "Panorama")
        assertNull(contract.parseResult(Activity.RESULT_OK, unknown))
    }
}
