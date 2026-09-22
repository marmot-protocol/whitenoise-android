package dev.ipf.whitenoise.android.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the composer attachment sheet: the attach icon
 * opens it, its action tiles fire the hoisted callbacks and close it, the
 * emoji toggle displaces it, and unavailable acquisition sources stay absent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerAttachmentSheetBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = app.getString(resId)

    private fun renderComposer(
        onPickFromGallery: (() -> Unit)? = {},
        onCaptureFromCamera: (() -> Unit)? = null,
        onPickDocument: (() -> Unit)? = {},
        onShareLocation: (() -> Unit)? = null,
        onShareUser: (() -> Unit)? = null,
        onShareContact: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.width(360.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        onPickFromGallery = onPickFromGallery,
                        onCaptureFromCamera = onCaptureFromCamera,
                        onPickDocument = onPickDocument,
                        onShareLocation = onShareLocation,
                        onShareUser = onShareUser,
                        onShareContact = onShareContact,
                    )
                }
            }
        }
    }

    private fun openAttachmentSheet() {
        composeRule.onNodeWithContentDescription(string(R.string.attach_options)).performClick()
        composeRule.waitForIdle()
    }

    /** Tapping attach opens the sheet. */
    @Test
    fun tappingAttachOpensTheSheet() {
        renderComposer()
        openAttachmentSheet()
        composeRule.onNodeWithText(string(R.string.attachment_photos_videos)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.download_files)).assertIsDisplayed()
    }

    /** The sole media entry invokes the system picker directly and closes the menu. */
    @Test
    fun mediaEntryInvokesGalleryOnceAndClosesTheMenu() {
        var galleryClicks = 0
        renderComposer(onPickFromGallery = { galleryClicks++ })
        openAttachmentSheet()
        composeRule.onNodeWithText(string(R.string.attachment_photos_videos)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, galleryClicks)
        composeRule.onNodeWithText(string(R.string.attachment_photos_videos)).assertDoesNotExist()
    }

    /** Document tile fires callback. */
    @Test
    fun documentTileFiresCallback() {
        var documentClicks = 0
        renderComposer(onPickDocument = { documentClicks++ })
        openAttachmentSheet()
        composeRule.onNodeWithText(string(R.string.download_files)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, documentClicks)
    }

    /** Camera only composer uses the single attachment trigger. */
    @Test
    fun cameraOnlyComposerUsesTheSingleAttachmentTrigger() {
        var cameraClicks = 0
        renderComposer(
            onPickFromGallery = null,
            onCaptureFromCamera = { cameraClicks++ },
            onPickDocument = null,
        )

        composeRule.onNodeWithContentDescription(string(R.string.attach_options)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.attachment_camera)).assertDoesNotExist()
        openAttachmentSheet()
        composeRule.onNodeWithText(string(R.string.attachment_camera)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, cameraClicks)
        composeRule.onNodeWithText(string(R.string.attachment_camera)).assertDoesNotExist()
    }

    /** Opening emoji picker closes the sheet. */
    @Test
    fun openingEmojiPickerClosesTheSheet() {
        renderComposer()
        openAttachmentSheet()
        composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.attachment_photos_videos)).assertDoesNotExist()
    }

    /** Contact tile fires callback and closes the sheet. */
    @Test
    fun contactTileFiresCallbackAndClosesTheSheet() {
        var contactClicks = 0
        renderComposer(onShareContact = { contactClicks++ })
        assertEquals(0, contactClicks)
        openAttachmentSheet()
        // Composing the sheet alone must not start the flow — only the tap may.
        assertEquals(0, contactClicks)
        composeRule.onNodeWithText(string(R.string.attachment_device_contact)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, contactClicks)
        composeRule.onNodeWithText(string(R.string.attachment_device_contact)).assertDoesNotExist()
    }

    @Test
    fun locationTileFiresCallbackOnlyAfterTap() {
        var locationClicks = 0
        renderComposer(onShareLocation = { locationClicks++ })
        assertEquals(0, locationClicks)
        openAttachmentSheet()
        // The permission request lives inside this callback, so "no fire before
        // tap" is exactly "no permission prompt before tap".
        assertEquals(0, locationClicks)
        composeRule.onNodeWithText(string(R.string.attach_location)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, locationClicks)
    }

    /** User tile fires callback only after tap. */
    @Test
    fun userTileFiresCallbackOnlyAfterTap() {
        var userClicks = 0
        renderComposer(onShareUser = { userClicks++ })
        assertEquals(0, userClicks)
        openAttachmentSheet()
        assertEquals(0, userClicks)
        composeRule.onNodeWithText(string(R.string.attach_contact)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, userClicks)
        composeRule.onNodeWithText(string(R.string.attach_contact)).assertDoesNotExist()
    }

    /** Unavailable sources are not offered as working commands. */
    @Test
    fun unavailableSourcesAreNotOfferedAsWorkingCommands() {
        renderComposer()
        openAttachmentSheet()
        composeRule.onNodeWithText(string(R.string.attachment_camera)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.attach_location)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.attach_contact)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.attachment_device_contact)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.coming_soon)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.attachment_photos_videos)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.download_files)).assertIsDisplayed()
    }
}
