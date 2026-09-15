package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.share.GROUP_A
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChat
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests real AppState-backed screen ownership, before recomposition can remove the old callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewMessageOwnershipTest {
    @get:Rule val composeRule = createComposeRule()

    /** Changing accounts invalidates an already captured native action in the same frame. */
    @Test fun accountChangeRejectsCapturedNavigation() = capturedAction("account")

    /** Sign-out blocks old actions before the screen is removed. */
    @Test fun signOutRejectsCapturedNavigation() = capturedAction("signOut")

    /** Wipe blocks old actions before runtime identity is replaced. */
    @Test fun wipeRejectsCapturedNavigation() = capturedAction("wipe")

    /** Runtime replacement invalidates callbacks even when the account label is unchanged. */
    @Test fun runtimeReplacementRejectsCapturedNavigation() = capturedAction("runtime")

    /** Screen disposal rejects a retained action even if the account remains active. */
    @Test fun disposalRejectsCapturedNavigation() = capturedAction("dispose")

    /** A new account cannot inherit the previous recipient's identifier query or resolved result. */
    @Test fun accountChangeResetsRecipientQuery() {
        val state = state()
        composeRule.setContent { WhiteNoiseTheme { NewMessageScreen(state, {}, {}, { _, _ -> }) } }
        composeRule.onNodeWithTag("new_message.searchField").performTextReplacement("b".repeat(64))
        composeRule.runOnIdle { state.replaceActiveAccountForTest("second") }
        val text =
            composeRule
                .onNodeWithTag("new_message.searchField")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
        assertEquals("", text)
    }

    /** An old camera session cannot close or fill the reopened scanner; current delivery consumes once. */
    @Test
    @Suppress("LongMethod") // Keep camera reopen, route reopen and stale callbacks in their required order.
    fun closeReopenRejectsOldScannerCallbacks() {
        val state = state()
        var dismiss: (() -> Unit)? = null
        var deliver: ((String) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                NewMessageScreen(state, {}, {}, { _, _ -> }, scannerContent = { close, scan ->
                    dismiss = close
                    deliver = scan
                })
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanLabel = context.getString(R.string.new_message_connect_qr)
        composeRule.onNodeWithTag("new_message.searchField").performTextReplacement("Alice")
        composeRule.onNodeWithText(scanLabel).performClick()
        composeRule.onNodeWithTag("share_connect.screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.show_my_qr_code)).assertDoesNotExist()
        val cameraLabel = context.getString(R.string.scan_qr_code)
        composeRule.onNodeWithText(cameraLabel).performClick()
        composeRule.waitForIdle()
        val oldDismiss = checkNotNull(dismiss)
        val oldDeliver = checkNotNull(deliver)
        composeRule.runOnIdle { oldDismiss() }
        composeRule.onNodeWithText(cameraLabel).performClick()
        composeRule.waitForIdle()
        val reopenedCameraDeliver = checkNotNull(deliver)
        composeRule.runOnIdle {
            oldDismiss()
            oldDeliver("npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq")
        }
        composeRule.onNodeWithTag("share_connect.screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals("Alice", query())
        composeRule.onNodeWithText(scanLabel).performClick()
        composeRule.onNodeWithText(cameraLabel).performClick()
        composeRule.waitForIdle()
        val currentDeliver = checkNotNull(deliver)
        composeRule.runOnIdle {
            reopenedCameraDeliver("npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq")
        }
        composeRule.onNodeWithTag("share_connect.screen").assertIsDisplayed()
        val currentNpub = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
        composeRule.runOnIdle {
            currentDeliver(currentNpub)
            currentDeliver("npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq")
        }
        assertEquals(currentNpub, query())
    }

    /** Back invalidates local intent synchronously, before a captured native person row can start creation. */
    @Test fun backThenSameFramePersonDoesNotStartNativeWork() = leaveThenPerson(back = true)

    /** Switching to group creation also consumes the old direct-chat intent before Compose changes steps. */
    @Test fun newGroupThenSameFramePersonDoesNotStartNativeWork() = leaveThenPerson(back = false)

    /** Both callbacks come from the actual AppState-backed screen and run in the same UI frame. */
    private fun leaveThenPerson(back: Boolean) {
        val state = appStateWithDirectChat(GROUP_A, PEER_A)
        var leaves = 0
        composeRule.setContent {
            WhiteNoiseTheme { NewMessageScreen(state, { leaves++ }, { leaves++ }, { _, _ -> }) }
        }
        val person =
            composeRule
                .onNodeWithTag("creation.person.$PEER_A")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val context = ApplicationProvider.getApplicationContext<Context>()
        val node =
            if (back) {
                composeRule.onNodeWithContentDescription(context.getString(R.string.back))
            } else {
                composeRule.onNodeWithText(context.getString(R.string.new_group))
            }
        val leave = node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeRule.runOnIdle {
            leave()
            person()
            assertEquals(1, leaves)
            assertFalse(state.hasActiveChatCreateOpenTiming())
        }
    }

    /** Reads the editable value rather than the field's placeholder or supporting semantics. */
    private fun query(): String =
        composeRule
            .onNodeWithTag("new_message.searchField")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** Captures the production Material action, mutates the actual owner and invokes before UI disablement. */
    private fun capturedAction(change: String) {
        val state = state()
        var navigations = 0
        val visible = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) NewMessageScreen(state, {}, { navigations++ }, { _, _ -> })
            }
        }
        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.new_group)
        val action = composeRule.onNodeWithText(label).captureClickCallbackForReplay()
        if (change == "dispose") {
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            composeRule.runOnIdle { action() }
        } else {
            composeRule.runOnIdle {
                when (change) {
                    "account" -> state.replaceActiveAccountForTest("second")
                    "signOut" -> state.signOutInProgress = true
                    "wipe" -> state.wipeInProgress = true
                    else -> {
                        val field = WhiteNoiseAppState::class.java.getDeclaredField("runtimeGeneration\$delegate")
                        field.isAccessible = true
                        (field.get(state) as MutableIntState).intValue++
                    }
                }
                action()
                visible.value = false
            }
        }
        assertEquals(0, navigations)
    }

    /** Existing fixture creates real AppState with only host-owned identities and no native search substitute. */
    private fun state() =
        emptyAppState(
            accounts = listOf(testAccount("first", "a".repeat(64)), testAccount("second", "c".repeat(64))),
            activeAccountRef = "first",
        ).also { state ->
            val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
            field.isAccessible = true
            (field.get(state) as BoundedNpubCache).put(
                "a".repeat(64),
                "npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq",
            )
        }
}
