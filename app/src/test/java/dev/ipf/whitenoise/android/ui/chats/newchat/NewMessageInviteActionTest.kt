package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewMessageInviteActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun blankQueryShowsAccessibleInviteAlongsideExistingActionsAndLaunchesOnce() {
        val taps = mutableListOf<String>()
        render(query = "", taps = taps)

        listOf(R.string.new_group, R.string.invite_friends, R.string.scan_qr_code, R.string.show_my_qr_code)
            .forEach { label -> composeRule.onNodeWithText(context.getString(label)).assertIsDisplayed() }
        composeRule
            .onNodeWithText(context.getString(R.string.invite_friends))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertEquals(listOf("invite"), taps)
    }

    @Test
    fun activeRecipientQueryHidesEveryQuickActionWithoutInvokingCallbacks() {
        val taps = mutableListOf<String>()
        render(query = "alice", taps = taps)

        composeRule.onNodeWithText(context.getString(R.string.invite_friends)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.new_group)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.scan_qr_code)).assertDoesNotExist()
        assertEquals(emptyList<String>(), taps)
    }

    private fun render(
        query: String,
        taps: MutableList<String>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    NewMessageQuickActions(
                        query = query,
                        showMyQrLabel = context.getString(R.string.show_my_qr_code),
                        showMyQrEnabled = true,
                        onNewGroup = { taps += "group" },
                        onScanQr = { taps += "scan" },
                        onShowMyQr = { taps += "show" },
                        onInviteFriends = { taps += "invite" },
                    )
                }
            }
        }
    }
}
