package dev.ipf.whitenoise.android.ui.profile

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ProfileAddableGroupsLoadState
import dev.ipf.whitenoise.android.state.ProfileAddableGroupsState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundCoordinator
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileAddToGroupsFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun addToGroupsAndBackReuseOneModalHost() {
        renderProfile()
        val hostDialog = latestComponentDialog()

        composeRule
            .onNodeWithText(app.getString(R.string.profile_add_to_another_group))
            .performScrollTo()
            .performClick()
        finishContentTransition()

        assertSame(hostDialog, latestComponentDialog())
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertExists()

        composeRule.runOnUiThread {
            hostDialog.onBackPressedDispatcher.onBackPressed()
        }
        finishContentTransition()

        assertSame(hostDialog, latestComponentDialog())
        composeRule.onNodeWithTag(PROFILE_SHEET_CONTENT_TAG).assertExists()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertDoesNotExist()
    }

    @Test
    fun unresolvedGroupsShowLoadingInsteadOfTerminalEmptyCopy() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsContent(
                    appState = appState,
                    targetName = "Alice",
                    state =
                        ProfileAddableGroupsState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("pending"),
                            loadState = ProfileAddableGroupsLoadState.LOADING,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_addable_groups_loading)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.profile_no_addable_groups)).assertDoesNotExist()
    }

    @Test
    fun failedGroupCheckShowsRetryAction() {
        val appState = testAppState()
        var retryCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsContent(
                    appState = appState,
                    targetName = "Alice",
                    state =
                        ProfileAddableGroupsState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("failed"),
                            loadState = ProfileAddableGroupsLoadState.FAILED,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = { retryCount += 1 },
                    onAdd = {},
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_addable_groups_failed)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, retryCount) }
    }

    private fun renderProfile() {
        val appState = testAppState()
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
                    Text("Chat list shell")
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun testAppState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { reference -> reference.takeIf { it == TARGET_NPROFILE }?.let { TARGET_HEX } },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACTIVE_ACCOUNT_REF,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACTIVE_ACCOUNT_REF,
            accountIdHex = ACTIVE_ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun latestComponentDialog(): ComponentDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("Profile flow must own a ComponentDialog", dialog is ComponentDialog)
        return dialog as ComponentDialog
    }

    private fun finishContentTransition() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

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
    }
}
