package dev.ipf.whitenoise.android.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.profile.ProfileStartGroupAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileStartGroupNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun shellProfileStartGroupPromotesPickerWithViewedMember() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = state.initialMember,
                conversationOpen = false,
            ),
        )
    }

    @Test
    fun conversationProfileStartGroupPromotesPickerAboveConversation() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = state.initialMember,
                conversationOpen = true,
            ),
        )
    }

    @Test
    fun closingPickerClearsShellForegroundRoute() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(null, state.initialMember, conversationOpen = false),
        )

        state.close()

        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(null, state.initialMember, conversationOpen = false),
        )
    }

    @Test
    fun closingPickerClearsConversationForegroundRoute() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(null, state.initialMember, conversationOpen = true),
        )

        state.close()

        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(null, state.initialMember, conversationOpen = true),
        )
    }

    @Test
    fun shellProfileHandoffShowsSelectedMemberAndBackClearsOverlays() {
        val fixture = renderProfileHandoff(conversationOpen = false)

        startGroupFromProfile(fixture)

        composeRule.onNodeWithText(PROFILE_SURFACE).assertDoesNotExist()
        composeRule.onNodeWithText(SHELL_SURFACE).assertDoesNotExist()
        assertSelectedMemberPicker(fixture)

        closePicker()

        composeRule.onNodeWithText(PROFILE_SURFACE).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.members_count, 1)).assertDoesNotExist()
        composeRule.onNodeWithText(SHELL_SURFACE).assertIsDisplayed()
    }

    @Test
    fun conversationProfileHandoffRemovesAdminSheetAndBackRestoresConversationOnly() {
        val fixture = renderProfileHandoff(conversationOpen = true)

        composeRule.onNodeWithText(ADMIN_CONTEXT).assertIsDisplayed()
        startGroupFromProfile(fixture)

        composeRule.onNodeWithText(PROFILE_SURFACE).assertDoesNotExist()
        composeRule.onNodeWithText(ADMIN_CONTEXT).assertDoesNotExist()
        composeRule.onNodeWithText(CONVERSATION_SURFACE).assertDoesNotExist()
        assertSelectedMemberPicker(fixture)

        closePicker()

        composeRule.onNodeWithText(PROFILE_SURFACE).assertDoesNotExist()
        composeRule.onNodeWithText(ADMIN_CONTEXT).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.members_count, 1)).assertDoesNotExist()
        composeRule.onNodeWithText(CONVERSATION_SURFACE).assertIsDisplayed()
    }

    private fun renderProfileHandoff(conversationOpen: Boolean): HandoffFixture {
        val candidate = viewedCandidate()
        val appState = WhiteNoiseAppState(app, DraftStore(EmptyDraftPersistence()))
        val targetLabel = appState.displayName(candidate.accountIdHex)
        composeRule.setContent {
            WhiteNoiseTheme {
                var profileOpen by remember { mutableStateOf(true) }
                val foregroundState = remember { ProfileGroupForegroundState() }
                val pickerOpen =
                    ProfileGroupForegroundFlow(
                        appState = appState,
                        initialMember = foregroundState.initialMember,
                        secureWindowEnabled = null,
                        onOpenConversation = { _, _ -> foregroundState.close() },
                        onClose = foregroundState::close,
                    )
                if (!pickerOpen) {
                    Text(if (conversationOpen) CONVERSATION_SURFACE else SHELL_SURFACE)
                    if (profileOpen) {
                        Column {
                            Text(PROFILE_SURFACE)
                            if (conversationOpen) Text(ADMIN_CONTEXT)
                            ProfileStartGroupAction(
                                candidate = candidate,
                                enabled = true,
                                onStartGroup = {
                                    profileOpen = false
                                    foregroundState.open(it)
                                },
                            )
                        }
                    }
                }
            }
        }
        return HandoffFixture(candidate = candidate, targetLabel = targetLabel)
    }

    private fun startGroupFromProfile(fixture: HandoffFixture) {
        composeRule
            .onNodeWithText(app.getString(R.string.profile_start_new_group_with, fixture.candidate.displayName))
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
    }

    private fun assertSelectedMemberPicker(fixture: HandoffFixture) {
        composeRule.onNodeWithText(app.getString(R.string.members_count, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(fixture.targetLabel).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).assertIsEnabled()
    }

    private fun closePicker() {
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        composeRule.waitForIdle()
    }

    private fun viewedCandidate() =
        RecipientSearch.Candidate(
            accountIdHex = "a".repeat(64),
            displayName = "Alice",
            npub = "npub1alice",
        )

    private data class HandoffFixture(
        val candidate: RecipientSearch.Candidate,
        val targetLabel: String,
    )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val PROFILE_SURFACE = "Profile modal"
        const val SHELL_SURFACE = "Chat list shell"
        const val CONVERSATION_SURFACE = "Active group conversation"
        const val ADMIN_CONTEXT = "Group admin profile context"
    }
}
