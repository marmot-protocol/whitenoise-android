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
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundCoordinator
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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
                        ProfileGroupPickerState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("pending"),
                            loadState = ProfileGroupPickerLoadState.LOADING,
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
                        ProfileGroupPickerState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("failed"),
                            loadState = ProfileGroupPickerLoadState.FAILED,
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
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun makeAdminPickerReturnsTheSelectedSharedGroup() {
        val appState = testAppState()
        val group = groupItem("friends", "Friends")
        var promotedGroupId: String? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileMakeAdminContent(
                    appState = appState,
                    targetName = "Alice",
                    state =
                        ProfileGroupPickerState(
                            groups = listOf(group),
                            pendingGroupIds = emptySet(),
                            loadState = ProfileGroupPickerLoadState.READY,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onPromote = { promotedGroupId = it.group.groupIdHex },
                )
            }
        }

        composeRule.onNodeWithText("Friends").performClick()
        composeRule.onNodeWithText(app.getString(R.string.make_admin)).performClick()
        composeRule.runOnIdle { assertEquals("friends", promotedGroupId) }
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

    private fun groupItem(
        groupIdHex: String,
        name: String,
    ) = ChatListItem(
        group =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = groupIdHex,
                protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
                profilePresent = false,
                endpoint = "endpoint",
                name = name,
                description = "",
                admins = listOf(ACTIVE_ACCOUNT_HEX),
                relays = listOf("wss://relay.example"),
                nostrGroupIdHex = "nostr-$groupIdHex",
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
                encryptedMedia =
                    AppGroupEncryptedMediaComponentFfi(
                        componentId = 0x8008u,
                        component = "marmot.group.encrypted-media.v1",
                        required = true,
                        version = EncryptedMediaVersionFfi.V1,
                        mediaFormat = "encrypted-media-v1",
                        allowedLocatorKinds = listOf("blossom-v1"),
                        defaultBlobEndpoints =
                            listOf(
                                AppBlobEndpointFfi(
                                    locatorKind = "blossom-v1",
                                    baseUrl = "https://blossom.primal.net",
                                ),
                            ),
                    ),
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
            ),
        latest = null,
        otherMemberAccount = null,
        memberCount = 2,
        memberSnapshot = null,
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
