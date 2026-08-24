package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
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
        val item = chatItem()
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListRow(
                    item = item,
                    appState = state,
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
                        pinned = false,
                        showPinToggle = true,
                        showMovePinnedUp = false,
                        showMovePinnedDown = false,
                        onMarkRead = {},
                        onMarkUnread = {},
                        onAddToFolder = {},
                        onArchiveToggle = {},
                        onMuteToggle = {},
                        onPinToggle = {},
                        onMovePinned = {},
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
    @Suppress("LongMethod") // Full pointer lifecycle and visible sheet belong in one regression test.
    fun actionSheetOpensAtLongPressThresholdBeforePointerUp() {
        var sheetOpen by mutableStateOf(false)
        var actionOpens = 0
        var chatOpens = 0
        val item = chatItem()
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag(CHAT_HOLD_HOST_TAG)) {
                    ChatListRow(
                        item = item,
                        appState = state,
                        isMuted = false,
                        selectionMode = false,
                        selected = false,
                        onOpen = { chatOpens++ },
                        onOpenProfile = {},
                        onOpenActions = {
                            actionOpens++
                            sheetOpen = true
                        },
                        onDragSelectionStart = {},
                        onDragSelection = { false },
                        onDragSelectionEnd = {},
                        onDragSelectionCancel = {},
                        rangeDragActive = false,
                        onToggleSelection = {},
                    )
                }
                if (sheetOpen) {
                    ChatActionSheet(
                        hasUnread = false,
                        canMarkUnread = true,
                        archived = false,
                        muted = false,
                        pinned = false,
                        showPinToggle = true,
                        showMovePinnedUp = false,
                        showMovePinnedDown = false,
                        onMarkRead = {},
                        onMarkUnread = {},
                        onAddToFolder = {},
                        onArchiveToggle = {},
                        onMuteToggle = {},
                        onPinToggle = {},
                        onMovePinned = {},
                        onSelect = {},
                        onDelete = {},
                        onDismiss = { sheetOpen = false },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CHAT_HOLD_HOST_TAG).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(center)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.select)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, actionOpens)
            assertEquals(0, chatOpens)
        }

        composeRule.onAllNodes(isRoot())[0].performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, actionOpens)
            assertEquals(0, chatOpens)
        }
    }

    @Test
    fun productionRowsCancelHeldPointerAndActionsAcrossPlacementMotion() {
        var itemIds by mutableStateOf(listOf("A", "B"))
        val items =
            mapOf(
                "A" to chatItem(groupIdByte = "0a", name = "Gesture A"),
                "B" to chatItem(groupIdByte = "0b", name = "Gesture B"),
            )
        val openedIds = mutableListOf<String>()
        val actionIds = mutableListOf<String>()
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProductionChatListPlacementHarness(
                    itemIds = itemIds,
                    items = items,
                    appState = state,
                    onOpen = openedIds::add,
                    onOpenActions = actionIds::add,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(productionChatRowTag("A")).performTouchInput { down(center) }
        composeRule.runOnUiThread { itemIds = listOf("B", "A") }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodes(isRoot())[0].performTouchInput { up() }

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), openedIds)
            assertEquals(emptyList<String>(), actionIds)
        }
        composeRule.onNodeWithText("Gesture A").assertIsNotEnabled()
        composeRule
            .onNodeWithText("Gesture B")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick).not())

        composeRule.mainClock.advanceTimeBy(CHAT_LIST_ROW_PLACEMENT_MILLIS.toLong() + 1L)
        composeRule.runOnIdle { }
        composeRule.onNode(hasText("Gesture A") and hasClickAction()).performClick()
        composeRule.onNodeWithTag(productionChatRowTag("B")).performTouchInput { longClick() }

        composeRule.runOnIdle {
            assertEquals(listOf("A"), openedIds)
            assertEquals(listOf("B"), actionIds)
        }
    }

    @Test
    fun longPressDragDismissesThresholdActionAndEntersRange() {
        var rangeActive by mutableStateOf(false)
        var sheetOpen by mutableStateOf(false)
        var actionOpens = 0
        var dragStarts = 0
        var dragMoves = 0
        var dragEnds = 0
        val item = chatItem()
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag(CHAT_DRAG_HOST_TAG)) {
                    ChatListRow(
                        item = item,
                        appState = state,
                        isMuted = false,
                        selectionMode = rangeActive,
                        selected = rangeActive,
                        onOpen = {},
                        onOpenProfile = {},
                        onOpenActions = {
                            actionOpens++
                            sheetOpen = true
                        },
                        onDragSelectionStart = {
                            sheetOpen = false
                            rangeActive = true
                            dragStarts++
                        },
                        onDragSelection = {
                            dragMoves++
                            true
                        },
                        onDragSelectionEnd = { dragEnds++ },
                        onDragSelectionCancel = {},
                        rangeDragActive = rangeActive,
                        onToggleSelection = {},
                    )
                }
            }
        }

        val chatRow = composeRule.onNodeWithTag(CHAT_DRAG_HOST_TAG)
        chatRow.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.waitForIdle()
        chatRow.performTouchInput {
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 48f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, actionOpens)
        assertEquals(false, sheetOpen)
        assertEquals(1, dragStarts)
        assertTrue(dragMoves >= 2)
        assertEquals(1, dragEnds)
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
                        pinned = false,
                        showPinToggle = true,
                        showMovePinnedUp = false,
                        showMovePinnedDown = false,
                        onMarkRead = {},
                        onMarkUnread = {},
                        onAddToFolder = {},
                        onArchiveToggle = {},
                        onMuteToggle = {},
                        onPinToggle = {},
                        onMovePinned = {},
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

    private fun chatItem(
        groupIdByte: String = "02",
        name: String = GROUP_NAME,
    ) = ChatListItem(
        group =
            AppGroupRecordFfi(
                groupIdHex = groupIdByte.repeat(32),
                protocolProfile = AppProtocolProfileFfi.LEGACY,
                endpoint = "wss://relay.example",
                profilePresent = true,
                name = name,
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
        const val CHAT_DRAG_HOST_TAG = "chat-drag-host"
        const val CHAT_HOLD_HOST_TAG = "chat-hold-host"
    }
}

@Composable
private fun ProductionChatListPlacementHarness(
    itemIds: List<String>,
    items: Map<String, ChatListItem>,
    appState: WhiteNoiseAppState,
    onOpen: (String) -> Unit,
    onOpenActions: (String) -> Unit,
) {
    val placementInProgress =
        rememberChatListRowPlacementGate(
            orderedRowIds = itemIds,
            pinnedBoundaryIndex = null,
            leadingItemCount = 0,
        )
    LazyColumn {
        itemIds.forEachIndexed { targetIndex, id ->
            item(key = id) {
                Box(modifier = chatListRowMotion(targetIndex).testTag(productionChatRowTag(id))) {
                    ChatListRow(
                        item = items.getValue(id),
                        appState = appState,
                        isMuted = false,
                        interactionsEnabled = !placementInProgress,
                        selectionMode = false,
                        selected = false,
                        onOpen = { onOpen(id) },
                        onOpenProfile = {},
                        onOpenActions = { onOpenActions(id) },
                        onDragSelectionStart = {},
                        onDragSelection = { false },
                        onDragSelectionEnd = {},
                        onDragSelectionCancel = {},
                        rangeDragActive = false,
                        onToggleSelection = {},
                    )
                }
            }
        }
    }
}

private fun productionChatRowTag(id: String): String = "production-chat-row-$id"
