package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.PublicEventPreviewFfi
import dev.ipf.marmotkit.PublicEventPreviewStateFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.NostrEventReferenceDecoder
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberMessageMarkdownDocumentForDisplayedBody
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.eventCardDocument
import dev.ipf.whitenoise.android.ui.conversation.nostr.noteBech32
import dev.ipf.whitenoise.android.ui.conversation.nostr.readCachedPublicEventCards
import dev.ipf.whitenoise.android.ui.conversation.nostr.refreshPublicEventCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real conversation controller over a scripted window whose messages may name one previously resolved event card,
 * with native Markdown and a cached-card read that runs [cacheRead] first (#2238). [anchorPage] is the window MDK
 * answers a visible-row report with, [jumpOutcomes] its answers to exact-message jumps, and [seedOptimistic] puts the
 * reader's in-flight sends in place before the controller starts. [profileRead] runs before MDK's local profile read
 * answers, so a test can keep the first presentation unprepared. [compose] draws the controller's published rows the
 * way the conversation screen does and records every card state that composes.
 */
internal class EventCardConversationFixture(
    private val composeRule: ComposeContentTestRule,
    snapshotPage: TimelinePageFfi,
    backwardsPage: TimelinePageFfi = timelinePage(),
    anchorPage: TimelinePageFfi? = null,
    jumpOutcomes: MutableList<ConversationJumpOutcome> = mutableListOf(),
    seedOptimistic: (WhiteNoiseAppState) -> Unit = {},
    private val cacheRead: () -> Unit,
    private val profileRead: () -> Unit = {},
) {
    val cacheReads = AtomicInteger()

    /** How many times MDK parsed a message naming a `nostr:` card, so a test can tell a parse was not repeated. */
    val cardParses = AtomicInteger()
    val composed: MutableList<NostrEventCardState> = Collections.synchronizedList(mutableListOf())

    /** Every distinct row order the screen composed, oldest first, so a test can see what the reader saw. */
    val composedOrders: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    /**
     * Which published rows the screen composes, as a scrolled list composes only the rows in view. Every row by
     * default; a test narrows it to model rows off screen and widens it to scroll them in.
     */
    var composesRow: (String) -> Boolean by mutableStateOf({ _: String -> true })
    private val scripts =
        ScriptedConversationLiveSubscriptions(
            timelineScripts =
                listOf(
                    ScriptedConversationTimelineSubscription(
                        snapshotPage,
                        backwardsPage = backwardsPage,
                        anchorPage = anchorPage,
                        jumpOutcomes = jumpOutcomes,
                    ),
                ),
            group = conversationTimelineTestGroup(),
        )
    private val nativeExecutor = Executors.newFixedThreadPool(NATIVE_THREADS)
    val appState =
        conversationTimelineTestAppState(
            liveSubscriptions = scripts.subscriptions,
            marmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = runtime()),
            marmotIoDispatcher = nativeExecutor.asCoroutineDispatcher(),
        )

    init {
        seedOptimistic(appState)
    }

    val controller =
        ConversationController(
            appState = appState,
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            startOnConstruction = true,
        )

    /** The conversation's one scripted window, for a test that delivers live windows to it. */
    val timelineScript: ScriptedConversationTimelineSubscription
        get() = scripts.timelineScripts.first()

    /** True once the conversation is ready and a card row has composed. */
    fun openedWithACard(): Boolean = controller.hasPreparedInitialPresentation && composed.isNotEmpty()

    /** The stages a page passes, for a stalled wait's message. */
    fun describeState(): String =
        "holding=${controller.isHoldingInitialPageForEventCards} " +
            "published=${controller.hasPublishedAuthoritativeTimeline} " +
            "prepared=${controller.hasPreparedInitialPresentation} " +
            "rows=${controller.timeline.map { it.id.take(2) }} cacheReads=${cacheReads.get()} composed=${composed.size}"

    /** Composes the controller's published rows as the conversation screen does. */
    fun compose() {
        composeRule.setContent {
            WhiteNoiseTheme { PublishedCardRows(appState, controller, composed, composedOrders, composesRow) }
        }
    }

    /**
     * Idles the main looper until [condition] holds, without moving its clock, so only real work settles it. A timeout
     * reports this fixture's state, so a hang names the stage it stopped at.
     */
    fun awaitIdleCondition(condition: () -> Boolean) {
        try {
            composeRule.waitUntil(WAIT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                // Controller writes happen outside composition. After many earlier Robolectric tests in the same JVM
                // the global apply observer may not be scheduled on this test's looper, so flush them explicitly.
                Snapshot.sendApplyNotifications()
                condition()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("stalled at ${describeState()}", timeout)
        }
        composeRule.waitForIdle()
    }

    /** Releases the controller, its scripted window and its native threads. */
    fun close() {
        controller.onCleared()
        awaitOpenedTimelineSubscriptionsClosed(scripts)
        nativeExecutor.shutdownNow()
    }

    /**
     * Answers Markdown and the cached card natively, runs [profileRead] before failing the local profile read, and
     * fails every other native call as unavailable.
     */
    private fun runtime(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name.substringBefore('-')) {
                "parseMarkdown" -> {
                    val text = args!![0] as String
                    if ("nostr:" in text) cardParses.incrementAndGet()
                    parsedNote(text)
                }
                "cachedPublicEventPreviews" -> {
                    cacheReads.incrementAndGet()
                    cacheRead()
                    @Suppress("UNCHECKED_CAST")
                    (args!![1] as List<String>).map(::cachedCardPreview)
                }
                "cachedIdentityProjections" -> emptyList<Any>()
                "userProfile" -> {
                    // The read the first presentation's profile warm waits on, so holding it holds readiness.
                    profileRead()
                    throw IllegalStateException("native call unavailable in this fixture: ${method.name}")
                }
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "toString" -> "EventCardConversationRuntime"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw IllegalStateException("native call unavailable in this fixture: ${method.name}")
            }
        } as MarmotInterface

    companion object {
        val EVENT_ID = "a".repeat(64)
        val NOTE = noteBech32(EVENT_ID)

        /** A second previously resolved card, for a window that names one the first read did not. */
        val SECOND_EVENT_ID = "b".repeat(64)
        val SECOND_NOTE = noteBech32(SECOND_EVENT_ID)
        const val CACHED_CONTENT = "Seen before"
        const val LOADING = "Loading"
        const val LOADED = "Loaded"

        /** Slower than the chat-list open's usual 300 ms bound, as the first read after a cold start can be. */
        const val COLD_CACHE_READ_MILLIS = 400L
        const val READ_BLOCK_SECONDS = 10L
        private const val WAIT_MILLIS = 10_000L
        private const val NATIVE_THREADS = 4

        /** The card a recorded state draws, by its visible text. */
        fun describe(state: NostrEventCardState): String =
            when (state) {
                is NostrEventCardState.Loaded -> "$LOADED ${state.card.eventIdHex}"
                NostrEventCardState.Loading -> LOADING
                else -> state.toString()
            }

        /** MDK's parse of [text]: the card reference of the note it names, otherwise nothing structured. */
        private fun parsedNote(text: String): MarkdownDocumentFfi {
            val note = NOTE_TOKEN.find(text)?.value ?: return EMPTY_MARKDOWN_DOCUMENT
            return eventCardDocument(note)
        }

        /** The event a cached [reference] names, so every note the conversation names has its own cached card. */
        private fun eventIdOf(reference: String): String =
            (NostrEventReferenceDecoder.eventReference(reference) as? NostrEventReference.Event)?.eventIdHex ?: EVENT_ID

        private val NOTE_TOKEN = Regex("note1[02-9ac-hj-np-z]+")

        /** MDK's cached projection of the card, present and verified. */
        private fun cachedCardPreview(reference: String) =
            PublicEventPreviewFfi(
                reference = reference,
                state = PublicEventPreviewStateFfi.PRESENT,
                eventJson =
                    JSONObject()
                        .put("id", eventIdOf(reference))
                        .put("pubkey", "c".repeat(64))
                        .put("created_at", 1)
                        .put("kind", 1)
                        .put("tags", JSONArray())
                        .put("content", CACHED_CONTENT)
                        .put("sig", "0".repeat(128))
                        .toString(),
                fetchedAt = 1uL,
            )
    }
}

/**
 * The controller's published rows, each through the bubble's document selection (stored tokens, else the bubble's
 * own fallback parse) into the screen's card resolver, recording every card state and every row order that composes.
 */
@Composable
private fun PublishedCardRows(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    composed: MutableList<NostrEventCardState>,
    composedOrders: MutableList<List<String>>,
    composesRow: (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val order = controller.timeline.map { it.record.messageIdHex }
    SideEffect { if (composedOrders.lastOrNull() != order) composedOrders += order }
    val resolver =
        remember(controller) {
            NostrEventCardResolver(
                parentScope = scope,
                readCached = { references ->
                    appState.readCachedPublicEventCards(ConversationTimelineTestIds.ACCOUNT_REF, references)
                },
                refresh = { reference ->
                    appState.refreshPublicEventCard(ConversationTimelineTestIds.ACCOUNT_REF, reference)
                },
                snapshots = controller.eventCardSnapshots,
            )
        }
    controller.timeline.filter { composesRow(it.record.messageIdHex) }.forEach { message ->
        key(message.id) {
            val record = message.record
            val document =
                rememberMessageMarkdownDocumentForDisplayedBody(
                    messageIdHex = record.messageIdHex,
                    bodyText = record.plaintext,
                    recordPlaintext = record.plaintext,
                    storedDocument = record.contentTokens,
                    overrideDocument = null,
                    deleted = false,
                    persistedFailure = false,
                    fallbackParsingEnabled = record.kind == 9uL,
                    parseMarkdown = { text -> appState.parseMarkdownOrEmpty(text) },
                )
            document?.let(::nostrEventReferences).orEmpty().forEach { occurrence ->
                val state by resolver.state(occurrence.reference, occurrence.authoredReference).collectAsState()
                SideEffect { composed += state }
                NostrEventCard(
                    state = state,
                    authorDisplayName = { it },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
            }
        }
    }
}
