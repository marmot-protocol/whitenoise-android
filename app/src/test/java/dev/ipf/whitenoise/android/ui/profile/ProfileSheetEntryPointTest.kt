package dev.ipf.whitenoise.android.ui.profile

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundCoordinator
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Entry-point-dependent chrome on the profile sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileSheetEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun unknownFollowStateLeavesTheQuickActionDisabled() {
        renderProfile { it.presentProfile(TARGET_NPROFILE) }

        // No engine is attached, so the follow read fails and the state stays
        // unknown — the control must be dead rather than offering a guess.
        composeRule
            .onAllNodes(hasAnyAncestor(hasTestTag(PROFILE_FOLLOW_ACTION_TAG)) and hasClickAction())
            .onFirst()
            .assertIsNotEnabled()
    }

    @Test
    fun followSitsInTheQuickActionRowBeforeMessage() {
        renderProfile { it.presentProfile(TARGET_NPROFILE) }

        // Semantics traversal order is composition order, unlike laid-out bounds,
        // which read as zero until the sheet has finished animating in.
        val ordered =
            composeRule
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(PROFILE_QUICK_ACTIONS_TAG)) and
                        (hasTestTag(PROFILE_FOLLOW_ACTION_TAG) or hasTestTag(PROFILE_MESSAGE_ACTION_TAG)),
                ).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag] }

        assertEquals(listOf(PROFILE_FOLLOW_ACTION_TAG, PROFILE_MESSAGE_ACTION_TAG), ordered)
    }

    private fun renderProfile(present: (WhiteNoiseAppState) -> Unit): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { reference -> reference.takeIf { it == TARGET_NPROFILE }?.let { TARGET_HEX } },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACTIVE_ACCOUNT_REF,
            )
        present(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileGroupForegroundCoordinator(
                    appState = appState,
                    conversationController = null,
                    profileGroupForegroundState = ProfileGroupForegroundState(),
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
