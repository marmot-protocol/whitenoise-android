package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
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
                    onStage = {},
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
                    onStage = { staged = it },
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
                    onStage = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Person 12"))
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
                    onStage = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Person 12"))
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
                    onStage = {},
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
                    onStage = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(app.getString(R.string.close)).assertIsDisplayed()
        composeRule.onNodeWithText("Alice").performClick().assertIsSelected()
    }

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
                    onStage = { staged = it },
                )
            }
        }

        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Bob").performClick()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 2, 2))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(GROUP_A, GROUP_B), staged)
            assertEquals(1, dismissCount)
        }
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
                    onStage = {},
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
                        onStage = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Alice").performClick()

        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).assertIsDisplayed()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .assertIsDisplayed()
    }

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

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
