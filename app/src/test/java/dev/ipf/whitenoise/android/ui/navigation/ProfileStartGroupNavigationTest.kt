package dev.ipf.whitenoise.android.ui.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val fixture = renderProfileHandoff(conversationController = null, ownerSurface = SHELL_SURFACE)

        assertOwnerSurfaceVisible(fixture)
        assertProfileActionVisible(fixture, scrollToAction = false)
        startGroupFromProfile(fixture)

        assertProfileOverlayAbsent(fixture)
        assertOwnerSurfaceAbsent(fixture)
        assertSelectedMemberPicker(fixture)

        closePicker()

        assertNoProfileOrPickerOverlay(fixture)
    }

    @Test
    fun conversationProfileHandoffRemovesAdminSheetAndBackClearsOverlays() {
        val fixture = profileFixture(CONVERSATION_SURFACE)
        val controller = conversationController(fixture.appState)
        renderProfileHandoff(fixture, controller)

        assertOwnerSurfaceVisible(fixture)
        assertProfileActionVisible(fixture, scrollToAction = true)
        composeRule.onNodeWithText(app.getString(R.string.make_admin)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.remove_member)).assertExists()
        startGroupFromProfile(fixture)

        assertProfileOverlayAbsent(fixture)
        assertOwnerSurfaceAbsent(fixture)
        composeRule.onNodeWithText(app.getString(R.string.make_admin)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.remove_member)).assertDoesNotExist()
        assertSelectedMemberPicker(fixture)

        closePicker()

        assertNoProfileOrPickerOverlay(fixture)
    }

    private fun renderProfileHandoff(
        conversationController: ConversationController?,
        ownerSurface: String,
    ): HandoffFixture {
        val fixture = profileFixture(ownerSurface)
        renderProfileHandoff(fixture, conversationController)
        return fixture
    }

    private fun renderProfileHandoff(
        fixture: HandoffFixture,
        conversationController: ConversationController?,
    ) {
        fixture.appState.presentProfile(TARGET_NPROFILE)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileGroupForegroundCoordinator(
                    appState = fixture.appState,
                    conversationController = conversationController,
                    secureWindowEnabled = null,
                    profileSecurePolicy = SecureFlagPolicy.Inherit,
                    onOpenConversation = { _, _ -> },
                    onDismissProfile = fixture.appState::clearPresentedProfile,
                    onClosePicker = {},
                ) {
                    Text(fixture.ownerSurface)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun profileFixture(ownerSurface: String): HandoffFixture {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { reference -> reference.takeIf { it == TARGET_NPROFILE }?.let { TARGET_HEX } },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACTIVE_ACCOUNT_REF,
            )
        val targetLabel = appState.displayName(TARGET_HEX)
        return HandoffFixture(
            appState = appState,
            candidate =
                viewedCandidate().copy(
                    displayName = targetLabel,
                    npub = TARGET_NPROFILE,
                ),
            targetLabel = targetLabel,
            ownerSurface = ownerSurface,
        )
    }

    private fun conversationController(appState: WhiteNoiseAppState): ConversationController =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot =
                GroupMemberSnapshot(
                    listOf(
                        member(ACTIVE_ACCOUNT_HEX, local = true),
                        member(TARGET_HEX, local = false),
                    ),
                ),
        )

    private fun assertProfileActionVisible(
        fixture: HandoffFixture,
        scrollToAction: Boolean,
    ) {
        val action =
            composeRule.onNodeWithText(
                app.getString(R.string.profile_start_new_group_with, fixture.candidate.displayName),
            )
        if (scrollToAction) action.performScrollTo()
        action.assertIsDisplayed().assertIsEnabled()
    }

    private fun startGroupFromProfile(fixture: HandoffFixture) {
        composeRule
            .onNodeWithText(app.getString(R.string.profile_start_new_group_with, fixture.candidate.displayName))
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
    }

    private fun assertSelectedMemberPicker(fixture: HandoffFixture) {
        composeRule.onNodeWithText(app.getString(R.string.new_group)).assertIsDisplayed()
        composeRule
            .onAllNodesWithText(app.resources.getQuantityString(R.plurals.selected_members_count, 1, 1))
            .onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(fixture.targetLabel).onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).assertIsEnabled()
    }

    private fun assertProfileOverlayAbsent(fixture: HandoffFixture) {
        composeRule
            .onNodeWithText(app.getString(R.string.profile_start_new_group_with, fixture.candidate.displayName))
            .assertDoesNotExist()
    }

    private fun assertOwnerSurfaceVisible(fixture: HandoffFixture) {
        composeRule.onNodeWithText(fixture.ownerSurface).assertIsDisplayed()
    }

    private fun assertOwnerSurfaceAbsent(fixture: HandoffFixture) {
        composeRule.onNodeWithText(fixture.ownerSurface).assertDoesNotExist()
    }

    private fun assertNoProfileOrPickerOverlay(fixture: HandoffFixture) {
        assertProfileOverlayAbsent(fixture)
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.selected_members_count, 1, 1))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()
        assertOwnerSurfaceVisible(fixture)
        composeRule.runOnIdle { assertNull(fixture.appState.pendingProfileNpub) }
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
        val appState: WhiteNoiseAppState,
        val candidate: RecipientSearch.Candidate,
        val targetLabel: String,
        val ownerSurface: String,
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

    private fun member(
        accountIdHex: String,
        local: Boolean,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = accountIdHex,
        account = if (local) ACTIVE_ACCOUNT_REF else null,
        local = local,
    )

    private fun group() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Test Group",
            description = "",
            admins = listOf(ACTIVE_ACCOUNT_HEX),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-group",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
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
        const val SHELL_SURFACE = "Chat list shell"
        const val CONVERSATION_SURFACE = "Active group conversation"
    }
}
