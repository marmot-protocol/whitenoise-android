package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for interactive account switches that publish local rows before presentation hydration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AccountSwitchRowsOnlyFirstFrameScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rowsOnlySnapshotShowsDeterministicFallbacks() {
        render(rowsOnlyItems(), SCREENSHOT_TAG_ROWS_ONLY)

        composeRule.onAllNodesWithText("Unknown").assertCountEquals(2)
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG_ROWS_ONLY)
            .captureRoboImage("src/test/snapshots/account_switch_rows_only_first_frame_light.png")
    }

    @Test
    fun hydratedSnapshotShowsResolvedDmAndGroupTitles() =
        runTest {
            val appState = appState()
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))
            render(hydratedItems(), SCREENSHOT_TAG_HYDRATED, appState)

            composeRule.onNodeWithText(PEER_NAME).assertIsDisplayed()
            composeRule.onNodeWithText("Group of 3 people").assertIsDisplayed()
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG_HYDRATED)
                .captureRoboImage("src/test/snapshots/account_switch_rows_only_hydrated_light.png")
        }

    private fun render(
        items: List<ChatListItem>,
        screenshotTag: String,
        appState: WhiteNoiseAppState = appState(),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().testTag(screenshotTag)) {
                        items.forEach { item ->
                            ChatRow(
                                item = item,
                                appState = appState,
                                interactionsEnabled = false,
                                onClick = {},
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun rowsOnlyItems() =
        listOf(
            chatItem(DM_GROUP_ID),
            chatItem(GROUP_ID),
        )

    private fun hydratedItems() =
        listOf(
            chatItem(
                groupId = DM_GROUP_ID,
                otherMemberAccount = PEER_ID,
                memberCount = 2,
            ),
            chatItem(
                groupId = GROUP_ID,
                memberCount = 3,
            ),
        )

    private fun chatItem(
        groupId: String,
        otherMemberAccount: String? = null,
        memberCount: Int = 0,
    ) = ChatListItem(
        group = unnamedGroup(groupId),
        latest = null,
        otherMemberAccount = otherMemberAccount,
        memberCount = memberCount,
        memberSnapshot = null,
    )

    private fun appState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { peerProfile() },
            profileDisplayNameReader = { PEER_NAME },
            profileRefreshRequest = {},
        )
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun peerProfile() =
        UserProfileMetadataFfi(
            name = "alice",
            displayName = PEER_NAME,
            about = null,
            picture = null,
            nip05 = null,
            lud16 = null,
        )

    private fun unnamedGroup(groupId: String) =
        AppGroupRecordFfi(
            groupIdHex = groupId,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = false,
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = groupId,
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
                    allowedLocatorKinds = emptyList(),
                    defaultBlobEndpoints = emptyList(),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val SCREENSHOT_TAG_ROWS_ONLY = "account-switch-rows-only-first-frame"
        const val SCREENSHOT_TAG_HYDRATED = "account-switch-rows-only-hydrated"
        const val ACCOUNT_REF = "primary"
        const val PEER_NAME = "Alice"
        val ACCOUNT_ID = "11".repeat(32)
        val PEER_ID = "22".repeat(32)
        val DM_GROUP_ID = "33".repeat(32)
        val GROUP_ID = "44".repeat(32)
    }
}
