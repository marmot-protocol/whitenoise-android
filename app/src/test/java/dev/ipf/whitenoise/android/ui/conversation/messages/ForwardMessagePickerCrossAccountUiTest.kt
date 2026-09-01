package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
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

    /** Builds an app state with two signing accounts and one active-account chat. */
    private fun twoAccountAppState(): WhiteNoiseAppState =
        appStateWithDirectChats(
            GROUP_UNDER_A to PEER_A_ID,
            profiles =
                mutableMapOf(
                    PEER_A_ID to profile("Person A"),
                    PEER_B_ID to profile("Person B"),
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
        val PEER_B_ID = "41".repeat(32)
        val GROUP_UNDER_A = "20".repeat(32)
        val GROUP_UNDER_B = "21".repeat(32)
        val ORIGIN_GROUP = "ff".repeat(32)
    }
}
