package dev.ipf.whitenoise.android.ui.profile

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundCoordinator
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The profile sheet's Message quick action opens the existing 1:1 DM when there
 * is one and only creates otherwise. There is no conversation chooser: a DM is
 * always exactly one conversation, so nothing is left for the user to pick.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileSheetMessageActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun messageLooksForAnExistingDirectChatBeforeCreatingOne() {
        renderProfile()

        composeRule
            .onAllNodes(hasAnyAncestor(hasTestTag(PROFILE_MESSAGE_ACTION_TAG)) and hasClickAction())
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        // No chat list is bound, so the existing-DM lookup cannot answer and
        // fails closed on the read. A sheet that skipped the lookup and went
        // straight to create would report a create failure instead.
        composeRule.onNodeWithText(app.getString(R.string.couldnt_load_chats)).assertExists()
    }

    private fun renderProfile(): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { reference -> reference.takeIf { it == TARGET_NPROFILE }?.let { TARGET_HEX } },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACTIVE_ACCOUNT_REF,
            )
        appState.presentProfile(TARGET_NPROFILE)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileGroupForegroundCoordinator(
                    appState = appState,
                    conversationController = null,
                    secureWindowEnabled = null,
                    profileSecurePolicy = SecureFlagPolicy.Inherit,
                    onOpenConversation = { _, _ -> },
                    onDismissProfile = appState::clearPresentedProfile,
                    onClosePicker = {},
                ) {
                    Text(OWNER_SURFACE)
                }
            }
        }
        composeRule.waitForIdle()
        return appState
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACTIVE_ACCOUNT_REF,
            accountIdHex = ACTIVE_ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACTIVE_ACCOUNT_REF = "active"
        const val ACTIVE_ACCOUNT_HEX =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TARGET_HEX =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TARGET_NPROFILE = "nprofile-test-alice"
        const val OWNER_SURFACE = "Chat list shell"
    }
}
