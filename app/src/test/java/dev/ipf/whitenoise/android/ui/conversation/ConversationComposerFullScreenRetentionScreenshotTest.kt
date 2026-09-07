package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties.EditableText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.RetainedComposerExpansion
import dev.ipf.whitenoise.android.state.RetainedComposerExpansionMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_INDICATOR_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production re-entry evidence for retained full-screen geometry under dense, narrow RTL layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-xhdpi")
class ConversationComposerFullScreenRetentionScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ConversationController? = null
    private var conversationVisible by mutableStateOf(true)

    /** Cancels the fixture's conversation-owned work even when an assertion fails. */
    @After
    fun releaseController() {
        controller?.onCleared()
    }

    /** Restores the selected full-screen mode and same draft after actual composition disposal. */
    @Test
    fun fullScreenReentryKeepsTheExactOwnerAndFitsNarrowLargeTypeRtl() {
        val draft = "Retained draft " + "keeps the selected full-screen editor readable. ".repeat(12)
        val appState = createAppState(draft)
        val chat = createConversation(appState)
        mountConversation(appState, chat)
        composeRule.waitForIdle()
        assertEquals(2f, context.resources.displayMetrics.density, 0.01f)
        val automaticHeight = composerHeight()

        composeRule.onNodeWithContentDescription(context.getString(R.string.composer_resize)).performClick()
        composeRule.waitForIdle()
        assertFullScreenOwner(appState, draft)
        val fullScreenHeight = composerHeight()
        assertTrue("full-screen mode must exceed automatic height", fullScreenHeight > automaticHeight + 1f)

        composeRule.runOnUiThread { conversationVisible = false }
        composeRule.onNodeWithTag(CHAT_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).assertDoesNotExist()
        composeRule.runOnUiThread { conversationVisible = true }
        composeRule.waitForIdle()

        awaitResizeHandleAfterReentry()
        assertFullScreenOwner(appState, draft)
        assertEquals(fullScreenHeight, composerHeight(), 1f)
        assertInsideViewport()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/conversation_composer_full_screen_retained_reentry_amoled_rtl_xhdpi.png",
        )
    }

    /** Mounts the real route with framework density retained and large-text RTL presentation. */
    private fun mountConversation(
        appState: WhiteNoiseAppState,
        chat: ChatListItem,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    if (conversationVisible) {
                        ConversationScreen(
                            appState = appState,
                            chat = chat,
                            controller = checkNotNull(controller),
                            onBack = { conversationVisible = false },
                        )
                    } else {
                        Text("Chat list", modifier = Modifier.testTag(CHAT_LIST_TAG))
                    }
                }
            }
        }
    }

    /** Verifies exact retained ownership, authoritative draft rendering, and an accessible collapse action. */
    private fun assertFullScreenOwner(
        appState: WhiteNoiseAppState,
        draft: String,
    ) {
        assertTrue(checkNotNull(controller).matchesConversation(ACCOUNT_A, GROUP_A))
        assertEquals(
            RetainedComposerExpansion(RetainedComposerExpansionMode.FullScreen, manualHeightDp = null),
            appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A),
        )
        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_B, GROUP_A))
        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_B))
        val editable =
            composeRule
                .onNode(hasSetTextAction())
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
        assertEquals(draft, editable?.text)
        val resizeHandle = composeRule.onNodeWithContentDescription(context.getString(R.string.composer_resize))
        resizeHandle.assertIsDisplayed()
        assertEquals(
            context.getString(R.string.composer_collapse),
            resizeHandle.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
    }

    /** Waits for the delayed re-entry handle and verifies its visible indicator geometry. */
    private fun awaitResizeHandleAfterReentry() {
        val description = context.getString(R.string.composer_resize)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1 &&
                composeRule
                    .onAllNodesWithTag(COMPOSER_RESIZE_INDICATOR_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size == 1
        }
        val handle = composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        val indicator =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_INDICATOR_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
        val handleBounds = handle.getUnclippedBoundsInRoot()
        val indicatorBounds = indicator.getUnclippedBoundsInRoot()
        assertEquals(96.dp, handleBounds.right - handleBounds.left)
        assertEquals(48.dp, handleBounds.bottom - handleBounds.top)
        assertEquals(36.dp, indicatorBounds.right - indicatorBounds.left)
        assertEquals(4.dp, indicatorBounds.bottom - indicatorBounds.top)
        assertEquals(handleBounds.left + handleBounds.right, indicatorBounds.left + indicatorBounds.right)
        assertTrue(indicatorBounds.top >= handleBounds.top && indicatorBounds.bottom <= handleBounds.bottom)
    }

    /** Measures real pixels so the re-entry equality does not depend on assumed dp density. */
    private fun composerHeight(): Float =
        composeRule
            .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.height

    /** Requires a visible full-screen editor contained by the current production window. */
    private fun assertInsideViewport() {
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val composer = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(composer.height > viewport.height / 2f)
        assertTrue(composer.top >= viewport.top && composer.bottom <= viewport.bottom)
        assertTrue(composer.left >= viewport.left && composer.right <= viewport.right)
    }

    /** Keeps draft content in the production draft owner while providing two independent test accounts. */
    private fun createAppState(draft: String): WhiteNoiseAppState {
        val drafts = DraftStore(NoOpDraftPersistence())
        drafts.set(ACCOUNT_A, GROUP_A, TextFieldValue(draft))
        return WhiteNoiseAppState(
            context = context,
            draftStore = drafts,
            accountIdHexResolver = { ref ->
                when (ref) {
                    ACCOUNT_A -> ACCOUNT_A_ID
                    ACCOUNT_B -> ACCOUNT_B_ID
                    else -> null
                }
            },
            accounts = listOf(account(ACCOUNT_A, ACCOUNT_A_ID), account(ACCOUNT_B, ACCOUNT_B_ID)),
            activeAccountRef = ACCOUNT_A,
        )
    }

    /** Seeds authoritative membership and an empty timeline without starting native subscriptions. */
    private fun createConversation(appState: WhiteNoiseAppState): ChatListItem {
        val group = conversationTimelineTestGroup().copy(groupIdHex = GROUP_A, admins = listOf(ACCOUNT_A_ID))
        val members =
            GroupMemberSnapshot(
                listOf(AppGroupMemberRecordFfi(memberIdHex = ACCOUNT_A_ID, account = ACCOUNT_A, local = true)),
            )
        controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                accountRefOverride = ACCOUNT_A,
                startOnConstruction = false,
                markdownParser = { EMPTY_MARKDOWN_DOCUMENT },
            ).also { it.markAuthoritativeTimelinePublishedForTest() }
        return ChatListItem(
            group = group,
            latest = null,
            otherMemberAccount = null,
            memberCount = 1,
            memberSnapshot = members,
        )
    }

    /** Creates a local synthetic account with an explicit matching native identifier. */
    private fun account(
        label: String,
        id: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Avoids external persistence while the real DraftStore owns the test's draft. */
    private class NoOpDraftPersistence : DraftPersistence {
        /** Starts with no persisted draft before the explicit fixture seed. */
        override fun read(): Map<String, String> = emptyMap()

        /** Leaves synthetic draft writes in the production in-memory owner for this test. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_A_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val CHAT_LIST_TAG = "composer.full_screen_reentry.chat_list"
    }
}
