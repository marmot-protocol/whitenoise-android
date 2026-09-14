package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual account-scoped FAB loader and native recovery destination; no fake readiness counter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsNewMessageFabTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = chatOrganizationAppState(context)
    private var opens = 0

    /** Configured keeps the existing new message callback and performance tag. */
    @Test fun configuredKeepsTheExistingNewMessageCallbackAndPerformanceTag() {
        show { chatOrganizationRelays(configured = true) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.new_message)).assertIsDisplayed()
        fab().performClick()
        assertEquals(1, opens)
        if (BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) {
            composeRule.onNodeWithTag(PerformanceTestTags.NEW_MESSAGE).assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag(PerformanceTestTags.NEW_MESSAGE).assertDoesNotExist()
        }
    }

    /** Failed read does not invent a missing relay gate. */
    @Test fun failedReadDoesNotInventAMissingRelayGate() {
        show { error("local native fixture failure") }
        fab().performClick()
        assertEquals(1, opens)
    }

    /** Known missing opens actual relays only after explicit tap. */
    @Test fun knownMissingOpensActualRelaysOnlyAfterExplicitTap() {
        show { chatOrganizationRelays(configured = false) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.chats_check_relays)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.relays)).assertDoesNotExist()
        assertEquals(0, opens)
        fab().performClick()
        composeRule.onNodeWithText(context.getString(R.string.relays)).assertIsDisplayed()
        assertEquals(0, opens)
    }

    /** Old account result cannot change the new accounts fab. */
    @Test fun oldAccountResultCannotChangeTheNewAccountsFab() {
        val held = CompletableDeferred<AccountRelayListsFfi?>()
        show { if (it == "alice") held.await() else awaitCancellation() }
        composeRule.runOnIdle { replaceChatOrganizationAccount(app, "bob") }
        held.complete(chatOrganizationRelays(configured = false))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.new_message)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chats_check_relays)).assertDoesNotExist()
    }

    /** Captured callback cannot open after same frame account replacement. */
    @Test fun capturedCallbackCannotOpenAfterSameFrameAccountReplacement() {
        show { null }
        val action =
            fab()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            replaceChatOrganizationAccount(app, "bob")
            action()
        }
        assertEquals(0, opens)
    }

    /** Captured callback cannot open during wipe. */
    @Test fun capturedCallbackCannotOpenDuringWipe() {
        show { null }
        val action =
            fab()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            app.wipeInProgress = true
            action()
        }
        assertEquals(0, opens)
    }

    /** Unavailable unsafe and missing publication follow actual inbox evidence. */
    @Test fun unavailableUnsafeAndMissingPublicationFollowActualInboxEvidence() {
        assertFalse(chatsFabNeedsRelays(null))
        assertFalse(chatsFabNeedsRelays(chatOrganizationRelays(true)))
        assertTrue(chatsFabNeedsRelays(chatOrganizationRelays(false)))
        assertTrue(
            chatsFabNeedsRelays(
                chatOrganizationRelays(true).copy(missing = listOf(MissingRelayListKindFfi.INBOX)),
            ),
        )
        assertTrue(
            chatsFabNeedsRelays(
                chatOrganizationRelays(true).copy(
                    inbox = RelayListFfi(10_050uL, listOf("ws://unsafe.example.com")),
                ),
            ),
        )
    }

    /** The FAB node under test. */
    private fun fab() =
        composeRule.onNode(
            hasClickAction() and (
                hasContentDescription(context.getString(R.string.new_message)) or
                    hasContentDescription(context.getString(R.string.chats_check_relays))
            ),
        )

    /** Shows the surface under test. */
    private fun show(load: suspend (String) -> AccountRelayListsFfi?) {
        composeRule.setContent { WhiteNoiseTheme { ChatsNewMessageFab(app, { opens++ }, load) } }
    }
}

/** Only public synthetic relay lists are represented in these local fixtures. */
internal fun chatOrganizationRelays(configured: Boolean): AccountRelayListsFfi =
    AccountRelayListsFfi(
        complete = configured,
        missing = if (configured) emptyList() else listOf(MissingRelayListKindFfi.INBOX),
        defaultRelays = emptyList(),
        bootstrapRelays = emptyList(),
        nip65 = RelayListFfi(kind = 10_002uL, relays = listOf("wss://posting.example.com")),
        inbox =
            RelayListFfi(
                kind = 10_050uL,
                relays = if (configured) listOf("wss://inbox.example.com") else emptyList(),
            ),
    )
