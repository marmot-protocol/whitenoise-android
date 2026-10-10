package dev.ipf.whitenoise.android.ui.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.GROUP_A
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerFullScreenContent
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChat
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises live IME resize on the real picker, including responsive search's composition identity. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DestinationPickerImeScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Portrait with enlarged text keeps a complete tappable destination above the raised footer. */
    @Test
    fun forwardPortraitLargeTextKeepsResultsAboveKeyboard() {
        render(share = false, fontScale = 1.6f)
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("Alice")
        dispatchIme(300)
        composeRule.onNodeWithTag("forward.destinations").performScrollToNode(hasText("Alice"))
        val bounds = composeRule.onNodeWithTag("forward.destinations").fetchSemanticsNode().boundsInRoot
        assertTrue("destination viewport retains a full touch target", bounds.height >= 48f)
        composeRule
            .onNode(hasText("Alice") and hasAnyAncestor(hasTestTag("forward.destinations")))
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithText("Forward to 1 chat").assertIsDisplayed()
        val ownerLabel = composeRule.activity.getString(R.string.share_sending_as_value, "Alex")
        composeRule.onNodeWithContentDescription(ownerLabel).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/forward_picker_portrait_ime_large.png")
        dispatchIme(0)
    }

    /** Forward search remains focused when a tablet's IME switches filters into the horizontal layout. */
    @Test
    @Config(sdk = [36], qualifiers = "w700dp-h900dp-mdpi")
    fun forwardSearchSurvivesResponsiveImeTransition() = verifyResponsiveSearch(share = false)

    /** The system-share entry point retains the same focused field across that layout transition. */
    @Test
    @Config(sdk = [36], qualifiers = "w700dp-h900dp-mdpi")
    fun shareSearchSurvivesResponsiveImeTransition() = verifyResponsiveSearch(share = true)

    /** Types continuously through both threshold crossings without requesting focus a second time. */
    private fun verifyResponsiveSearch(share: Boolean) {
        render(share, 1f)
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("Al")
        dispatchIme(400)
        composeRule.onNode(hasSetTextAction()).assertIsFocused().performTextInput("ic")
        dispatchIme(0)
        composeRule.onNode(hasSetTextAction()).assertIsFocused().performTextInput("e")
        val listTag = if (share) "share.destinations" else "forward.destinations"
        composeRule
            .onNode(hasText("Alice") and !hasSetTextAction() and hasAnyAncestor(hasTestTag(listTag)))
            .assertIsDisplayed()
    }

    /** Seeds a real folder and recipient while keeping the fixture independent of native/network state. */
    private fun render(
        share: Boolean,
        fontScale: Float,
    ) {
        val state =
            appStateWithDirectChat(
                GROUP_A,
                PEER_A,
                mutableMapOf(PEER_A to profile("Alice"), ACCOUNT_HEX to profile("Alex")),
            )
        val store = state.chatFolderPreferences
        store.clearAllForAccount(ACCOUNT_REF)
        val folder = requireNotNull(store.createFolder(ACCOUNT_REF, "Friends"))
        store.setChatInFolder(ACCOUNT_REF, folder.id, GROUP_A, true)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                WhiteNoiseTheme(darkTheme = true) {
                    if (share) {
                        ShareChatPickerFullScreenContent(
                            appState = state,
                            payload = SharePayload("shared text", emptyList(), "text/plain"),
                            onDismiss = {},
                            onStage = { _, _ -> true },
                        )
                    } else {
                        ForwardMessagePickerContent(
                            appState = state,
                            messageCount = 11,
                            attachmentCount = 11,
                            originGroupIdHex = "ff".repeat(32),
                            sourceAccountRef = ACCOUNT_REF,
                            onDismiss = {},
                            onForward = { _, _ -> true },
                        )
                    }
                }
            }
        }
    }

    /** Dispatches actual window insets so production Scaffold and footer consumption drive the resize. */
    private fun dispatchIme(bottom: Int) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView.rootView, insets)
        }
        composeRule.waitForIdle()
    }
}
