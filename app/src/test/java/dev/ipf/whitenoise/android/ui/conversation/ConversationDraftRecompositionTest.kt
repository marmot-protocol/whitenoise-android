package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.audio.ConversationDictationKey
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Typing and cursor updates in the composer must never recompose [ConversationScreen], while
 * drafts hydrated after mount through storage restore, inbound share or dictation still fill the
 * mounted composer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDraftRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null
    private var appState: WhiteNoiseAppState? = null

    /** Removes any active composition tracer and cancels conversation and app-state jobs. */
    @After
    @OptIn(InternalComposeTracingApi::class)
    fun releaseFixture() {
        Composer.setTracer(null)
        controller?.onCleared()
        appState
            ?.mutationsScope
            ?.coroutineContext
            ?.get(Job)
            ?.cancel()
    }

    /**
     * Typing characters and moving the cursor do not recompose the ConversationScreen body. The cursor
     * move is driven as the `setDraft(textChanged = false)` store write a selection change produces,
     * not as a selection gesture through the field.
     */
    @Test
    fun typingAndCursorMovesDoNotRecomposeConversationScreenBody() {
        val conversation = mountScreen()
        val accountRef = requireNotNull(conversation.boundAccountRef)
        val groupIdHex = conversation.group.groupIdHex
        composeRule.onNode(hasSetTextAction()).performTextInput("a")
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        settleCompositions(SCREEN_SOURCE)

        val traceCounts = mutableMapOf<String, Int>()
        val screenRuns =
            countCompositions(SCREEN_SOURCE, traceCounts) {
                for (letter in listOf("b", "c", "d", "e")) {
                    composeRule.onNode(hasSetTextAction()).performTextInput(letter)
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                }
                composeRule.runOnIdle {
                    conversation.appState.setDraft(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        value = TextFieldValue("abcde", TextRange(2)),
                        textChanged = false,
                    )
                }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }

        assertEquals("ConversationScreen.kt recomposed while typing: $traceCounts", 0, screenRuns)
        assertEquals(
            TextFieldValue("abcde", TextRange(2)),
            conversation.appState.draftSnapshotFor(accountRef, groupIdHex)?.textFieldValue,
        )
    }

    /** Late storage restore, inbound share and dictation hydration still populate the mounted composer. */
    @Test
    fun draftHydratedAfterMountPopulatesComposerAndKeepsTypingScoped() {
        val gateway = ControllableDraftGateway()
        val conversation = mountScreen(gateway)
        val accountRef = requireNotNull(conversation.boundAccountRef)
        val groupIdHex = conversation.group.groupIdHex

        composeRule.runOnIdle {
            gateway.current = MessageDraftFfi(groupIdHex, "Loaded after mount", null, emptyList(), 1_000L, 1_000L)
            conversation.appState.loadDraft(accountRef, groupIdHex)
        }
        awaitComposerText("Loaded after mount")

        composeRule.runOnIdle {
            conversation.appState.draftStore.hydrate(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                content = "Shared after mount",
                draftedAtMs = 2_000L,
                replaceExisting = true,
            )
            conversation.appState.shareStaging.notifyTextStaged()
        }
        awaitComposerText("Shared after mount")

        composeRule.runOnIdle {
            conversation.appState.draftStore.set(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                value = TextFieldValue("Dictated after mount", TextRange(20)),
            )
            val key = ConversationDictationKey.from(accountRef, groupIdHex)
            val revisions = conversation.appState.conversationDictation.completionRevisions
            revisions[key] = (revisions[key] ?: 0) + 1
        }
        awaitComposerText("Dictated after mount")

        composeRule.onNode(hasSetTextAction()).performTextInput("!")
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        settleCompositions(SCREEN_SOURCE)

        val traceCounts = mutableMapOf<String, Int>()
        val runsAfterHydration =
            countCompositions(SCREEN_SOURCE, traceCounts) {
                composeRule.onNode(hasSetTextAction()).performTextInput("?")
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
        assertEquals("ConversationScreen.kt re-subscribed after hydration: $traceCounts", 0, runsAfterHydration)
    }

    /** Waits until the composer field renders [expected] text. */
    private fun awaitComposerText(expected: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(expected).assertExists()
    }

    /** Builds a started ConversationController over two scripted messages. */
    private fun createConversationController(gateway: ControllableDraftGateway?): ConversationController {
        val group = conversationTimelineTestGroup()
        val records =
            listOf(
                timelineRecord("a1".repeat(32), 1uL, "First message"),
                timelineRecord("a2".repeat(32), 2uL, "Second message"),
            )
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val state =
            if (gateway == null) {
                conversationTimelineTestAppState(scripted.subscriptions)
            } else {
                appStateWithDraftGateway(scripted, gateway)
            }
        appState = state
        val conversation =
            ConversationController(
                appState = state,
                initialGroup = group,
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        controller = conversation
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /** Mounts the production ConversationScreen backed by scripted timeline subscriptions. */
    private fun mountScreen(gateway: ControllableDraftGateway? = null): ConversationController {
        val conversation = createConversationController(gateway)
        val group = conversation.group
        val latest = conversation.timeline.last().record
        val projection =
            chatRow(group.groupIdHex).copy(
                groupName = group.name,
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = latest.messageIdHex,
                lastReadTimelineAt = latest.recordedAt,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = conversation.appState,
                    chat =
                        ChatListItem(
                            group = group,
                            latest = null,
                            otherMemberAccount = null,
                            memberCount = 1,
                            memberSnapshot = conversationTimelineMemberSnapshot(),
                            projection = projection,
                        ),
                    controller = conversation,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag("performance.conversation_transcript_visible")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        return conversation
    }

    /** Builds a test app state wired to a controllable [MessageDraftGateway] for late loadDraft tests. */
    private fun appStateWithDraftGateway(
        scripted: ScriptedConversationLiveSubscriptions,
        gateway: ControllableDraftGateway,
    ): WhiteNoiseAppState {
        val repository = MessageDraftRepository(gateway, EditorSessionStore(EmptyEditorStrings), Dispatchers.Unconfined)
        return WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(InMemoryDraftPersistence),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            messageDraftRepository = repository,
        ).also { state ->
            state.liveSubscriptionOverrides.conversation = scripted.subscriptions
        }
    }

    /** Advances frames until [QUIET_FRAMES] consecutive frames run zero scopes matching [source]. */
    private fun settleCompositions(source: String) {
        var quiet = 0
        repeat(MAX_SETTLE_FRAMES) {
            Thread.sleep(SETTLE_PAUSE_MS)
            val runs =
                countCompositions(source) {
                    composeRule.runOnIdle { }
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                }
            quiet = if (runs == 0) quiet + 1 else 0
            if (quiet == QUIET_FRAMES) return
        }
        fail("$source never stopped recomposing after the screen was mounted")
    }

    /** Counts composable scopes whose source location contains [source] during [block]. */
    @OptIn(InternalComposeTracingApi::class)
    private fun countCompositions(
        source: String,
        traceCounts: MutableMap<String, Int>? = null,
        block: () -> Unit,
    ): Int {
        var runs = 0
        Composer.setTracer(
            object : CompositionTracer {
                /** Traces every composable scope while installed. */
                override fun isTraceInProgress() = true

                /** Records scopes originating in [source]. */
                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    if (source in info) {
                        runs++
                        traceCounts?.let { it[info] = it.getOrDefault(info, 0) + 1 }
                    }
                }

                /** Scope exits need no bookkeeping. */
                override fun traceEventEnd() = Unit
            },
        )
        try {
            block()
        } finally {
            Composer.setTracer(null)
        }
        return runs
    }

    private companion object {
        const val SCREEN_SOURCE = "(ConversationScreen.kt:"
        const val QUIET_FRAMES = 4
        const val MAX_SETTLE_FRAMES = 80
        const val SETTLE_PAUSE_MS = 25L
    }
}

/** In-memory editor persistence avoiding Android keystore dependencies in Robolectric. */
private object EmptyEditorStrings : EditorStringStore {
    /** Returns no persisted editor entries. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts replacement writes in memory without disk I/O. */
    override fun replaceAll(values: Map<String, String>) = true

    /** No-op clear for the empty test store. */
    override fun clear() = Unit
}

/** No-op draft persistence keeping test drafts strictly in memory. */
private object InMemoryDraftPersistence : DraftPersistence {
    /** Returns no persisted drafts at startup. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores persistence writes in the in-memory test store. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/** Controllable native draft gateway returning [current] when loadDraft reads from storage. */
private class ControllableDraftGateway : MessageDraftGateway {
    var current: MessageDraftFfi? = null

    /** Returns the currently scripted draft for [groupIdHex], if any. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current?.takeIf { it.groupIdHex == groupIdHex }

    /** Stores and returns the saved draft snapshot. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 1L)
            .also { current = it }

    /** Clears the scripted draft for [groupIdHex]. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (current?.groupIdHex == groupIdHex) current = null
    }

    /** Returns no summary entries for this single-conversation test. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}
