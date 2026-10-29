package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual chat-list navigation and row ownership on an emulator, using local authoritative projections. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ChatListContinuityAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: WhiteNoiseAppState
    private lateinit var controller: ChatsController
    private var selectedFolder by mutableStateOf<String?>(null)
    private var search by mutableStateOf(GlobalSearchState())
    private var mounted by mutableStateOf(true)
    private var opens = 0
    private var previousAnimationScale: String? = null
    private val account = "continuity-emulator"
    private val firstRow = "c1".repeat(32)
    private val secondRow = "c2".repeat(32)

    /** CI disables animations globally; enable the motion under test and restore that setting afterwards. */
    @Before
    fun prepareLocalProjection() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val context = rule.activity
        previousAnimationScale =
            Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        check(Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        rule.runOnUiThread {
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(DiscardedDrafts),
                    accountIdHexResolver = { null },
                    accounts = listOf(AccountSummaryFfi(account, "a0".repeat(32), true, false, false, true)),
                    activeAccountRef = account,
                )
            controller = ChatsController(state, account) { _, _ -> emptyList() }
            controller.applyChatListRow(
                chatRow(firstRow, "First local group", 2uL).copy(unreadCount = 3uL, hasUnread = true),
            )
            controller.applyChatListRow(chatRow(secondRow, "Second local group", 1uL))
        }
        rule.waitUntil(5_000) { controller.items.size == 2 }
    }

    /** Release the actual mounted screen before retiring its controller and restoring emulator motion policy. */
    @After
    fun releaseFixture() {
        try {
            rule.mainClock.autoAdvance = true
            if (::controller.isInitialized) {
                rule.runOnUiThread { mounted = false }
                rule.waitForIdle()
                rule.runOnUiThread { controller.onCleared() }
            }
        } finally {
            try {
                Settings.Global.putString(
                    rule.activity.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    previousAnimationScale,
                )
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    /** Folder A/B/A retires stale rows and callbacks on the first frame, including an interrupted arrival. */
    @Test
    fun actualFolderSwitchRetiresOldRowsAndCapturedActionsDuringReversal() {
        val first = state.chatFolderPreferences.createFolder(account, "First")!!
        val second = state.chatFolderPreferences.createFolder(account, "Second")!!
        state.chatFolderPreferences.setChatInFolder(account, first.id, firstRow, true)
        state.chatFolderPreferences.setChatInFolder(account, second.id, secondRow, true)
        selectedFolder = first.id
        render()
        val oldOpen = rule.onNodeWithTag("chat.row.$firstRow").captureClickCallbackForReplay()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selectedFolder = second.id }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
        rule.onAllNodesWithTag("chat.row.$secondRow").assertCountEquals(1)
        rule.runOnIdle { oldOpen() }
        assertEquals("the retired folder cannot open its captured row", 0, opens)
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { selectedFolder = first.id }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("chat.row.$secondRow").assertDoesNotExist()
        rule.onAllNodesWithTag("chat.row.$firstRow").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS)
        rule.onNodeWithTag("chat.row.$firstRow").performClick()
        assertEquals(1, opens)
    }

    /** Active/archived viewports keep separate scroll state but share one interruptible arrival owner. */
    @Test
    fun archivedFolderArrivalSurvivesViewportReplacementAndReversal() {
        rule.runOnIdle {
            controller.applyChatListRow(chatRow(secondRow, "Archived local group", 1uL).copy(archived = true))
        }
        render()
        val activeTop =
            rule
                .onNodeWithTag("chat.row.$firstRow")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selectedFolder = ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
        rule.onAllNodesWithTag("chat.row.$secondRow").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { selectedFolder = null }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        val returningTop =
            rule
                .onNodeWithTag("chat.row.$firstRow")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue("reversal must retain the in-flight arrival", returningTop > activeTop)
        rule.mainClock.advanceTimeBy(240)
        assertEquals(
            activeTop,
            rule
                .onNodeWithTag("chat.row.$firstRow")
                .fetchSemanticsNode()
                .boundsInRoot
                .top,
            1f,
        )
        rule.runOnIdle { selectedFolder = ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        val arrivingTop =
            rule
                .onNodeWithTag("chat.row.$secondRow")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        rule.mainClock.advanceTimeBy(240)
        val settledTop =
            rule
                .onNodeWithTag("chat.row.$secondRow")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue("Archived must animate despite replacing its scroll viewport", arrivingTop > settledTop)
        rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
    }

    /** The real selection command owns semantics immediately while the decorative leading strip animates. */
    @Test
    fun actualSelectionKeepsUnreadMetadataAndOneCurrentRowThroughExitReversal() {
        render()
        val row = rule.onNodeWithTag("chat.row.$firstRow")
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        rule.onNodeWithTag("chat.action.Select").assertExists()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("chat.action.Select").performClick()
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        row.assertIsSelected()
        assertCurrentUnreadBadge()
        rule.onNodeWithTag("chat.row.$secondRow").assertIsNotSelected()
        rule.onAllNodesWithTag("chat.row.$firstRow").assertCountEquals(1)
        assertTrue(
            "selection must not mark the unread projection read",
            controller.items.first { it.id == firstRow }.effectiveHasUnread(state.activeAccount?.accountIdHex),
        )
        rule.mainClock.advanceTimeBy(64)
        val exitStarted = rule.mainClock.currentTime
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        row.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        awaitSelectDuringExit(exitStarted)
        rule.onNodeWithTag("chat.action.Select").performClick()
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        row.assertIsSelected()
        assertCurrentUnreadBadge()
        assertEquals("selection never opens a conversation", 0, opens)
        rule.mainClock.advanceTimeBy(240)
        assertTrue(controller.items.first { it.id == firstRow }.effectiveHasUnread(state.activeAccount?.accountIdHex))
    }

    /** Selects the authoritative count slot; the visual badge also supplies an unmerged description. */
    private fun assertCurrentUnreadBadge() {
        val description = rule.activity.resources.getQuantityString(R.plurals.unread_messages_count, 3, 3)
        rule
            .onNode(
                hasText("3") and hasContentDescription(description) and
                    hasAnyAncestor(hasTestTag("chat.row.$firstRow")),
                useUnmergedTree = true,
            ).assertExists()
    }

    /** Samples actual list paint across folder/search keys, independently of the separate real-IME cases. */
    @Test
    fun actualFolderAndSearchKeysRetainEligibleFadePixels() {
        val folders = prepareFadeFolders()
        selectedFolder = folders[0]
        val originalImeFlag =
            rule.runOnIdle {
                rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
        try {
            render(fadeProbe = true)
            rule.onNodeWithTag(chatListFilterChipTag(folders[0])).assertIsSelected()
            rule.onNodeWithTag("chats.list").performScrollToIndex(6)
            rule.mainClock.advanceTimeBy(240)
            rule.waitForIdle()
            val probe = ChatFolderFadeFrameProbe(rule)
            probe.assertEdges("scrolled-a", top = true, bottom = true)
            rule.mainClock.autoAdvance = false
            publishFadeChange { selectedFolder = folders[1] }
            rule.onNodeWithTag(chatListFilterChipTag(folders[1])).assertIsSelected()
            probe.assertEdges("b-first", top = true, bottom = true)
            rule.mainClock.advanceTimeBy(60)
            probe.assertEdges("b-middle", top = true, bottom = true)
            publishFadeChange { selectedFolder = folders[0] }
            rule.onNodeWithTag(chatListFilterChipTag(folders[0])).assertIsSelected()
            probe.assertEdges("a-reversed", top = true, bottom = true)
            rule.mainClock.advanceTimeBy(240)
            probe.assertEdges("a-settled", top = true, bottom = true)
            publishFadeChange { search = search.copy(isOpen = true) }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            rule.onNodeWithTag("chats.searchField").assertIsFocused()
            probe.assertEdges("search-first", top = true, bottom = true)
            rule.mainClock.advanceTimeBy(60)
            probe.assertEdges("search-middle", top = true, bottom = true)
            publishFadeChange { search = search.copy(isOpen = false) }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithTag("chats.searchField").assertDoesNotExist()
            probe.assertEdges("search-return", top = true, bottom = true)
            publishFadeChange { selectedFolder = folders[2] }
            rule.onNodeWithTag(chatListFilterChipTag(folders[2])).assertIsSelected()
            probe.assertFitting("short-first")
            publishFadeChange { selectedFolder = folders[3] }
            rule.onNodeWithTag(chatListFilterChipTag(folders[3])).assertIsSelected()
            rule.onNodeWithTag("chats.list").assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_empty_folder_detail)).assertExists()
            publishFadeChange { selectedFolder = folders[0] }
            rule.onNodeWithTag(chatListFilterChipTag(folders[0])).assertIsSelected()
            // Folder changes retain their keyed viewport; choose an overflowing anchor for renewed fade eligibility.
            rule.onNodeWithTag("chats.list").performScrollToIndex(6)
            rule.mainClock.advanceTimeBy(240)
            probe.assertEdges("return-from-empty", top = true, bottom = true)
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread {
                rule.activity.window.setFlags(originalImeFlag, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
        }
    }

    /** Archive replacement retains an eligible bottom band while its fresh viewport exposes the resting top. */
    @Test
    fun actualArchivedFolderSwitchRetainsEligibleFadePixels() {
        prepareArchivedFadeRows()
        val originalImeFlag =
            rule.runOnIdle {
                rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
        try {
            render(fadeProbe = true)
            val probe = ChatFolderFadeFrameProbe(rule)
            probe.assertEdges("archive-active-initial", top = false, bottom = true)
            rule.mainClock.autoAdvance = false
            publishFadeChange { selectedFolder = ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID }
            rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
            probe.assertEdges("archive-first", top = false, bottom = true)
            rule.mainClock.advanceTimeBy(60)
            probe.assertEdges("archive-middle", top = false, bottom = true)
            publishFadeChange { selectedFolder = null }
            rule.onNodeWithTag("chat.row.$firstRow").assertExists()
            probe.assertEdges("active-reversal-first", top = false, bottom = true)
            rule.mainClock.advanceTimeBy(60)
            probe.assertEdges("active-reversal-middle", top = false, bottom = true)
            rule.mainClock.advanceTimeBy(240)
            probe.assertEdges("active-reversal-settled", top = false, bottom = true)
            publishFadeChange { selectedFolder = ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID }
            rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
            probe.assertEdges("archive-return-first", top = false, bottom = true)
            rule.mainClock.advanceTimeBy(60)
            probe.assertEdges("archive-return-middle", top = false, bottom = true)
            rule.mainClock.advanceTimeBy(240)
            probe.assertEdges("archive-return-settled", top = false, bottom = true)
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread {
                rule.activity.window.setFlags(originalImeFlag, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
        }
    }

    /** Materializes separate overflowing active and archived projections without sharing their scroll state. */
    private fun prepareArchivedFadeRows() {
        rule.runOnUiThread {
            (3..34).forEach { index ->
                val activeId = index.toString(16).padStart(64, '0')
                val archivedId = (index + 64).toString(16).padStart(64, '0')
                controller.applyChatListRow(chatRow(activeId, "Active fade $index", 1uL))
                controller.applyChatListRow(chatRow(archivedId, "Archived fade $index", 1uL).copy(archived = true))
            }
        }
        rule.waitUntil(5_000) { controller.items.size == 34 && controller.archivedItems.size == 32 }
    }

    /** Uses identical authoritative rows in the long folders so membership cannot change edge eligibility. */
    private fun prepareFadeFolders(): List<String> {
        val rows = (3..34).map { it.toString(16).padStart(64, '0') }
        rule.runOnUiThread {
            rows.forEachIndexed { index, id -> controller.applyChatListRow(chatRow(id, "Fade row $index", 1uL)) }
        }
        rule.waitUntil(5_000) { controller.items.size == rows.size + 2 }
        state.chatFolderPreferences.foldersFor(account)
        val memberships = listOf(rows.toSet(), rows.toSet(), setOf(rows.first()), emptySet())
        return memberships.mapIndexed { index, ids ->
            requireNotNull(
                state.chatFolderPreferences.commitFolderDraft(
                    accountRef = account,
                    folderId = null,
                    name = "Fade $index",
                    description = "",
                    manualChatIds = ids,
                    rule = null,
                    showWhenEmpty = true,
                ),
            ).id
        }
    }

    /** Publishes the new key, then captures its first measured/drawn frame without completing the fade window. */
    private fun publishFadeChange(change: () -> Unit) {
        rule.runOnUiThread {
            change()
            Snapshot.sendApplyNotifications()
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    /** Admit the real popup without allowing the 160 ms selection-strip exit to finish before reversal. */
    private fun awaitSelectDuringExit(exitStarted: Long) {
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        rule.onNodeWithTag("chat.menu.$firstRow").assertExists()
        while (rule.onAllNodesWithTag("chat.action.Select").fetchSemanticsNodes().isEmpty()) {
            assertTrue("popup must become actionable during the exit", rule.mainClock.currentTime - exitStarted < 100)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertTrue("selection must reverse before its exit settles", rule.mainClock.currentTime - exitStarted < 120)
    }

    /** Changing the folder while a real unread pull is held invalidates its old pointer owner. */
    @Test
    fun heldUnreadPullCannotFilterTheReplacementFolderAndSearchHasOneEditor() {
        val folder = state.chatFolderPreferences.createFolder(account, "Replacement")!!
        state.chatFolderPreferences.setChatInFolder(account, folder.id, secondRow, true)
        state.swipePreferences.setPullUnread(true)
        render()
        val distance = with(rule.density) { 100.dp.toPx() }
        val row = rule.onNodeWithTag("chat.row.$firstRow")
        row.performTouchInput {
            down(Offset(width * 0.6f, 10f))
            moveBy(Offset(0f, distance), 120)
        }
        rule.runOnIdle { selectedFolder = folder.id }
        rule.onNodeWithTag("chats.unread_surface").performTouchInput { up() }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$secondRow").assertExists()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_list_search_open)).performClick()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithTag("chat.row.$firstRow").assertDoesNotExist()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("chats.searchField").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$secondRow").assertExists()
        assertEquals(0, opens)
    }

    /** Composes the same screen and state callbacks used by MainShell, without creating a native account. */
    private fun render(fadeProbe: Boolean = false) {
        rule.setContent {
            WhiteNoiseTheme {
                val scheme = MaterialTheme.colorScheme
                val colors = if (fadeProbe) scheme.copy(surface = Color.Green, background = Color.Black) else scheme
                MaterialTheme(colorScheme = colors) {
                    Surface {
                        if (mounted) {
                            ChatsScreen(
                                appState = state,
                                controller = controller,
                                onOpenSettings = {},
                                onOpenGroup = { _, _, _, _ -> opens++ },
                                selectedFolderId = selectedFolder,
                                onSelectFolder = { selectedFolder = it },
                                globalSearchState = search,
                                onGlobalSearchStateChange = { transform -> search = transform(search) },
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS)
        rule.waitForIdle()
    }

    /** A stable member group row with the given activity time and no messages. */
    private fun chatRow(
        groupId: String,
        name: String,
        activity: ULong,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = name,
        groupName = name,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = activity,
        activitySortAt = activity,
        updatedAt = activity,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    /** The fixture cannot read or persist a personal draft. */
    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
