package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.PublicEventPreviewFfi
import dev.ipf.marmotkit.PublicEventPreviewStateFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A previously resolved event card on a conversation's first page, through the real controller, its Markdown
 * hydration, first-page card warm and publish hold, and the bubble's document selection and card resolver (#2238).
 * Rows compose wherever the controller publishes them, the way every open path shows them, so what is recorded here
 * is what any route draws on its first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class InitialEventCardFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A cold 400 ms cache read, slower than the usual open bound, still composes the card Loaded at first. */
    @Test
    fun slowColdCacheReadComposesTheCardLoadedOnItsFirstFrame() {
        val fixture = CardConversationFixture(cacheRead = { Thread.sleep(COLD_CACHE_READ_MILLIS) })
        try {
            fixture.compose()
            awaitIdleCondition(fixture, fixture::openedWithACard)

            assertTrue("the read settled the hold, not the budget", fixture.cacheReads.get() >= 1)
            assertTrue("the held page published with readiness", fixture.controller.hasPublishedAuthoritativeTimeline)
            assertEquals(
                "no Loading card may compose before the cached card",
                listOf("$LOADED $EVENT_ID"),
                fixture.composed.map(::describe).distinct(),
            )
        } finally {
            fixture.close()
        }
    }

    /** A cache read that never answers cannot hold the conversation past the card budget. */
    @Test
    fun cacheReadBeyondTheBudgetStillPublishesTheConversation() {
        val blocked = CountDownLatch(1)
        val fixture = CardConversationFixture(cacheRead = { blocked.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) })
        try {
            fixture.compose()
            awaitIdleCondition(fixture) { fixture.controller.isHoldingInitialPageForEventCards }
            assertFalse("a held page is not yet published", fixture.controller.hasPublishedAuthoritativeTimeline)
            assertTrue("nothing composes while the page is held", fixture.composed.isEmpty())

            shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS + 1L),
            )
            awaitIdleCondition(fixture, fixture::openedWithACard)

            assertTrue(fixture.controller.hasPublishedAuthoritativeTimeline)
            assertFalse(fixture.controller.isHoldingInitialPageForEventCards)
            assertEquals("past the budget the card resolves itself", LOADING, describe(fixture.composed.first()))
        } finally {
            blocked.countDown()
            fixture.close()
        }
    }

    /** A first page that names no card is published at once, as before, without waiting for any card budget. */
    @Test
    fun pageWithoutCardsIsPublishedWithoutTheHold() {
        val fixture = CardConversationFixture(cacheRead = {}, plaintext = "plain hello")
        try {
            awaitIdleCondition(fixture) { fixture.controller.hasPublishedAuthoritativeTimeline }

            assertFalse(fixture.controller.isHoldingInitialPageForEventCards)
            assertEquals(listOf(MESSAGE_ID), timelineMessageIds(fixture.controller))
            assertEquals(0, fixture.cacheReads.get())
        } finally {
            fixture.close()
        }
    }

    /**
     * Idles the main looper until [condition] holds, without moving its clock, so only real work settles it. A timeout
     * reports [fixture]'s state, so a hang names the stage it stopped at.
     */
    private fun awaitIdleCondition(
        fixture: CardConversationFixture,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(WAIT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                // Controller writes happen outside composition. After many earlier Robolectric tests in the same JVM
                // the global apply observer may not be scheduled on this test's looper, so flush them explicitly.
                Snapshot.sendApplyNotifications()
                condition()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("stalled at ${fixture.describeState()}", timeout)
        }
        composeRule.waitForIdle()
    }

    /** The card a recorded state draws, by its visible text. */
    private fun describe(state: NostrEventCardState): String =
        when (state) {
            is NostrEventCardState.Loaded -> "$LOADED ${state.card.eventIdHex}"
            NostrEventCardState.Loading -> LOADING
            else -> state.toString()
        }

    /** A real conversation controller whose first page holds one message naming a previously resolved card. */
    private inner class CardConversationFixture(
        private val cacheRead: () -> Unit,
        plaintext: String = "nostr:$NOTE",
    ) {
        val cacheReads = AtomicInteger()
        val composed: MutableList<NostrEventCardState> = Collections.synchronizedList(mutableListOf())
        private val scripts =
            ScriptedConversationLiveSubscriptions(
                timelineScripts =
                    listOf(
                        ScriptedConversationTimelineSubscription(
                            timelinePage(timelineRecord(MESSAGE_ID, 1uL, plaintext = plaintext)),
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
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )

        /** True once the conversation is ready and a card row has composed. */
        fun openedWithACard(): Boolean = controller.hasPreparedInitialPresentation && composed.isNotEmpty()

        /** The stages a first page passes, for a stalled wait's message. */
        fun describeState(): String =
            "holding=${controller.isHoldingInitialPageForEventCards} " +
                "published=${controller.hasPublishedAuthoritativeTimeline} " +
                "prepared=${controller.hasPreparedInitialPresentation} " +
                "rows=${controller.timeline.size} cacheReads=${cacheReads.get()} composed=${composed.size}"

        /** Composes the controller's published rows as the conversation screen does. */
        fun compose() {
            composeRule.setContent { WhiteNoiseTheme { PublishedCardRows(appState, controller, composed) } }
        }

        /** Releases the controller, its scripted window and its native threads. */
        fun close() {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripts)
            nativeExecutor.shutdownNow()
        }

        /** Answers Markdown and the cached card natively, and fails every other native call as unavailable. */
        private fun runtime(): MarmotInterface =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name.substringBefore('-')) {
                    "parseMarkdown" -> parsed(args!![0] as String)
                    "cachedPublicEventPreviews" -> {
                        cacheReads.incrementAndGet()
                        cacheRead()
                        @Suppress("UNCHECKED_CAST")
                        (args!![1] as List<String>).map(::cachedPreview)
                    }
                    "cachedIdentityProjections" -> emptyList<Any>()
                    "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                    "toString" -> "InitialEventCardRuntime"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw IllegalStateException("native call unavailable in this fixture: ${method.name}")
                }
            } as MarmotInterface
    }

    private companion object {
        val MESSAGE_ID = "d1".repeat(32)
        val EVENT_ID = "a".repeat(64)
        val NOTE = noteBech32(EVENT_ID)
        const val CACHED_CONTENT = "Seen before"
        const val LOADING = "Loading"
        const val LOADED = "Loaded"

        /** Slower than the chat-list open's usual 300 ms bound, as the first read after a cold start can be. */
        const val COLD_CACHE_READ_MILLIS = 400L
        const val WAIT_MILLIS = 10_000L
        const val READ_BLOCK_SECONDS = 10L
        const val NATIVE_THREADS = 4

        /** MDK's parse of [text]: the card reference when it names the note, otherwise nothing structured. */
        fun parsed(text: String) = if (NOTE in text) eventCardDocument(NOTE) else EMPTY_MARKDOWN_DOCUMENT

        /** MDK's cached projection of the card, present and verified. */
        fun cachedPreview(reference: String) =
            PublicEventPreviewFfi(
                reference = reference,
                state = PublicEventPreviewStateFfi.PRESENT,
                eventJson =
                    JSONObject()
                        .put("id", EVENT_ID)
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
 * own fallback parse) into the screen's card resolver, recording every card state that composes.
 */
@Composable
private fun PublishedCardRows(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    composed: MutableList<NostrEventCardState>,
) {
    val scope = rememberCoroutineScope()
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
    controller.timeline.forEach { message ->
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
