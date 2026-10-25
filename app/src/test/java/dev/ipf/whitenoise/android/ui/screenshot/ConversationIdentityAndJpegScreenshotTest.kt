package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.conversation.composer.composerSendingAccount
import dev.ipf.whitenoise.android.ui.conversation.messages.PublicJpegPreviewImage
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationIdentityAndJpegScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val alice = testAccount("alice", "aa".repeat(32))
    private val bob = testAccount("bob", "bb".repeat(32))

    @Test fun sendingIdentityLight() = render(dark = false, amoled = false)

    @Test fun sendingIdentityDark() = render(dark = true, amoled = false)

    @Test fun sendingIdentityAmoledRtlLarge() = render(dark = true, amoled = true, rtl = true)

    @Test fun singleAccountAndMissingOwnerNeverSubstituteAnIdentity() {
        assertNull(composerSendingAccount(emptyAppState(accounts = listOf(alice)), "alice"))
        val state = emptyAppState(accounts = listOf(alice, bob), activeAccountRef = "bob")
        assertNull(composerSendingAccount(state, "removed"))
        assertEquals(alice, composerSendingAccount(state, "alice"))
    }

    private fun render(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean = false,
    ) {
        val state = emptyAppState(accounts = listOf(alice, bob), activeAccountRef = "bob")
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff39756c.toInt()) }
        val callbacks = mutableListOf<android.window.OnBackInvokedCallback>()
        val registrar =
            ComposerOverlayBackRegistrar { _, callback ->
                callbacks.add(callback)
                val unregister: () -> Unit = {
                    callbacks.remove(callback)
                    Unit
                }
                unregister
            }
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalCursorBlinkEnabled provides false) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 1.6f else 1f) {
                    Surface(Modifier.width(360.dp).testTag("polish-preview")) {
                        Column {
                            PublicJpegPreviewImage(image.asImageBitmap())
                            Text("images.example/linked-image.jpg")
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                initialDraft = TextFieldValue("I’ll bring the route map."),
                                draftAccountRef = "alice",
                                appState = state,
                                overlayBackRegistrar = registrar,
                                onPickFromGallery = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("composer-sending-identity").assertContentDescriptionEquals("Sending as, alice")
        composeRule.onNodeWithTag("composer-sending-identity").performClick()
        composeRule.onNodeWithText("Sending as").assertIsDisplayed()
        composeRule.onNodeWithText("I’ll bring the route map.").assertIsDisplayed()
        composeRule.onNodeWithTag("composer-sending-identity-popup").captureRoboImage(
            "src/test/snapshots/sending_identity_popup_${if (amoled) {
                "amoled_rtl_large"
            } else if (dark) {
                "dark"
            } else {
                "light"
            }}.png",
        )
        // The popup capture above includes the complete window: sender, draft and JPEG decoration.
        // Avoid a second rasterization of the same unchanged frame.
        composeRule.runOnIdle { callbacks.last().onBackInvoked() }
        composeRule.onNodeWithTag("composer-sending-identity-popup").assertDoesNotExist()
        composeRule.onNodeWithText("I’ll bring the route map.").assertIsDisplayed()
    }
}
