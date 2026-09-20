package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.window.OnBackInvokedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties.EditableText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.RetainedComposerExpansion
import dev.ipf.whitenoise.android.state.RetainedComposerExpansionMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.state.notifiedMessagePreview
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_GESTURE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_INDICATOR_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
@Suppress("LargeClass") // Production-route screenshot cases share one deterministic app/controller fixture.
class ConversationComposerExpansionRetentionScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controllers = mutableListOf<ConversationController>()

    /** A live drag uses its compact minimum; cancellation keeps the original retained owner unchanged. */
    @Test
    fun heldDragCanCrossTheLegacyManualMinimumWithoutPublishingIt() {
        val owner = appState()
        val full = RetainedComposerExpansion(RetainedComposerExpansionMode.FullScreen, null)
        owner.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            full,
            draftGeneration = owner.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).height(720.dp)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue("Short draft"),
                            draftKey = GROUP_A,
                            draftAccountRef = ACCOUNT_A,
                            draftGroupIdHex = GROUP_A,
                            appState = owner,
                            modifier = Modifier.testTag(COMPOSER),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val fullHeight = composerHeight()
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, fullHeight - 60f), delayMillis = 1_000)
        }
        composeRule.waitForIdle()
        assertTrue("held drag must not plateau at the legacy144dp minimum", composerHeight() < 144f)
        assertEquals(full, owner.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A))
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true).performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals(fullHeight, composerHeight(), 1f)
        assertEquals(full, owner.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A))
        composeRule.onNodeWithText("Short draft").assertExists()
    }

    /**
     * A drag released between the endpoints keeps the height it was let go at.
     *
     * Every release used to settle at the draft's natural height or full screen, so a reader could drag
     * the border anywhere but could not leave it there. The capture is the settled composer, which is
     * the only thing a screenshot can show about a gesture that has already ended.
     */
    @Test
    fun releasedDragKeepsItsOwnHeightOnTheProductionRoute() {
        val draftStore = DraftStore(InMemoryDraftPersistence())
        val appState = appState(draftStore)
        draftStore.set(ACCOUNT_A, GROUP_A, TextFieldValue(longDraft("free resize")))
        val fixture = productionConversation(appState, ACCOUNT_A, GROUP_A)
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat = fixture.chat,
                    controller = fixture.controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        val automaticHeight = productionComposerPillContentHeight()
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -160f), delayMillis = 200)
            up()
        }
        composeRule.waitForIdle()

        val settled = productionComposerPillContentHeight()
        assertTrue(
            "a release between the endpoints must keep its own height, not fall back to $automaticHeight",
            settled > automaticHeight + 1f,
        )
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/conversation_composer_free_released_height_light.png",
        )
    }

    /** Releases all production fixture owners after the test, including failed assertion paths. */
    @After
    fun releaseControllers() {
        controllers.forEach { it.onCleared() }
    }

    /**
     * Exercises the low-level composer owner contract across route disposal,
     * focus dismissal, account switches, and both explicit expansion modes.
     */
    @Suppress("LongMethod") // One route keeps the exact captured heights comparable across every owner transition.
    @Test
    fun expandedDraftSurvivesReentryAndRemainsIsolatedByConversationAndAccount() {
        val appState = appState()
        appState.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            manualPreference(240f),
            draftGeneration = appState.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        var route by mutableStateOf(Route(ACCOUNT_A, GROUP_A))
        var overlayCallback: OnBackInvokedCallback? = null
        val overlayRegistrar =
            ComposerOverlayBackRegistrar { _, callback ->
                overlayCallback = callback
                { if (overlayCallback === callback) overlayCallback = null }
            }
        val drafts =
            mapOf(
                (ACCOUNT_A to GROUP_A) to longDraft("A"),
                (ACCOUNT_A to GROUP_B) to longDraft("B"),
                (ACCOUNT_B to GROUP_A) to longDraft("other account"),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    val destination = route
                    BackHandler(enabled = destination != Route.ChatList) { route = Route.ChatList }
                    if (destination == Route.ChatList) {
                        Text(CHAT_LIST, modifier = Modifier.testTag(CHAT_LIST))
                    } else {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                initialDraft =
                                    TextFieldValue(
                                        checkNotNull(drafts[destination.accountRef to destination.groupIdHex]),
                                    ),
                                onDraftChange = {
                                    appState.setDraft(destination.accountRef, destination.groupIdHex, it)
                                },
                                draftKey = destination.groupIdHex,
                                draftAccountRef = destination.accountRef,
                                draftGroupIdHex = destination.groupIdHex,
                                appState = appState,
                                overlayBackRegistrar = overlayRegistrar,
                                modifier = Modifier.testTag(COMPOSER),
                            )
                        }
                    }
                }
            }
        }

        val manualHeight = composerHeight()
        assertEquals("legacy manual geometry restores before the first interaction", 240f, manualHeight, 1f)

        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        editor.assertIsFocused()
        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }
        editor.assertIsNotFocused()
        assertTrue("focused Back should retain manual height", kotlin.math.abs(composerHeight() - manualHeight) <= 1f)

        pressBack()
        composeRule.onNodeWithTag(COMPOSER).assertDoesNotExist()
        composeRule.onNodeWithTag(CHAT_LIST).assertExists()
        composeRule.runOnIdle { route = Route(ACCOUNT_A, GROUP_A) }
        assertTrue("re-entry should retain manual height", kotlin.math.abs(composerHeight() - manualHeight) <= 1f)
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue("refocusing should retain manual height", kotlin.math.abs(composerHeight() - manualHeight) <= 1f)
        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }

        composeRule.runOnIdle { route = Route(ACCOUNT_A, GROUP_B) }
        assertTrue("another conversation should stay automatic", composerHeight() > manualHeight + 64f)

        composeRule.runOnIdle { route = Route(ACCOUNT_B, GROUP_A) }
        assertTrue("another account should stay automatic", composerHeight() > manualHeight + 64f)

        composeRule.runOnIdle { route = Route(ACCOUNT_A, GROUP_A) }
        resizeGesture().performClick()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }
        assertResizeHandleToggleLabel(R.string.composer_collapse)
        pressBack()
        composeRule.onNodeWithTag(COMPOSER).assertDoesNotExist()

        composeRule.runOnIdle { route = Route(ACCOUNT_A, GROUP_A) }
        awaitResizeHandleAfterReentry()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
        pressBack()
        composeRule.onNodeWithTag(COMPOSER).assertDoesNotExist()
    }

    /**
     * A real conversation route must preserve manual geometry when Back first
     * dismisses focus and then disposes/recreates the destination.
     */
    @Test
    fun productionConversationRetainsManualHeightAcrossFocusBackAndReentry() {
        val draftStore = DraftStore(InMemoryDraftPersistence())
        val appState = appState(draftStore)
        val draft = longDraft("production route")
        draftStore.set(ACCOUNT_A, GROUP_A, TextFieldValue(draft))
        appState.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            manualPreference(240f),
            draftGeneration = appState.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        val fixture = productionConversation(appState, ACCOUNT_A, GROUP_A)
        var conversationVisible by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (conversationVisible) {
                    ConversationScreen(
                        appState = appState,
                        chat = fixture.chat,
                        controller = fixture.controller,
                        onBack = { conversationVisible = false },
                    )
                } else {
                    Text(CHAT_LIST, modifier = Modifier.testTag(CHAT_LIST))
                }
            }
        }
        composeRule.waitForIdle()

        val retainedHeight = productionComposerPillContentHeight()
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        editor.assertIsFocused()

        pressBack()

        editor.assertIsNotFocused()
        assertTrue(
            "focus Back must not collapse explicit geometry",
            kotlin.math.abs(productionComposerPillContentHeight() - retainedHeight) <= 1f,
        )
        pressBack()
        composeRule.onNodeWithTag(CHAT_LIST).assertExists()
        composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).assertDoesNotExist()

        composeRule.runOnIdle { conversationVisible = true }

        awaitResizeHandleAfterReentry()
        assertManualRouteReentry(appState, draft, retainedHeight)
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/conversation_composer_manual_retained_reentry_light.png",
        )
    }

    /**
     * Saved-state recreation restores only geometry, then a production screen
     * joins it with the separately restored MDK-owned draft.
     */
    @Test
    fun savedGeometryAndAuthoritativeDraftRenderTogetherAfterOwnerRecreation() {
        val savedState = SavedStateHandle()
        val firstState = appState()
        val firstHolder = MainShellStateHolder(firstState, savedState)
        firstState.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            manualPreference(240f),
            draftGeneration = firstState.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        val savedGeometry = checkNotNull(savedState.get<android.os.Bundle>(SAVED_EXPANSION_KEY))
        firstHolder.release()

        val restoredDraftStore = DraftStore(InMemoryDraftPersistence())
        val restoredState = appState(restoredDraftStore)
        val restoredHolder =
            MainShellStateHolder(
                restoredState,
                SavedStateHandle(mapOf(SAVED_EXPANSION_KEY to savedGeometry)),
            )
        val restoredDraft = longDraft("restored independently from MDK")
        restoredDraftStore.set(ACCOUNT_A, GROUP_A, TextFieldValue(restoredDraft))
        val fixture = productionConversation(restoredState, ACCOUNT_A, GROUP_A)

        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = restoredState,
                    chat = fixture.chat,
                    controller = fixture.controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        awaitResizeHandleAfterReentry()
        composeRule.onNodeWithText(restoredDraft).assertExists()
        val restoredPillHeight = productionComposerPillContentHeight()
        // The composer bar keeps a 6dp vertical inset above and below the pill.
        val expectedPillHeight = 240f - (2f * 6f)
        assertTrue(
            "the restored 240dp owner must leave exact content below its outer insets",
            kotlin.math.abs(restoredPillHeight - expectedPillHeight) <= 2f,
        )
        assertEquals(
            manualPreference(240f),
            restoredState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A),
        )
        restoredHolder.release()
    }

    /**
     * A definite publisher failure after optimistic acceptance restores both
     * the draft field and its exact expansion on the still-mounted screen.
     */
    @Test
    fun productionSendFailureRestoresDraftAndGeometryOnTheMountedRoute() {
        val publisherStarted = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val draftStore = DraftStore(InMemoryDraftPersistence())
        val appState = appState(draftStore)
        val draft = longDraft("terminal failure")
        draftStore.set(ACCOUNT_A, GROUP_A, TextFieldValue(draft))
        appState.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            manualPreference(240f),
            draftGeneration = appState.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        val fixture =
            productionConversation(appState, ACCOUNT_A, GROUP_A) { _, _, _, _ ->
                publisherStarted.complete(Unit)
                releaseFailure.await()
                throw MarmotKitException.InvalidIdentity("synthetic terminal failure")
            }
        showProductionConversation(appState, fixture)
        val retainedHeight = productionComposerPillContentHeight()

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { publisherStarted.isCompleted }

        assertEquals("", productionComposerText())
        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A))

        releaseFailure.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            productionComposerText() == draft &&
                appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A) != null
        }

        assertEquals(draft, productionComposerText())
        assertEquals(
            manualPreference(240f),
            appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A),
        )
        assertTrue(
            "terminal restoration must return to the captured height",
            kotlin.math.abs(productionComposerPillContentHeight() - retainedHeight) <= 1f,
        )
    }

    /**
     * Models the dictation-to-input handoff by growing the bottom input only
     * after the optimistic row is published, then captures the settled result.
     *
     * One paused-clock lifecycle keeps publish, delayed growth, capture, and completion ordered.
     */
    @Test
    @Suppress("LongMethod")
    fun acceptedSendStaysVisibleWhenBottomInputGrowsNextFrame() {
        val publisherStarted = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val draftStore = DraftStore(InMemoryDraftPersistence())
        val appState = appState(draftStore)
        val dictatedDraft = "This dictated message was sent as soon as transcription completed."
        val seededPreview = notifiedMessagePreview()
        assertNotEquals(CONFIRMED_MESSAGE_ID, seededPreview.messageIdHex)
        assertNotEquals(dictatedDraft, seededPreview.plaintext)
        draftStore.set(ACCOUNT_A, DICTATION_GROUP, TextFieldValue(dictatedDraft))
        val fixture =
            productionConversation(
                appState = appState,
                accountRef = ACCOUNT_A,
                groupIdHex = DICTATION_GROUP,
                initialPreview = seededPreview,
                clockMillis = { DICTATION_TIMESTAMP_MILLIS },
            ) { _, _, _, _ ->
                publisherStarted.complete(Unit)
                releaseSuccess.await()
                successfulSendSummary()
            }
        showProductionConversation(appState, fixture)
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(seededPreview.messageIdHex), useUnmergedTree = true)
            .assertIsDisplayed()
        val compactComposerHeight = productionComposerPillContentHeight()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                publisherStarted.isCompleted &&
                    fixture.controller.timeline.any { it.record.plaintext == dictatedDraft }
            }
            val optimisticMessageId =
                composeRule.runOnIdle {
                    fixture.controller.timeline
                        .single { it.record.plaintext == dictatedDraft }
                        .record.messageIdHex
                }
            composeRule.runOnUiThread {
                appState.composerExpansionStateRetention.update(
                    ACCOUNT_A,
                    DICTATION_GROUP,
                    manualPreference(240f),
                    draftGeneration = appState.composerDraftGeneration(ACCOUNT_A, DICTATION_GROUP),
                )
            }
            repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.waitForIdle()

            assertTrue(
                "the synthetic post-send input must grow after the optimistic row is published",
                productionComposerPillContentHeight() > compactComposerHeight + 100f,
            )
            val bubble =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(optimisticMessageId), useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val composer = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "the dictated bubble must settle above the delayed replacement input: $bubble vs $composer",
                bubble.bottom <= composer.top,
            )
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.onRoot().captureRoboImage(
                "src/test/snapshots/conversation_dictated_send_delayed_input_growth_light.png",
            )
        } finally {
            composeRule.mainClock.autoAdvance = true
            releaseSuccess.complete(Unit)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.controller.timeline.any { message ->
                message.record.messageIdHex == CONFIRMED_MESSAGE_ID && message.status == MessageStatus.Sent
            }
        }
    }

    /**
     * A durable callback from an older accepted send cannot delete text or
     * full-screen geometry created while that publish was in flight.
     */
    @Test
    fun lateProductionSendSuccessCannotClearANewerDraftOrExpansion() {
        val publisherStarted = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val draftStore = DraftStore(InMemoryDraftPersistence())
        val appState = appState(draftStore)
        val sentDraft = longDraft("accepted send")
        val newerDraft = longDraft("newer generation")
        draftStore.set(ACCOUNT_A, GROUP_A, TextFieldValue(sentDraft))
        appState.composerExpansionStateRetention.update(
            ACCOUNT_A,
            GROUP_A,
            manualPreference(240f),
            draftGeneration = appState.composerDraftGeneration(ACCOUNT_A, GROUP_A),
        )
        val fixture =
            productionConversation(appState, ACCOUNT_A, GROUP_A) { _, _, _, _ ->
                publisherStarted.complete(Unit)
                releaseSuccess.await()
                successfulSendSummary()
            }
        showProductionConversation(appState, fixture)

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { publisherStarted.isCompleted }
        composeRule.onNode(hasSetTextAction()).performTextReplacement(newerDraft)
        composeRule.waitForIdle()
        resizeGesture().performClick()
        assertResizeHandleToggleLabel(R.string.composer_collapse)

        releaseSuccess.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.controller.timeline.any { message ->
                message.record.messageIdHex == CONFIRMED_MESSAGE_ID &&
                    message.record.plaintext == sentDraft.trim() &&
                    message.status == MessageStatus.Sent
            }
        }

        assertEquals(newerDraft, productionComposerText())
        assertEquals(
            RetainedComposerExpansion(RetainedComposerExpansionMode.FullScreen, manualHeightDp = null),
            appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A),
        )
        assertResizeHandleToggleLabel(R.string.composer_collapse)
    }

    /** Dispatches through the activity so the production BackHandler chain runs. */
    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Pointer input targets the border so taps never reposition the editor caret. */
    private fun resizeGesture() = composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true)

    /** Returns the accessible resize action exposed by the composer pill. */
    private fun resizeHandle() = composeRule.onNodeWithContentDescription(context.getString(R.string.composer_resize))

    /** Verifies the localized tap action exposed by the visible resize handle. */
    private fun assertResizeHandleToggleLabel(labelRes: Int) {
        val label = resizeHandle().fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(context.getString(labelRes), label)
    }

    /** Re-entry restores the accessible surface and border drag target without a visible handle. */
    private fun awaitResizeHandleAfterReentry() {
        val description = context.getString(R.string.composer_resize)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1
        }
        val target =
            composeRule
                .onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val strip =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        assertTrue(target.bottom - target.top >= 48.dp)
        assertEquals(target.left, strip.left)
        assertEquals(target.right, strip.right)
        assertEquals(target.top, strip.top)
        assertEquals(8.dp, strip.bottom - strip.top)
        composeRule.onNodeWithTag(COMPOSER_RESIZE_INDICATOR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Measures the low-level fixture wrapper used by the isolation matrix. */
    private fun composerHeight(): Float =
        composeRule
            .onNodeWithTag(COMPOSER)
            .fetchSemanticsNode()
            .boundsInRoot.height

    /** Measures the tagged pill rendered by the production conversation route. */
    private fun productionComposerPillContentHeight(): Float =
        composeRule
            .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.height

    /** Checks the restored draft, exact geometry owner, and viewport before its visual baseline. */
    private fun assertManualRouteReentry(
        appState: WhiteNoiseAppState,
        draft: String,
        retainedHeight: Float,
    ) {
        assertEquals(draft, productionComposerText())
        assertEquals(manualPreference(240f), appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_B, GROUP_A))
        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_A, GROUP_B))
        assertTrue(kotlin.math.abs(productionComposerPillContentHeight() - retainedHeight) <= 1f)
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val composer = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(composer.top >= viewport.top && composer.bottom <= viewport.bottom)
        assertTrue(composer.left >= viewport.left && composer.right <= viewport.right)
    }

    /** Reads only editable semantics so an optimistic bubble cannot satisfy a draft assertion. */
    private fun productionComposerText(): String? =
        composeRule
            .onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config
            .getOrNull(EditableText)
            ?.text

    /** Builds an account-aware production state with an injectable draft cache. */
    private fun appState(draftStore: DraftStore = DraftStore(InMemoryDraftPersistence())): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = draftStore,
            accountIdHexResolver = { ref ->
                when (ref) {
                    ACCOUNT_A -> ACCOUNT_A_ID
                    ACCOUNT_B -> ACCOUNT_B_ID
                    else -> null
                }
            },
            accounts = listOf(account(ACCOUNT_A, 'a'), account(ACCOUNT_B, 'b')),
            activeAccountRef = ACCOUNT_A,
        )

    /** Builds the account record whose id also seeds the matching roster. */
    private fun account(
        label: String,
        idCharacter: Char,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = idCharacter.toString().repeat(64),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Keeps fixture text long enough to surface resize semantics. */
    private fun longDraft(owner: String): String {
        val prefix = "Draft $owner "
        return prefix + "keeps enough text to expose the accessible resize handle. ".repeat(12)
    }

    /** Produces the retained representation of a manual dp height. */
    private fun manualPreference(heightDp: Float) =
        RetainedComposerExpansion(
            mode = RetainedComposerExpansionMode.Manual,
            manualHeightDp = heightDp,
        )

    /**
     * Creates a real controller/chat pair whose authoritative member seed
     * makes the production composer available without engine subscriptions.
     */
    private fun productionConversation(
        appState: WhiteNoiseAppState,
        accountRef: String,
        groupIdHex: String,
        initialPreview: ChatListMessagePreviewFfi? = null,
        clockMillis: () -> Long = System::currentTimeMillis,
        textPublisher: suspend (String?, String, String, String) -> SendSummaryFfi =
            { _, _, _, _ -> successfulSendSummary() },
    ): ProductionConversation {
        val accountIdHex = if (accountRef == ACCOUNT_A) ACCOUNT_A_ID else ACCOUNT_B_ID
        val group = conversationTimelineTestGroup().copy(groupIdHex = groupIdHex, admins = listOf(accountIdHex))
        val memberSnapshot =
            GroupMemberSnapshot(
                listOf(
                    AppGroupMemberRecordFfi(
                        memberIdHex = accountIdHex,
                        account = accountRef,
                        local = true,
                    ),
                ),
            )
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = memberSnapshot,
                initialTimelinePreview = initialPreview,
                accountRefOverride = accountRef,
                startOnConstruction = false,
                clockMillis = clockMillis,
                textPublisher = textPublisher,
                markdownParser = { EMPTY_MARKDOWN_DOCUMENT },
            ).also { controllers += it }
        controller.markAuthoritativeTimelinePublishedForTest()
        return ProductionConversation(
            chat =
                ChatListItem(
                    group = group,
                    latest = null,
                    otherMemberAccount = null,
                    memberCount = 1,
                    memberSnapshot = memberSnapshot,
                    projection =
                        initialPreview?.let { preview ->
                            notificationChatListRow().copy(
                                groupIdHex = groupIdHex,
                                lastMessage = preview,
                                unreadCount = 0uL,
                                hasUnread = false,
                                firstUnreadMessageIdHex = null,
                                lastReadMessageIdHex = preview.messageIdHex,
                                lastReadTimelineAt = preview.timelineAt,
                            )
                        },
                ),
            controller = controller,
        )
    }

    /** Mounts the real route used by send lifecycle assertions. */
    private fun showProductionConversation(
        appState: WhiteNoiseAppState,
        fixture: ProductionConversation,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat = fixture.chat,
                    controller = fixture.controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Builds a typed durable send result for stale-callback coverage. */
    private fun successfulSendSummary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf(CONFIRMED_MESSAGE_ID),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    /** No-op backing store because production persistence remains MDK-owned. */
    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** Low-level destination identity used to assert cross-owner isolation. */
    private data class Route(
        val accountRef: String,
        val groupIdHex: String,
    ) {
        companion object {
            val ChatList = Route("", "")
        }
    }

    /** Production screen inputs kept together so ownership cannot drift in tests. */
    private data class ProductionConversation(
        val chat: ChatListItem,
        val controller: ConversationController,
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_A_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        val DICTATION_GROUP = "d4".repeat(32)
        const val DICTATION_TIMESTAMP_MILLIS = 1_700_000_000_000L
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
        const val CHAT_LIST = "Chat list"
        const val COMPOSER = "retained-composer"
        const val SAVED_EXPANSION_KEY = "main_shell_composer_expansion"
    }
}
