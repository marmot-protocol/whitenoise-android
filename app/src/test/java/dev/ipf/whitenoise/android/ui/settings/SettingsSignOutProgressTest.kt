package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Account teardown owns Settings until it finishes, even when callbacks arrive before a new frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsSignOutProgressTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A slow sign-out prevents leaving Settings or starting profile selection; normal navigation resumes afterward. */
    @Test
    fun signOutBlocksProfileAndBackUntilCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(account("alice", "a"), account("bob", "b")),
                activeAccountRef = "alice",
            )
        var backs = 0
        val details = mutableListOf<SettingsDetail?>()
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsScreen(
                    appState = appState,
                    onBackToChats = { backs++ },
                    onOpenDiagnostics = {},
                    onOpenSupportChat = {},
                    detail = null,
                    onDetailChange = { details += it },
                    homeViewport = SettingsHomeViewport.Top,
                    onHomeViewportChange = {},
                )
            }
        }
        composeRule.runOnIdle { appState.signOutInProgress = true }
        composeRule.onNodeWithTag("settings.sign_out_progress").assertExists()
        // Invoke semantics directly to cover queued callbacks as well as the modal's touch interception.
        composeRule.onNodeWithTag("settings.switch_profile").performClick()
        composeRule.onNodeWithTag("settings.active_profile").performClick()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(0, backs)
            assertEquals(emptyList<SettingsDetail?>(), details)
            assertEquals("alice", appState.activeAccount?.label)
        }
        composeRule.onNodeWithTag("settings.sign_out_progress").assertExists()
        composeRule.runOnIdle { appState.signOutInProgress = false }
        composeRule.onNodeWithTag("settings.sign_out_progress").assertDoesNotExist()
        composeRule.onNodeWithTag("settings.active_profile").performClick()
        composeRule.runOnIdle { assertEquals(listOf(SettingsDetail.ShareConnect), details) }
    }

    private fun account(
        label: String,
        hexDigit: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = hexDigit.repeat(64),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )
}
