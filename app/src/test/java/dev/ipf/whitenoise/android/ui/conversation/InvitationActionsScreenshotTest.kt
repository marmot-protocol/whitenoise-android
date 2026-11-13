package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.group
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

/** The two-account invite identifies its receiver and keeps both consent actions visible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class InvitationActionsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** The active account can lag a route bound to a different recipient. */
    @Test
    fun routedInviteUsesItsBoundAccountDuringAccountSwitch() {
        val active = testAccount("Personal", "a".repeat(64))
        val receiving = testAccount("Work", "b".repeat(64))
        val appState = emptyAppState(accounts = listOf(active, receiving), activeAccountRef = active.label)
        val controller = ConversationController(appState, group("invite"), accountRefOverride = receiving.label)
        assertEquals(active.label, appState.activeAccountRef)
        assertEquals(
            InviteReceivingAccount(receiving.accountIdHex, "Work", null),
            inviteReceivingAccount(appState, controller),
        )
    }

    /** A signed-out profile does not make the receiving account ambiguous. */
    @Test
    fun oneSignedInAccountHasNoReceivingAccountRow() {
        val active = testAccount("Personal", "a".repeat(64))
        val signedOut = testAccount("Work", "b".repeat(64)).copy(signedOut = true)
        val appState = emptyAppState(accounts = listOf(active, signedOut), activeAccountRef = active.label)
        val controller = ConversationController(appState, group("invite"))
        assertNull(inviteReceivingAccount(appState, controller))
    }

    /** Preserves the existing pane when there is one signing identity. */
    @Test
    fun singleAccountLight() = capture("single_account_light", null)

    /** Renders the receiving account's monogram and full label in dark theme. */
    @Test
    fun receivingAccountDark() =
        capture("receiving_account_dark", InviteReceivingAccount("work", "Work", null), dark = true)

    /** Long account labels keep both consent buttons visible in a narrow AMOLED pane. */
    @Test
    fun longReceivingAccountAmoled() =
        capture(
            "long_receiving_account_amoled",
            InviteReceivingAccount(
                "work",
                "Work account with a name much longer than the available notification pane",
                null,
            ),
            dark = true,
            amoled = true,
        )

    /** Captures the production pane at narrow width and checks its merged accessibility label. */
    private fun capture(
        name: String,
        account: InviteReceivingAccount?,
        dark: Boolean = false,
        amoled: Boolean = false,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Box(Modifier.width(320.dp)) {
                    InvitationActions(
                        inviterName = "Alice",
                        mutationInFlight = false,
                        onAccept = {},
                        onDecline = {},
                        modifier = Modifier.testTag("invite-actions"),
                        receivingAccount = account,
                    )
                }
            }
        }
        rule.onNodeWithText("Accept").assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        if (account == null) {
            rule.onAllNodesWithText("Invited as", substring = true).assertCountEquals(0)
        } else {
            rule.onAllNodesWithText("Invited as ${account.name}").assertCountEquals(1)
        }
        rule.onNodeWithTag("invite-actions").captureRoboImage("src/test/snapshots/invite_actions_$name.png")
    }
}
