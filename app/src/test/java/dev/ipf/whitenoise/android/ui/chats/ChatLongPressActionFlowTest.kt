package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatLongPressActionFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun longPressOpensActionsAndSelectDismissesIntoExistingSelectionMode() {
        var sheetOpen by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListRow(
                    item = chatItem(),
                    appState = appState(),
                    isMuted = false,
                    selectionMode = selected,
                    selected = selected,
                    onOpen = { opens++ },
                    onOpenProfile = {},
                    onOpenActions = { sheetOpen = true },
                    onDragSelectionStart = {},
                    onDragSelection = { false },
                    onDragSelectionEnd = {},
                    onDragSelectionCancel = {},
                    rangeDragActive = false,
                    onToggleSelection = { selected = !selected },
                )
                if (sheetOpen) {
                    ChatActionSheet(
                        hasUnread = false,
                        canMarkUnread = true,
                        archived = false,
                        muted = false,
                        onMarkRead = {},
                        onMarkUnread = {},
                        onAddToFolder = {},
                        onArchiveToggle = {},
                        onMuteToggle = {},
                        onSelect = { selected = true },
                        onDelete = {},
                        onDismiss = { sheetOpen = false },
                    )
                }
            }
        }

        composeRule.onNodeWithText(GROUP_NAME).performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.select)).assertIsDisplayed().performClick()

        composeRule.onNodeWithText(string(R.string.select)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(true, selected)
            assertEquals(0, opens)
        }
    }

    @Test
    fun deleteDismissesTheSheetAndRequiresDestructiveConfirmation() {
        var sheetOpen by mutableStateOf(true)
        var confirmationOpen by mutableStateOf(false)
        var confirmedDeletes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                if (sheetOpen) {
                    ChatActionSheet(
                        hasUnread = false,
                        canMarkUnread = true,
                        archived = false,
                        muted = false,
                        onMarkRead = {},
                        onMarkUnread = {},
                        onAddToFolder = {},
                        onArchiveToggle = {},
                        onMuteToggle = {},
                        onSelect = {},
                        onDelete = { confirmationOpen = true },
                        onDismiss = { sheetOpen = false },
                    )
                }
                if (confirmationOpen) {
                    ChatDeleteConfirmationDialog(
                        count = 1,
                        onConfirm = {
                            confirmationOpen = false
                            confirmedDeletes++
                        },
                        onDismiss = { confirmationOpen = false },
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        composeRule.onNodeWithText(string(R.string.chat_list_action_add_to_folder)).assertDoesNotExist()
        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(
                    R.plurals.chat_list_bulk_delete_confirm,
                    1,
                    1,
                ),
            ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmedDeletes) }

        composeRule
            .onNode(hasText(string(R.string.delete_group_confirm)) and hasClickAction())
            .performClick()
        composeRule.runOnIdle { assertEquals(1, confirmedDeletes) }
    }

    private fun string(res: Int): String = context.getString(res)

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "personal",
                        accountIdHex = "01".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "personal",
        )

    private fun chatItem() =
        ChatListItem(
            group =
                AppGroupRecordFfi(
                    groupIdHex = "02".repeat(32),
                    protocolProfile = AppProtocolProfileFfi.LEGACY,
                    endpoint = "wss://relay.example",
                    profilePresent = true,
                    name = GROUP_NAME,
                    description = "",
                    admins = emptyList(),
                    relays = emptyList(),
                    nostrGroupIdHex = "03".repeat(32),
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
                                        baseUrl = "https://blossom.example",
                                    ),
                                ),
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
                ),
            latest = null,
            otherMemberAccount = null,
            memberCount = 3,
            memberSnapshot = null,
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val GROUP_NAME = "Gesture test group"
    }
}
