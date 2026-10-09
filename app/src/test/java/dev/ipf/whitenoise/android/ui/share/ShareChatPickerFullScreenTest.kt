package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerFullScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val payload =
        SharePayload(
            text = "shared text",
            streamUris = emptyList(),
            intentMimeType = "text/plain",
        )

    @Test
    fun fullScreenPickerUsesAvailableHeightForAUsefulRecipientList() {
        val chats =
            (0 until 12).map { index ->
                hexId(0x20 + index) to hexId(0x40 + index)
            }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) ->
                    peerId to profile(displayName = "Person $index")
                }.toMap(mutableMapOf())
        val appState = appStateWithDirectChats(*chats.toTypedArray(), profiles = profiles)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }
        composeRule.waitForIdle()

        val titleTop =
            composeRule
                .onNodeWithText(app.getString(R.string.share_to))
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Full-screen title must be in the top app bar", titleTop < 100f)
        composeRule.onNodeWithText("Person 5").assertIsDisplayed()
    }

    /** A not-yet-loaded local projection renders an honest empty state without a progress gate. */
    @Test
    fun activeAccountWithoutALocalSnapshotShowsTheDirectEmptyState() {
        val appState = emptyAppState()
        appState.attachChatsController(ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() })

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.share_no_chats)).assertIsDisplayed()
        composeRule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }

    /** Local rows remain usable while live subscription and enrichment work is still delayed. */
    @Test
    fun cachedRowsReplaceTheEmptyStateBeforeTheLiveBindCompletes() {
        val appState = emptyAppState(profiles = mutableMapOf(PEER_A to profile(displayName = "Cached person")))
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        appState.attachChatsController(controller)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.share_no_chats)).assertIsDisplayed()

        composeRule.runOnIdle {
            controller.applyChatListRow(chatRow(GROUP_A))
            controller.applyLocalGroupDetails(
                record = group(GROUP_A).copy(name = "Cached chat"),
                members = listOf(member(ACCOUNT_HEX, local = true), member(PEER_A, local = false)),
            )
        }

        composeRule.onNodeWithText("Cached person").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_chats)).assertDoesNotExist()
    }

    /** Chats MDK holds beyond the retained window are listed under the retained rows (#2618). */
    @Test
    fun chatsBeyondTheRetainedWindowJoinTheRecipientList() {
        val appState = emptyAppState(profiles = mutableMapOf(PEER_A to profile(displayName = "Cached person")))
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window in this test") },
                openChats = { _, _ -> error("no live window in this test") },
                presentedChatList = { _, _ ->
                    listOf(presentedRow(GROUP_A), presentedGroup(GROUP_B, "Beyond the window"))
                },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        appState.attachChatsController(controller)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Beyond the window").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cached person").assertIsDisplayed()
        composeRule.onNodeWithText("Beyond the window").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_chats)).assertDoesNotExist()
    }

    /** Offline refresh failure is actionable without replacing the local-first empty picker. */
    @Test
    fun failedRefreshKeepsEmptyPickerAndShowsInlineRetryError() {
        val appState = emptyAppState()
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.publishInitialLoadFailureForTest(
            ErrorPresentation(AppText.Plain("Offline refresh failed"), "operation=SHARE_PICKER"),
        )
        appState.attachChatsController(controller)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithText("Offline refresh failed").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_chats)).assertIsDisplayed()
    }

    @Test
    fun querySelectionAndTargetOrderSurviveSavedStateRecreation() {
        val profiles =
            mutableMapOf(
                PEER_A to profile(displayName = "Alice"),
                PEER_B to profile(displayName = "Bob"),
            )
        val appState =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                GROUP_B to PEER_B,
                profiles = profiles,
            )
        var staged = emptyList<String>()
        val request = ShareRequest(payload, shortcutId = null, requestId = "request-7")
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = request.requestId,
                    payload = request.payload,
                    onDismiss = {},
                    onStage = { _, groupIds ->
                        staged = groupIds
                        true
                    },
                )
            }
        }

        composeRule
            .onNodeWithText(app.getString(R.string.share_search_chats))
            .performClick()
            .performTextInput("Alice")
        composeRule.onAllNodesWithText("Alice")[1].performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertTextEquals("Alice")
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        val aliceTop =
            composeRule
                .onNodeWithText("Alice")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val bobTop =
            composeRule
                .onNodeWithText("Bob")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Restoration must preserve target ordering", aliceTop < bobTop)
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(GROUP_A), staged) }
    }

    @Test
    @Suppress("LongMethod")
    fun chosenAccountReloadsTargetsClearsSelectionAndSurvivesRestoration() {
        val workAccountRef = "work"
        val workAccountHex = "a1".repeat(32)
        val appState =
            emptyAppState(
                profiles =
                    mutableMapOf(
                        PEER_A to profile(displayName = "Alice"),
                        PEER_B to profile(displayName = "Bob"),
                    ),
                accounts =
                    listOf(
                        testAccount(ACCOUNT_REF, ACCOUNT_HEX),
                        testAccount(workAccountRef, workAccountHex),
                    ),
            )
        val activeController = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        activeController.applyChatListRow(chatRow(GROUP_A))
        activeController.applyLocalGroupDetails(
            record = group(GROUP_A).copy(name = "Personal chat"),
            members = listOf(member(ACCOUNT_HEX, local = true), member(PEER_A, local = false)),
        )
        appState.attachChatsController(activeController)
        val workControllerFactory: (WhiteNoiseAppState) -> ChatsController = { state ->
            ChatsController(state, workAccountRef) { _, _ -> emptyList() }.also { controller ->
                controller.applyChatListRow(chatRow(GROUP_B))
                controller.applyLocalGroupDetails(
                    record = group(GROUP_B).copy(name = "Work chat"),
                    members = listOf(member(workAccountHex, local = true), member(PEER_B, local = false)),
                )
            }
        }
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        val folder =
            requireNotNull(
                appState.chatFolderPreferences.commitFolderDraft(
                    ACCOUNT_REF,
                    null,
                    "Personal",
                    "",
                    setOf(GROUP_A),
                    null,
                ),
            )
        var stagedAccountRef: String? = null
        var stagedGroupIds = emptyList<String>()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = "multi-account-request",
                    payload = payload,
                    onDismiss = {},
                    onStage = { accountRef, groupIds ->
                        stagedAccountRef = accountRef
                        stagedGroupIds = groupIds
                        true
                    },
                    controllerFactory = workControllerFactory,
                    controllerBinder = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.share_sending_as)).assertIsDisplayed()
        composeRule.onNodeWithText("Alice").performClick()
        val filterTag = "destination.filter.${folder.id}"
        composeRule.onNodeWithTag("destination.filters").performScrollToNode(hasTestTag(filterTag))
        composeRule.onNodeWithTag(filterTag).performClick().assertIsSelected()
        composeRule.onNodeWithTag(SHARE_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithText(workAccountRef).performClick()

        composeRule.onNodeWithTag("destination.filter.all").assertIsSelected()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share)).assertIsNotEnabled()
        composeRule.onNodeWithText("Bob").performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(workAccountRef).assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertIsSelected()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(workAccountRef, stagedAccountRef)
            assertEquals(listOf(GROUP_B), stagedGroupIds)
        }
    }

    @Test
    fun listPositionSurvivesSavedStateRecreation() {
        val chats =
            (0 until 20).map { index ->
                hexId(0x20 + index) to hexId(0x40 + index)
            }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) ->
                    peerId to profile(displayName = "Person $index")
                }.toMap(mutableMapOf())
        val appState = appStateWithDirectChats(*chats.toTypedArray(), profiles = profiles)
        val request = ShareRequest(payload, shortcutId = null, requestId = "request-list-position")
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = request.requestId,
                    payload = request.payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithTag("share.destinations").performScrollToNode(hasText("Person 12"))
        composeRule.onNodeWithText("Person 12").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Person 12").assertIsDisplayed()
    }

    @Test
    fun aNewRequestIdentityResetsListPosition() {
        val chats =
            (0 until 20).map { index ->
                hexId(0x20 + index) to hexId(0x40 + index)
            }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) ->
                    peerId to profile(displayName = "Person $index")
                }.toMap(mutableMapOf())
        val appState = appStateWithDirectChats(*chats.toTypedArray(), profiles = profiles)
        val requestId = mutableStateOf("request-list-1")

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = requestId.value,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithTag("share.destinations").performScrollToNode(hasText("Person 12"))
        composeRule.onNodeWithText("Person 12").assertIsDisplayed()

        composeRule.runOnIdle { requestId.value = "request-list-2" }

        composeRule.onNodeWithText("Person 0").assertIsDisplayed()
    }

    @Test
    fun aNewRequestIdentityClearsThePreviousQueryAndSelection() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)
        val request = mutableStateOf(ShareRequest(payload, shortcutId = null, requestId = "request-8"))

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = request.value.requestId,
                    payload = request.value.payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithText("Alice").performClick()
        composeRule
            .onNodeWithText(app.getString(R.string.share_search_chats))
            .performClick()
            .performTextInput("Nobody")

        composeRule.runOnIdle { request.value = request.value.copy(requestId = "request-9") }

        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share)).assertIsNotEnabled()
    }

    @Test
    fun closeAndRecipientSelectionExposeAccessibleSemantics() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).assertIsDisplayed()
        composeRule.onNodeWithText("Alice").performClick().assertIsSelected()
    }

    /** Checks the selected destination snapshot and single dismissal after successful staging. */
    @Test
    fun primaryActionStagesEverySelectedConversationAndDismissesOnce() {
        val profiles =
            mutableMapOf(
                PEER_A to profile(displayName = "Alice"),
                PEER_B to profile(displayName = "Bob"),
            )
        val appState =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                GROUP_B to PEER_B,
                profiles = profiles,
            )
        var staged = emptyList<String>()
        var dismissCount = 0

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = { dismissCount++ },
                    onStage = { _, groupIds ->
                        staged = groupIds
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).performClick()
        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Bob").performClick()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 2, 2))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(GROUP_A, GROUP_B), staged)
            assertEquals(1, dismissCount)
        }
        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).assertIsNotFocused()
    }

    /** Delays dismissal after acceptance to verify a second primary action cannot restage the same request. */
    @Test
    fun successfulStageCannotRepeatWhilePickerDismissalIsPending() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)
        var commits = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = { dismissals++ },
                    onStage = { _, _ ->
                        commits++
                        true
                    },
                )
            }
        }
        composeRule.onNodeWithText("Alice").performClick()
        val action = app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1)
        composeRule.onNodeWithText(action).performClick()
        composeRule.runOnIdle { assertEquals(1, commits) }
        // Keep the old UI mounted to exercise the interval before its route is removed.
        composeRule.onNodeWithText(action).performClick()
        composeRule.runOnIdle {
            assertEquals(1, commits)
            assertEquals(1, dismissals)
        }
    }

    /** Rejects staging and preserves the picker rather than acknowledging or discarding the request. */
    @Test
    fun rejectedStageKeepsThePickerOpenForRecovery() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)
        var dismissCount = 0

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = { dismissCount++ },
                    onStage = { _, _ -> false },
                )
            }
        }

        composeRule.onNodeWithText("Alice").performClick()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .performClick()

        composeRule.runOnIdle { assertEquals(0, dismissCount) }
        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.no_share_target_available)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun primaryActionAndResultsStayVisibleAboveImeInsets() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }
        composeRule.onNodeWithText("Alice").performClick()
        val actionLabel = app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1)
        val actionBeforeIme =
            composeRule
                .onNodeWithText(actionLabel)
                .fetchSemanticsNode()
                .boundsInRoot
        val rootBottom =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom

        dispatchImeBottom(300)

        val actionAfterIme =
            composeRule
                .onNodeWithText(actionLabel)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Primary action must move above the IME", actionAfterIme.bottom < actionBeforeIme.bottom - 250f)
        assertTrue("Primary action must remain within the visible viewport", actionAfterIme.bottom < rootBottom)
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).assertIsDisplayed()
        dispatchImeBottom(0)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun compactLandscapeAtLargeFontKeepsSearchResultsAndPrimaryActionVisible() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme(darkTheme = true) {
                    ShareChatPickerFullScreenContent(
                        appState = appState,
                        payload = payload,
                        onDismiss = {},
                        onStage = { _, _ -> true },
                    )
                }
            }
        }
        val destinations = composeRule.onNodeWithTag("share.destinations")
        destinations.performScrollToNode(hasText("Alice"))
        composeRule.onNodeWithText("Alice").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .assertIsDisplayed()
        destinations.performScrollToNode(hasSetTextAction())
        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).assertIsDisplayed()
    }

    /** Delivers a window-inset change with the IME occupying [bottomPx] from the bottom. */
    private fun dispatchImeBottom(bottomPx: Int) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** A 64-character hex identifier built from one repeated byte. */
    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)

    /** A presented group row outside the retained window whose prepared title is [title]. */
    private fun presentedGroup(
        groupIdHex: String,
        title: String,
    ): PresentedChatRowFfi =
        presentedRow(groupIdHex).let { presented ->
            presented.copy(
                row = presented.row.copy(title = title, groupName = title),
                presentation = presented.presentation.copy(title = PresentationTextFfi.Literal(title)),
            )
        }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShareChatPickerLandscapeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val payload = SharePayload("shared text", emptyList(), "text/plain")

    @Test
    @Config(sdk = [36], qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeCanScrollToSelectAWholeRecipientAndReturnToSearch() {
        val chats = (0 until 12).map { hexId(0x20 + it) to hexId(0x40 + it) }
        val profiles = chats.mapIndexed { index, (_, peer) -> peer to profile(displayName = "Person $index") }
        val appState = appStateWithDirectChats(*chats.toTypedArray(), profiles = profiles.toMap(mutableMapOf()))
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { _, _ -> true },
                )
            }
        }
        val destinations = composeRule.onNodeWithTag("share.destinations")
        val recipientRow = hasText("Person 11") and !hasSetTextAction()
        val viewport = destinations.fetchSemanticsNode().boundsInRoot
        assertTrue("Short landscape must retain room for whole touch targets", viewport.height >= 128f)
        destinations.performScrollToNode(recipientRow)
        val recipient = composeRule.onNode(recipientRow).assertIsDisplayed()
        val bounds = recipient.fetchSemanticsNode().boundsInRoot
        assertTrue("Recipient title must fit inside the scroll viewport", bounds.top >= viewport.top)
        assertTrue("Recipient must remain above the footer", bounds.bottom <= viewport.bottom)
        recipient.performClick().assertIsSelected()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .assertIsDisplayed()
        destinations.performScrollToNode(hasSetTextAction())
        composeRule.onNode(hasSetTextAction()).performTextInput("Person 11")
        destinations.performScrollToNode(recipientRow)
        composeRule.onNode(recipientRow).assertIsDisplayed().assertIsSelected()
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
