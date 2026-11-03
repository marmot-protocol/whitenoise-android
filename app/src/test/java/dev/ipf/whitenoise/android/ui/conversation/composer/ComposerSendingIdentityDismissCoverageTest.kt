package dev.ipf.whitenoise.android.ui.conversation.composer

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.window.OnBackInvokedCallback
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #3553: the composer's "Sending as" confirmation is a non-focusable popup in its own window. An outside
 * press must close it exactly once and never reach what lies beneath — including the avatar that opened
 * it, which would otherwise reopen it on the same tap. Taps here are injected at screen coordinates
 * through Robolectric's window dispatcher, so they hit-test the popup, scrim and host windows in z-order
 * the way a finger does; a semantic click would bypass that boundary. Outside taps aim at the composer
 * row (avatar, Send): Robolectric places the scrim window at its anchor without the WindowManager's
 * on-screen clamping, so only the anchor's row and below are covered by it in this harness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerSendingIdentityDismissCoverageTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val alice = testAccount("alice", "aa".repeat(32))
    private val bob = testAccount("bob", "bb".repeat(32))
    private var sends = 0
    private val backCallbacks = mutableListOf<OnBackInvokedCallback>()
    private lateinit var hostView: View

    /** The confirmation goes through the shared keyboard-safe owner, not a raw popup with its own dismissal. */
    @Test
    fun confirmationUsesTheKeyboardSafePopupAsItsOnlyDismissalAndBackOwner() {
        val body = sendingIdentitySource().functionBody("ComposerSendingIdentity")

        assertTrue("the confirmation must use the shared consuming scrim", "KeyboardSafePopup(" in body)
        val competingOwners =
            listOf("\\bPopup\\(", "dismissOnClickOutside", "\\bBackHandler\\(", "ComposerPlatformPreImeBackHandler\\(")
        competingOwners.forEach { owner ->
            assertFalse(
                "the shared popup must be the only dismissal/Back owner, found $owner",
                Regex(owner).containsMatchIn(body),
            )
        }
        assertTrue(
            "the test/host registrar must reach the popup's single Back registration",
            "backRegistration = backRegistration" in body,
        )
        assertTrue("the popup must anchor to the avatar slot", "modifier = Modifier.matchParentSize()" in body)
    }

    /** A second physical tap on the avatar closes the confirmation for good; a third, separate tap reopens it. */
    @Test
    fun repeatedAvatarTapClosesOnceThenReopens() {
        render()
        val avatar = centerOf("composer-sending-identity")

        tapAt(avatar)
        popup().assertIsDisplayed()

        tapAt(avatar)
        popup().assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(FRAMES_AFTER_DISMISS_MS)
        popup().assertDoesNotExist()

        tapAt(avatar)
        popup().assertIsDisplayed()
        tapAt(avatar)
        popup().assertDoesNotExist()
        tapAt(avatar)
        popup().assertIsDisplayed()
    }

    /** A dismissing press aimed at Send only closes the confirmation; the next, separate tap sends. */
    @Test
    fun outsideTapOnSendClosesOnlyTheConfirmation() {
        render()
        val send = centerOf(composeRule.onNodeWithContentDescription("Send"))

        tapAt(centerOf("composer-sending-identity"))
        popup().assertIsDisplayed()
        tapAt(send)
        popup().assertDoesNotExist()
        assertEquals("the dismissing press must not reach Send beneath it", 0, sends)

        tapAt(send)
        assertEquals(1, sends)
        popup().assertDoesNotExist()
    }

    /** Tapping the confirmation's own content does not dismiss it, and the draft stays focused throughout. */
    @Test
    fun popupContentTapKeepsItOpenAndDismissalKeepsTheDraft() {
        render()
        composeRule.onNodeWithText(DRAFT).assertIsFocused()

        tapAt(centerOf("composer-sending-identity"))
        tapAt(centerOf("composer-sending-identity-popup"))
        popup().assertIsDisplayed()
        composeRule.onNodeWithText(DRAFT).assertIsFocused()

        tapAt(centerOf("composer-sending-identity"))
        popup().assertDoesNotExist()
        composeRule.onNodeWithText(DRAFT).assertIsDisplayed().assertIsFocused()
    }

    /**
     * While open, the popup holds the only overlay Back callback — the focused composer stands its own down —
     * and invoking it closes the popup, after which the composer's callback returns.
     */
    @Test
    fun backClosesTheConfirmationThroughOneOverlayCallback() {
        render()
        val composerCallbacks = backCallbacks.toList()

        tapAt(centerOf("composer-sending-identity"))
        popup().assertIsDisplayed()
        assertEquals("exactly one overlay Back owner while open", 1, backCallbacks.size)
        assertFalse("the composer's own Back must stand down", backCallbacks.single() in composerCallbacks)

        composeRule.runOnIdle { backCallbacks.last().onBackInvoked() }
        popup().assertDoesNotExist()
        assertEquals(composerCallbacks.size, backCallbacks.size)
        composeRule.onNodeWithText(DRAFT).assertIsDisplayed()
    }

    /**
     * With the keyboard up, the composer's pre-IME Back interceptor must let a Back key pass while the
     * confirmation is open, so Back closes only the confirmation and the draft keeps focus; once it is
     * closed, the interceptor owns Back again and dismisses the keyboard as before.
     */
    @Test
    fun preImeBackWhileOpenLeavesTheKeyboardAndClosesOnlyTheConfirmation() {
        render()
        tapAt(centerOf("composer-sending-identity"))
        popup().assertIsDisplayed()

        assertFalse("the composer must not consume Back while the confirmation is open", preImeBack())
        composeRule.onNodeWithText(DRAFT).assertIsFocused()
        composeRule.runOnIdle { backCallbacks.last().onBackInvoked() }
        popup().assertDoesNotExist()
        composeRule.onNodeWithText(DRAFT).assertIsFocused()

        assertTrue("the composer owns Back again once the confirmation is closed", preImeBack())
        composeRule.onNodeWithText(DRAFT).assertIsNotFocused()
    }

    /** The open state is reported on open and close, and a key change while open reports it closed. */
    @Test
    fun showingChangeTracksOpenCloseAndDisposal() {
        val reports = mutableListOf<Boolean>()
        var conversation by mutableStateOf<Any>("first")
        val state = emptyAppState(accounts = listOf(alice, bob), activeAccountRef = "bob")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface { ComposerSendingIdentity(state, "alice", conversation) { reports += it } }
            }
        }
        assertEquals(listOf(false), reports.collapsed())

        tapAt(centerOf("composer-sending-identity"))
        assertEquals(true, reports.last())
        tapAt(centerOf("composer-sending-identity"))
        assertEquals(false, reports.last())

        tapAt(centerOf("composer-sending-identity"))
        assertEquals(true, reports.last())
        composeRule.runOnIdle { conversation = "second" }
        composeRule.waitForIdle()
        assertEquals(false, reports.last())
        popup().assertDoesNotExist()
        assertEquals(listOf(false, true, false, true, false), reports.collapsed())
    }

    /** Before Android 13 the popup's BackHandler owns Back, and the activity dispatcher closes it first. */
    @Test
    @Config(sdk = [32])
    fun preTiramisuBackClosesTheConfirmation() {
        render()
        tapAt(centerOf("composer-sending-identity"))
        popup().assertIsDisplayed()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        popup().assertDoesNotExist()
        composeRule.onNodeWithText(DRAFT).assertIsDisplayed()
    }

    /** The popup sits above the avatar slot, aligned to its start edge in both layout directions. */
    @Test
    fun positionProviderKeepsTheConfirmationAboveTheAvatarStartEdge() {
        val provider = SendingIdentityPopupPositionProvider(liftPx = 48)
        val anchor = IntRect(left = 100, top = 600, right = 148, bottom = 648)
        val popup = IntSize(160, 80)

        val ltr = provider.calculatePosition(anchor, IntSize(360, 780), LayoutDirection.Ltr, popup)
        val rtl = provider.calculatePosition(anchor, IntSize(360, 780), LayoutDirection.Rtl, popup)

        assertEquals(100, ltr.x)
        assertEquals(648 - 80 - 48, ltr.y)
        assertEquals(148 - 160, rtl.x)
        assertEquals(ltr.y, rtl.y)
    }

    /** Composes a two-account composer bound to alice that counts its sends. */
    private fun render() {
        val state = emptyAppState(accounts = listOf(alice, bob), activeAccountRef = "bob")
        val registrar =
            ComposerOverlayBackRegistrar { _, callback ->
                backCallbacks.add(callback)
                val unregister: () -> Unit = { backCallbacks.remove(callback) }
                unregister
            }
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(Modifier.width(360.dp)) {
                        Column {
                            Text("images.example/linked-image.jpg")
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> sends++ },
                                initialDraft = TextFieldValue(DRAFT),
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
        // Focus the draft with a real tap so focus is held the way a user would hold it.
        tapAt(centerOf(composeRule.onNodeWithText(DRAFT)))
    }

    /** Sends a Back down/up pair through the host view's pre-IME path; true when the composer consumed it. */
    private fun preImeBack(): Boolean {
        var consumed = false
        composeRule.runOnUiThread {
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                consumed = hostView.dispatchKeyEventPreIme(KeyEvent(action, KeyEvent.KEYCODE_BACK)) || consumed
            }
        }
        composeRule.waitForIdle()
        return consumed
    }

    /** The values with consecutive repeats collapsed, since an effect may restate an unchanged state. */
    private fun List<Boolean>.collapsed(): List<Boolean> = filterIndexed { i, v -> i == 0 || this[i - 1] != v }

    /** The confirmation popup's content node. */
    private fun popup() = composeRule.onNodeWithTag("composer-sending-identity-popup")

    /** Screen-space centre of the node tagged [tag]. */
    private fun centerOf(tag: String): Offset = centerOf(composeRule.onNodeWithTag(tag))

    /** Screen-space centre of [node]. */
    private fun centerOf(node: SemanticsNodeInteraction): Offset =
        node.fetchSemanticsNode().let { it.positionOnScreen + Offset(it.size.width / 2f, it.size.height / 2f) }

    /** Injects a down/up pair at [point] through the window dispatcher, then lets Compose settle. */
    private fun tapAt(point: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + TAP_DURATION_MS)
            .forEach { (action, eventTime) ->
                val event = MotionEvent.obtain(downTime, eventTime, action, point.x, point.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("window dispatch must accept the injected touch", automation.injectInputEvent(event, true))
                event.recycle()
            }
        composeRule.waitForIdle()
    }

    /** Production source of the sending-identity composable, from the module or repository root. */
    private fun sendingIdentitySource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ComposerSendingIdentity.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ComposerSendingIdentity.kt"),
        ).first { it.exists() }.readText()

    private companion object {
        const val DRAFT = "I’ll bring the route map."
        const val TAP_DURATION_MS = 40L
        const val FRAMES_AFTER_DISMISS_MS = 500L
    }
}
