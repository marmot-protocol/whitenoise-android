package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
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

/** Actual AppState and Material callbacks exercise ownership before recomposition hides obsolete controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewGroupOwnershipTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Switching accounts cannot admit a captured selection action. */
    @Test fun accountSwitchRejectsCapturedSelection() = capturedSelection("account")

    /** Sign-out is live admission state rather than only a disabled visual. */
    @Test fun signOutRejectsCapturedSelection() = capturedSelection("signOut")

    /** Wipe invalidates selected-person actions in the same frame. */
    @Test fun wipeRejectsCapturedSelection() = capturedSelection("wipe")

    /** Replacing the native runtime invalidates otherwise identical account ownership. */
    @Test fun runtimeReplacementRejectsCapturedSelection() = capturedSelection("runtime")

    /** Continuing synchronously disposes picker intent before stale row callbacks can alter setup recipients. */
    @Test fun continueThenSameFrameSelectionCannotChangeMembers() = capturedSelection("continue")

    /** Back synchronously consumes old picker actions even before Compose removes the screen. */
    @Test fun backThenSameFrameSelectionCannotChangeMembers() = capturedSelection("back")

    /** Back from setup retains authored fields; member selection remains a temporary step. */
    @Test fun selectionSetupBackRetainsNameAndDescription() {
        val state = state()
        composeRule.setContent { WhiteNoiseTheme { NewGroupFlow(state, { _, _ -> }, {}) } }
        composeRule.onNodeWithTag("new_group.continue").performClick()
        composeRule.onNodeWithTag("group_setup.name").performTextReplacement("Team 😀")
        composeRule.onNodeWithTag("group_setup.description").performTextReplacement("Plans for Friday")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("new_group.continue").performClick()
        assertEquals("Team 😀", text("group_setup.name"))
        assertEquals("Plans for Friday", text("group_setup.description"))
    }

    /** Another account cannot inherit the first account's authored setup or selection. */
    @Test fun accountChangeDropsSetupDraft() {
        val state = state()
        composeRule.setContent { WhiteNoiseTheme { NewGroupFlow(state, { _, _ -> }, {}) } }
        composeRule.onNodeWithTag("new_group.continue").performClick()
        composeRule.onNodeWithTag("group_setup.name").performTextReplacement("Private team")
        composeRule.runOnIdle { state.replaceActiveAccountForTest("second") }
        composeRule.onNodeWithTag("new_group.continue").performClick()
        assertEquals("", text("group_setup.name"))
    }

    /** External submission callbacks may supersede the account synchronously; no native work may then start. */
    @Test fun submissionCallbackAccountChangeBlocksNativeCreate() {
        val state = state()
        var submitted = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NewGroupSetupScreen(state, emptyList(), {}, { _, _ -> }, onCreateSubmitted = {
                    submitted++
                    state.replaceActiveAccountForTest("second")
                    9L
                })
            }
        }
        composeRule.onNodeWithTag("group_setup.name").performTextReplacement("Team")
        val action =
            composeRule
                .onNodeWithTag("group_setup.create")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            action()
            action()
        }
        assertEquals(1, submitted)
        assertFalse(state.hasActiveChatCreateOpenTiming())
    }

    /** Closing and reopening the camera never admits its previous session's result or dismiss callback. */
    @Test fun closeReopenScannerRejectsOldCallbacks() {
        val state = state()
        val selected = mutableStateListOf<RecipientSearch.Candidate>()
        var dismiss: (() -> Unit)? = null
        var scan: ((String) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                NewGroupRecipientPickerScreen(state, selected, {}, {}, scannerContent = { close, deliver ->
                    dismiss = close
                    scan = deliver
                })
            }
        }
        val scanLabel = context.getString(R.string.scan_qr_code)
        composeRule.onNodeWithContentDescription(scanLabel).performClick()
        composeRule.waitForIdle()
        val oldClose = checkNotNull(dismiss)
        val oldScan = checkNotNull(scan)
        composeRule.runOnIdle { oldClose() }
        composeRule.onNodeWithContentDescription(scanLabel).performClick()
        composeRule.waitForIdle()
        val currentScan = checkNotNull(scan)
        val npub = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
        composeRule.runOnIdle {
            oldClose()
            oldScan(npub)
        }
        assertEquals("", text("new_group.search"))
        composeRule.runOnIdle {
            currentScan(npub)
            oldScan("ignored")
        }
        assertEquals(npub, text("new_group.search"))
        assertEquals(0, selected.size)
    }

    /** Captures the actual native person row, changes the live owner, then invokes within the same UI frame. */
    private fun capturedSelection(change: String) {
        val state = appStateWithDirectChat(GROUP_A, PEER_A)
        val selected = mutableStateListOf<RecipientSearch.Candidate>()
        var leaves = 0
        composeRule.setContent {
            WhiteNoiseTheme { NewGroupRecipientPickerScreen(state, selected, { leaves++ }, { leaves++ }) }
        }
        val select =
            composeRule
                .onNodeWithTag("new_group.person.$PEER_A")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val leave =
            if (change == "back") {
                composeRule.onNodeWithContentDescription(context.getString(R.string.back))
            } else {
                composeRule.onNodeWithTag("new_group.continue")
            }
        val leaveAction = leave.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeRule.runOnIdle {
            when (change) {
                "account" -> state.replaceActiveAccountForTest("second")
                "signOut" -> state.signOutInProgress = true
                "wipe" -> state.wipeInProgress = true
                "runtime" -> {
                    val field = WhiteNoiseAppState::class.java.getDeclaredField("runtimeGeneration\$delegate")
                    field.isAccessible = true
                    (field.get(state) as MutableIntState).intValue++
                }
                else -> leaveAction()
            }
            select()
            assertEquals(0, selected.size)
        }
        assertEquals(if (change in listOf("continue", "back")) 1 else 0, leaves)
    }

    /** Reads editable value rather than a field label. */
    private fun text(tag: String): String =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** Existing host-only state fixture; no provider or native create is simulated. */
    private fun state() =
        emptyAppState(
            accounts = listOf(testAccount("first", "a".repeat(64)), testAccount("second", "c".repeat(64))),
            activeAccountRef = "first",
        )
}
