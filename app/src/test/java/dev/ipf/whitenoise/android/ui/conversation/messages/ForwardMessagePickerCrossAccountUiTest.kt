package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.AccountSwitchProfileSeed
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.PendingForwardRequest
import dev.ipf.whitenoise.android.state.PendingForwardRequestStore
import dev.ipf.whitenoise.android.state.SerializedPendingForwardRequestStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.applyLocalDirectChat
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.group
import dev.ipf.whitenoise.android.ui.share.member
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cross-account destination selection in the forward picker: the sending
 * account defaults to the source owner, switching accounts swaps in the
 * destination account's own chats, and selections never survive an account
 * change. Selection state mirrors into the pending-request store and the
 * confirmed request carries the explicit destination account.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerCrossAccountUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class InMemoryPendingForwardStore : PendingForwardRequestStore {
        var entry: PendingForwardRequest? = null

        /** Records the saved request in memory. */
        override fun save(request: PendingForwardRequest): Boolean {
            entry = request
            return true
        }

        /** Returns the in-memory entry. */
        override fun load(): PendingForwardRequest? = entry

        /** Clears the entry only when its id matches. */
        override fun remove(requestId: String) {
            if (entry?.requestId == requestId) entry = null
        }

        /** Drops the in-memory entry. */
        override fun clear() {
            entry = null
        }
    }

    private val accountB = testAccount(ACCOUNT_B_REF, ACCOUNT_B_HEX)

    /** Builds an app state with two signing accounts and two active-account chats. */
    private fun twoAccountAppState(): WhiteNoiseAppState =
        appStateWithDirectChats(
            GROUP_UNDER_A to PEER_A_ID,
            GROUP_UNDER_A_2 to PEER_A_2_ID,
            profiles =
                mutableMapOf(
                    PEER_A_ID to profile("Person A"),
                    PEER_A_2_ID to profile("Person A2"),
                    PEER_B_ID to profile("Person B"),
                    PEER_B_2_ID to profile("Person B2"),
                ),
            accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX), accountB),
        )

    /** Provides a controller factory pre-bound to the second account. */
    private fun accountBControllerFactory(): Pair<(WhiteNoiseAppState) -> ChatsController, MutableList<String>> {
        val boundAccounts = mutableListOf<String>()
        val factory: (WhiteNoiseAppState) -> ChatsController = { state ->
            ChatsController(state, ACCOUNT_B_REF) { _, _ -> emptyList() }
        }
        return factory to boundAccounts
    }

    /** Hosts the picker content with seeded controller and binder seams. */
    @Suppress("TestFunctionName")
    @androidx.compose.runtime.Composable
    private fun Picker(
        appState: WhiteNoiseAppState,
        factory: (WhiteNoiseAppState) -> ChatsController,
        onForward: (String, List<String>) -> Boolean = { _, _ -> true },
        onPickerStateChanged: PickerStateListener = { _, _, _ -> },
        boundAccounts: MutableList<String> = mutableListOf(),
        includeSecondDestinationChat: Boolean = false,
    ) {
        WhiteNoiseTheme(darkTheme = true) {
            Surface {
                ForwardMessagePickerContent(
                    appState = appState,
                    messageCount = 1,
                    attachmentCount = 0,
                    originGroupIdHex = ORIGIN_GROUP,
                    sourceAccountRef = ACCOUNT_REF,
                    onDismiss = {},
                    onForward = onForward,
                    onPickerStateChanged = onPickerStateChanged,
                    controllerFactory = factory,
                    controllerBinder = { controller, accountRef ->
                        boundAccounts += accountRef
                        controller.applyLocalDirectChat(GROUP_UNDER_B, ACCOUNT_B_HEX, PEER_B_ID)
                        if (includeSecondDestinationChat) {
                            controller.applyLocalDirectChat(GROUP_UNDER_B_2, ACCOUNT_B_HEX, PEER_B_2_ID)
                        }
                    },
                )
            }
        }
    }

    /** The picker defaults to the source account and swaps in the chosen account's chats. */
    @Test
    fun pickerDefaultsToTheSourceAccountAndSwitchingLoadsDestinationScopedChats() {
        val appState = twoAccountAppState()
        val (factory, boundAccounts) = accountBControllerFactory()
        composeRule.setContent { Picker(appState, factory, boundAccounts = boundAccounts) }

        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Person A").assertIsDisplayed()

        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(ACCOUNT_B_REF).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ACCOUNT_B_REF), boundAccounts)
        composeRule.onNodeWithText("Person B").assertIsDisplayed()
        composeRule.onNodeWithText("Person A").assertDoesNotExist()
    }

    /** Account changes clear selections and confirm carries the explicit destination. */
    @Test
    fun chatSelectionsAreClearedByADestinationAccountChangeAndConfirmCarriesTheDestination() {
        val appState = twoAccountAppState()
        val (factory, _) = accountBControllerFactory()
        val forwards = mutableListOf<Pair<String, List<String>>>()
        var lastState: Triple<String?, List<String>, Map<String, String>>? = null
        composeRule.setContent {
            Picker(
                appState = appState,
                factory = factory,
                onForward = { accountRef, groups ->
                    forwards += accountRef to groups
                    true
                },
                onPickerStateChanged = { accountRef, selected, titles ->
                    lastState = Triple(accountRef, selected, titles)
                },
            )
        }

        composeRule.onNodeWithText("Person A").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(GROUP_UNDER_A), lastState?.second)
        assertEquals(mapOf(GROUP_UNDER_A to "Person A"), lastState?.third)

        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithText(ACCOUNT_B_REF).performClick()
        composeRule.waitForIdle()
        assertEquals(ACCOUNT_B_REF, lastState?.first)
        assertTrue(lastState!!.second.isEmpty())

        composeRule.onNodeWithText("Person B").performClick()
        composeRule.onNodeWithText("Forward to 1 chat").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ACCOUNT_B_REF to listOf(GROUP_UNDER_B)), forwards)
    }

    /** The sheet mirrors live selection into the store and discards it on dismiss. */
    @Test
    fun sheetMirrorsSelectionIntoTheStoreAndDiscardsItOnDismiss() {
        val appState = twoAccountAppState()
        val store = InMemoryPendingForwardStore()
        appState.forwardRequestPersistence.override(
            SerializedPendingForwardRequestStore(store, ioDispatcher = Dispatchers.Main.immediate),
        )
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessageSheet(
                        appState = appState,
                        payloads = listOf(textPayload()),
                        sourceAccountRef = ACCOUNT_REF,
                        originGroupIdHex = ORIGIN_GROUP,
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Person A").performClick()
        composeRule.waitForIdle()
        val persisted = requireNotNull(store.entry)
        assertEquals(ACCOUNT_REF, persisted.sourceAccountRef)
        assertEquals(ORIGIN_GROUP, persisted.originGroupIdHex)
        assertEquals(listOf(GROUP_UNDER_A), persisted.selectedGroupIds)
        assertEquals(listOf(textPayload()), persisted.payloads)

        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
        assertNull(store.entry)
    }

    /** A restored request preselects its destination and chat selection. */
    @Test
    fun restoredRequestPreselectsItsDestinationAndChats() {
        val appState = twoAccountAppState()
        val store = InMemoryPendingForwardStore()
        appState.forwardRequestPersistence.override(
            SerializedPendingForwardRequestStore(store, ioDispatcher = Dispatchers.Main.immediate),
        )
        val restored =
            PendingForwardRequest(
                requestId = "restored-1",
                sourceAccountRef = ACCOUNT_REF,
                originGroupIdHex = ORIGIN_GROUP,
                payloads = listOf(textPayload()),
                destinationAccountRef = ACCOUNT_REF,
                selectedGroupIds = listOf(GROUP_UNDER_A),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessageSheet(
                        appState = appState,
                        payloads = restored.payloads,
                        sourceAccountRef = restored.sourceAccountRef,
                        originGroupIdHex = restored.originGroupIdHex,
                        onDismiss = {},
                        restoredRequest = restored,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Forward to 1 chat").assertIsDisplayed()
    }

    /** Selections restored for a signed-out destination are dropped, never re-owned. */
    @Test
    fun restoredSelectionsForASignedOutDestinationAreDropped() {
        val appState = twoAccountAppState()
        val (factory, _) = accountBControllerFactory()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessagePickerContent(
                        appState = appState,
                        messageCount = 1,
                        attachmentCount = 0,
                        originGroupIdHex = ORIGIN_GROUP,
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                        initialDestinationAccountRef = "signed-out-account",
                        initialSelectedGroupIds = listOf(GROUP_UNDER_A),
                        controllerFactory = factory,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Forward").assertIsDisplayed()
        composeRule.onNodeWithText("Forward to 1 chat").assertDoesNotExist()
    }

    /** Folder selection and horizontal scroll never transfer to another destination account. */
    @Test
    fun folderSelectionAndScrollAreClearedWhenTheDestinationAccountChanges() {
        val appState = twoAccountAppState()
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_B_REF)
        val sourceFolders =
            (0 until 8).map { index ->
                seedFolder(appState, ACCOUNT_REF, "Alice work folder $index", GROUP_UNDER_A, GROUP_UNDER_A_2)
            }
        val destinationFolders =
            (0 until 8).map { index ->
                seedFolder(appState, ACCOUNT_B_REF, "Bob home folder $index", GROUP_UNDER_B, GROUP_UNDER_B_2)
            }
        val (factory, _) = accountBControllerFactory()
        var selected = emptyList<String>()
        composeRule.setContent {
            Picker(
                appState = appState,
                factory = factory,
                onPickerStateChanged = { _, groupIds, _ -> selected = groupIds },
                includeSecondDestinationChat = true,
            )
        }

        composeRule.onNodeWithText("Person A").assertIsDisplayed()
        val sourceFirst = composeRule.onNodeWithTag(forwardFolderChipTestTag(sourceFolders.first().id))
        val initialLeadingEdge = sourceFirst.getUnclippedBoundsInRoot().left.value
        composeRule
            .onNodeWithTag(forwardFolderChipTestTag(sourceFolders.last().id))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertTrue(sourceFirst.getUnclippedBoundsInRoot().left.value < initialLeadingEdge - 100f)
        assertEquals(setOf(GROUP_UNDER_A, GROUP_UNDER_A_2), selected.toSet())
        composeRule.onNodeWithText("Forward to 2 chats").assertIsDisplayed()

        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithText(ACCOUNT_B_REF).performClick()
        composeRule.waitForIdle()

        assertTrue(selected.isEmpty())
        composeRule.onNodeWithTag(forwardFolderChipTestTag(sourceFolders.first().id)).assertDoesNotExist()
        val destinationFirst = composeRule.onNodeWithTag(forwardFolderChipTestTag(destinationFolders.first().id))
        destinationFirst.assertIsDisplayed()
        assertEquals(initialLeadingEdge, destinationFirst.getUnclippedBoundsInRoot().left.value, 0.1f)
        assertFolderChipState(destinationFolders.first(), 2, ToggleableState.Off)
        destinationFirst.performClick()
        composeRule.waitForIdle()
        assertEquals(setOf(GROUP_UNDER_B, GROUP_UNDER_B_2), selected.toSet())
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_B_REF)
    }

    /** Persisted origin/stale IDs cannot inflate folder counts, bypass eligibility, or reach submission. */
    @Test
    fun folderBulkRowsCountSelectAndSubmitOnlyCurrentEligibleDestinations() {
        val appState =
            appStateWithDirectChats(
                GROUP_UNDER_A to PEER_A_ID,
                GROUP_UNDER_A_2 to PEER_A_2_ID,
                ORIGIN_GROUP to PEER_B_ID,
            )
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
        val folder =
            seedFolder(appState, ACCOUNT_REF, "Work", STALE_GROUP, GROUP_UNDER_A_2, ORIGIN_GROUP, GROUP_UNDER_A)
        val hiddenFolder = seedFolder(appState, ACCOUNT_REF, "Only one eligible", GROUP_UNDER_A, STALE_GROUP)
        val eligibleTargets = appState.forwardTargets().filterNot { it.group.groupIdHex == ORIGIN_GROUP }
        val rows = forwardFolderBulkRows(appState, eligibleTargets, GroupTitleCopy.Default)
        assertEquals(listOf(folder.id), rows.map { it.first.id })
        assertEquals(eligibleTargets.map { it.group.groupIdHex }, rows.single().second)
        val (factory, _) = accountBControllerFactory()
        val forwards = mutableListOf<Pair<String, List<String>>>()
        var selected = emptyList<String>()
        composeRule.setContent {
            Picker(
                appState = appState,
                factory = factory,
                onPickerStateChanged = { _, groupIds, _ -> selected = groupIds },
                onForward = { accountRef, groupIds ->
                    forwards += accountRef to groupIds
                    true
                },
            )
        }

        composeRule.onNodeWithTag(forwardFolderChipTestTag(hiddenFolder.id)).assertDoesNotExist()
        assertFolderChipState(folder, 2, ToggleableState.Off)
        composeRule.onNodeWithTag(forwardFolderChipTestTag(folder.id)).performClick()
        composeRule.waitForIdle()
        assertEquals(rows.single().second, selected)
        assertFolderChipState(folder, 2, ToggleableState.On)
        composeRule.onNodeWithText("Forward to 2 chats").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ACCOUNT_REF to rows.single().second), forwards)
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
    }

    /** Profile hydration re-evaluates a keyword folder under the active search without remounting. */
    @Test
    fun profileRevisionRefreshesAFilteredKeywordFolder() {
        val appState = twoAccountAppState()
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
        val folder = requireNotNull(appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Profile updates"))
        appState.chatFolderPreferences.setFolderRule(
            accountRef = ACCOUNT_REF,
            folderId = folder.id,
            rule = ChatFolderRule(keyword = "ready"),
        )
        val (factory, _) = accountBControllerFactory()
        composeRule.setContent { Picker(appState = appState, factory = factory) }
        composeRule
            .onNodeWithText(context.getString(R.string.forward_search_chats))
            .performClick()
            .performTextInput("profile")
        composeRule.onNodeWithTag(forwardFolderChipTestTag(folder.id)).assertDoesNotExist()
        val initialProfileRevision = appState.profileRevisionForCompose

        composeRule.runOnIdle {
            appState.applyAccountSwitchProfileSeed(profileSeed(PEER_A_ID, "Ready A"))
            appState.applyAccountSwitchProfileSeed(profileSeed(PEER_A_2_ID, "Ready A2"))
        }
        composeRule.waitForIdle()

        assertTrue(initialProfileRevision != appState.profileRevisionForCompose)
        assertFolderChipState(folder, 2, ToggleableState.Off)
        composeRule.onNodeWithTag(forwardFolderChipTestTag(folder.id)).performClick()
        assertFolderChipState(folder, 2, ToggleableState.On)
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
    }

    /** Roster hydration inserts a rule folder in stored order and derives mixed state from live selection. */
    @Test
    fun memberRevisionRefreshesAnOrderedMixedRuleFolder() {
        val appState =
            emptyAppState(
                profiles =
                    mutableMapOf(
                        PEER_A_ID to profile("Person A"),
                        PEER_A_2_ID to profile("Person A2"),
                    ),
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_UNDER_A, ACCOUNT_HEX, PEER_A_ID)
        controller.applyLocalDirectChat(GROUP_UNDER_A_2, ACCOUNT_HEX, PEER_A_2_ID)
        appState.attachChatsController(controller)
        try {
            appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
            val manualFolder = seedFolder(appState, ACCOUNT_REF, "Manual", GROUP_UNDER_A, GROUP_UNDER_A_2)
            val rosterFolder = requireNotNull(appState.chatFolderPreferences.createFolder(ACCOUNT_REF, "Roster"))
            appState.chatFolderPreferences.setFolderRule(
                accountRef = ACCOUNT_REF,
                folderId = rosterFolder.id,
                rule = ChatFolderRule(includeMemberPubkeys = setOf(RULE_MEMBER_ID)),
            )
            val (factory, _) = accountBControllerFactory()
            composeRule.setContent { Picker(appState = appState, factory = factory) }
            composeRule.onNodeWithText("Person A").performClick()
            composeRule.onNodeWithTag(forwardFolderChipTestTag(rosterFolder.id)).assertDoesNotExist()
            val initialMemberRevision = controller.memberSnapshotsRevision

            composeRule.runOnIdle {
                controller.applyLocalGroupDetailsWithRuleMember(GROUP_UNDER_A, PEER_A_ID)
                controller.applyLocalGroupDetailsWithRuleMember(GROUP_UNDER_A_2, PEER_A_2_ID)
            }
            composeRule.waitForIdle()

            assertTrue(controller.memberSnapshotsRevision > initialMemberRevision)
            assertFolderChipState(rosterFolder, 2, ToggleableState.Indeterminate)
            val manualLeft =
                composeRule.onNodeWithTag(forwardFolderChipTestTag(manualFolder.id)).getUnclippedBoundsInRoot().left
            val rosterLeft =
                composeRule.onNodeWithTag(forwardFolderChipTestTag(rosterFolder.id)).getUnclippedBoundsInRoot().left
            assertTrue("newly eligible folder must retain stored order", manualLeft < rosterLeft)
        } finally {
            appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
            appState.attachChatsController(null)
            controller.onCleared()
        }
    }

    /** Persists real manual folder membership so tests exercise the production row projection. */
    private fun seedFolder(
        appState: WhiteNoiseAppState,
        accountRef: String,
        name: String,
        vararg groupIds: String,
    ): ChatFolder {
        val store = appState.chatFolderPreferences
        val folder = requireNotNull(store.createFolder(accountRef, name))
        groupIds.forEach { store.setChatInFolder(accountRef, folder.id, it, included = true) }
        return folder
    }

    /** Asserts the actual accessible eligible count and tri-state, not only the visible label. */
    private fun assertFolderChipState(
        folder: ChatFolder,
        count: Int,
        state: ToggleableState,
    ) {
        val config = composeRule.onNodeWithTag(forwardFolderChipTestTag(folder.id)).fetchSemanticsNode().config
        assertEquals(listOf("${folder.name}, $count chats"), config[SemanticsProperties.ContentDescription])
        assertEquals(state, config[SemanticsProperties.ToggleableState])
    }

    /** Builds one cache seed that changes the rendered direct-chat title. */
    private fun profileSeed(
        accountIdHex: String,
        displayName: String,
    ) = AccountSwitchProfileSeed(
        accountIdHex = accountIdHex,
        profile = profile(displayName),
        displayName = displayName,
        avatarUrl = null,
    )

    /** Replaces one direct-chat roster with an authoritative rule-matching member set. */
    private fun ChatsController.applyLocalGroupDetailsWithRuleMember(
        groupIdHex: String,
        peerIdHex: String,
    ) {
        applyLocalGroupDetails(
            record = group(groupIdHex),
            members =
                listOf(
                    member(ACCOUNT_HEX, local = true),
                    member(peerIdHex, local = false),
                    member(RULE_MEMBER_ID, local = false),
                ),
        )
    }

    /** Builds one text payload rooted in the origin group. */
    private fun textPayload() =
        ForwardMessagePayload.Text(
            sourceGroupIdHex = ORIGIN_GROUP,
            sourceMessageIdHex = "01".repeat(32),
            text = "body",
        )

    private companion object {
        const val ACCOUNT_B_REF = "bob"
        val ACCOUNT_B_HEX = "d0".repeat(32)
        val PEER_A_ID = "40".repeat(32)
        val PEER_A_2_ID = "42".repeat(32)
        val PEER_B_ID = "41".repeat(32)
        val PEER_B_2_ID = "43".repeat(32)
        val GROUP_UNDER_A = "20".repeat(32)
        val GROUP_UNDER_A_2 = "22".repeat(32)
        val GROUP_UNDER_B = "21".repeat(32)
        val GROUP_UNDER_B_2 = "23".repeat(32)
        val STALE_GROUP = "ee".repeat(32)
        val RULE_MEMBER_ID = "ef".repeat(32)
        val ORIGIN_GROUP = "ff".repeat(32)
    }
}
