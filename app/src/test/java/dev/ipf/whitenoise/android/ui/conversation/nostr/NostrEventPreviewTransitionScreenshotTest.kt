package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A previously resolved card is complete on its first frame and a background refresh never moves it or the rows
 * after it (#2238). Cards that were never resolved keep their fit-to-content geometry from #3219 instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h2400dp-mdpi")
class NostrEventPreviewTransitionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var session by mutableStateOf<TransitionSession?>(null)

    /**
     * For Note, Article, Video, Release, File and Generic cards in light, dark, AMOLED, large-font, narrow and RTL
     * configurations: the first frame from a seeded resolver is complete with no progress node, and a refresh that
     * keeps or updates author metadata leaves every card, message and following row at identical bounds.
     */
    @Test
    fun nostrEventPreviewStateTransitionsKeepMessageBoundsStable() {
        composeRule.setContent { session?.let { current -> key(current) { TransitionContent(current) } } }
        TransitionConfiguration.entries.forEach { configuration ->
            val current = TransitionSession(configuration)
            composeRule.mainClock.autoAdvance = false
            composeRule.runOnUiThread {
                session?.close()
                session = current
                Snapshot.sendApplyNotifications()
            }
            composeRule.mainClock.advanceTimeByFrame()
            assertCompleteWithoutProgress(configuration)
            val firstFrame = bounds()
            composeRule.mainClock.autoAdvance = true

            // A refresh that only confirms the cached content changes nothing.
            current.refreshWith(sameAuthor = true)
            assertEquals("$configuration: unchanged refresh", firstFrame, bounds())
            // A refresh that updates the author's profile replaces the header text in place.
            current.retryAll(composeRule) { current.refreshWith(sameAuthor = false) }
            assertEquals("$configuration: author refresh", firstFrame, bounds())
            assertEquals(0, current.secondPassParses)
            composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage(
                "src/test/snapshots/nostr_event_preview_refresh_${configuration.name.lowercase()}.png",
            )
        }
        composeRule.runOnUiThread { session?.close() }
    }

    /** Asserts all six cards render their loaded header and that no loading indicator or label is present. */
    private fun assertCompleteWithoutProgress(configuration: TransitionConfiguration) {
        val loading = ApplicationProvider.getApplicationContext<Context>().getString(R.string.nostr_event_loading)
        assertEquals(
            "$configuration: first frame shows a progress node",
            0,
            composeRule
                .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(0, composeRule.onAllNodesWithText(loading, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(CARD_KINDS.size, composeRule.onAllNodesWithText(CACHED_AUTHOR, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** Bounds of each card, each message and the row after them, in composition order. */
    private fun bounds(): List<Rect> {
        composeRule.waitForIdle()
        val cards = composeRule.onAllNodesWithTag(NOSTR_EVENT_CARD_BOUNDS_TAG).fetchSemanticsNodes().map { it.boundsInRoot }
        val rows =
            listOf(MESSAGE_TAG + 0, MESSAGE_TAG + 1, NEXT_ROW_TAG).map {
                composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            }
        assertEquals(CARD_KINDS.size, cards.size)
        return cards + rows
    }

    /** Two message bubbles of three cards each, then a following row, under [session]'s configuration. */
    @Composable
    private fun TransitionContent(session: TransitionSession) {
        val configuration = session.configuration
        WhiteNoiseTheme(darkTheme = configuration.dark, amoled = configuration.amoled) {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, configuration.fontScale),
                LocalLayoutDirection provides configuration.direction,
            ) {
                Surface {
                    Column(
                        modifier =
                            Modifier
                                .width(configuration.widthDp.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                                .testTag(ROOT_TAG),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        session.occurrences.chunked(MAX_CARDS_PER_MESSAGE).forEachIndexed { index, references ->
                            CardBubble(session, references, MESSAGE_TAG + index)
                        }
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag(NEXT_ROW_TAG))
                    }
                }
            }
        }
    }

    /** One received message bubble holding [references]' cards through the production card list. */
    @Composable
    private fun CardBubble(
        session: TransitionSession,
        references: List<NostrEventReferenceOccurrence>,
        tag: String,
    ) {
        val presentation = messageBubblePresentation(deleted = false, mine = false)
        val content = colorFromArgb(presentation.contentArgb)
        Surface(
            color = colorFromArgb(presentation.backgroundArgb),
            contentColor = content,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        ) {
            NostrEventCards(
                references = references,
                resolver = session.resolver,
                authorDisplayName = { "Contact" },
                mentionDisplayName = { null },
                onNostrProfileTap = {},
                parseMarkdown = session::parseAfterFirstFrame,
                contentColor = content,
                modifier = Modifier.padding(8.dp),
            )
        }
    }

    private companion object {
        const val ROOT_TAG = "nostr-preview-transition"
        const val MESSAGE_TAG = "message-"
        const val NEXT_ROW_TAG = "next-row"
        const val CACHED_AUTHOR = "Cached author"
        val CARD_KINDS = NostrEventCardKind.entries
    }

    /** One configuration's resolver, seeded snapshots and gated refreshes. */
    private class TransitionSession(
        val configuration: TransitionConfiguration,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private var refreshGate = CompletableDeferred<Boolean>()
        private val cards = CARD_KINDS.associateWith(::card)
        val occurrences =
            CARD_KINDS.map { kind ->
                NostrEventReferenceOccurrence(NostrEventReference.Event(eventId(kind)), "nostr:${kind.name}")
            }
        var secondPassParses = 0
            private set
        val resolver =
            NostrEventCardResolver(
                parentScope = scope,
                readCached = { error("seeded cards need no cache read") },
                refresh = { authored -> refreshed(authored, refreshGate.await()) },
                snapshots = seededSnapshots(),
            )

        /** Lets every pending refresh answer, keeping or replacing the cached author. */
        fun refreshWith(sameAuthor: Boolean) {
            refreshGate.complete(sameAuthor)
        }

        /** Re-arms the gate, retries every card, then runs [answer] so the retries settle. */
        fun retryAll(
            rule: ComposeContentTestRule,
            answer: () -> Unit,
        ) {
            refreshGate = CompletableDeferred()
            rule.runOnUiThread { occurrences.forEach { resolver.retry(it.reference, null, it.authoredReference) } }
            answer()
        }

        /** Counts a preview parse, which a seeded card with prepared text never needs. */
        suspend fun parseAfterFirstFrame(text: String): MarkdownDocumentFfi {
            secondPassParses += 1
            return document(text)
        }

        /** Stops the session's refreshes. */
        fun close() {
            resolver.close()
            scope.cancel()
        }

        /** The snapshots a reopened conversation has before its first frame: every card loaded and prepared. */
        private fun seededSnapshots(): NostrEventCardSnapshots =
            NostrEventCardSnapshots().apply {
                occurrences.zip(CARD_KINDS).forEach { (occurrence, kind) ->
                    put(occurrence.reference.stableId, loaded(cards.getValue(kind), CACHED_AUTHOR))
                }
            }

        /** MDK's state after a refresh: the same event, with the cached or an updated author profile. */
        private fun refreshed(
            authored: String,
            sameAuthor: Boolean,
        ): NostrEventCardState {
            val kind = NostrEventCardKind.valueOf(authored.removePrefix("nostr:"))
            return loaded(cards.getValue(kind), if (sameAuthor) CACHED_AUTHOR else "Renamed author")
        }

        /** A loaded state for [card] by [author], with preview text prepared the way the native read does. */
        private fun loaded(
            card: NostrEventCardModel,
            author: String,
        ): NostrEventCardState.Loaded {
            val withAuthor = card.copy(authorMetadata = NostrEventAuthorMetadata(author, null))
            val preview = runBlocking { prepareNostrEventPreview(withAuthor) { document(it) } }
            return NostrEventCardState.Loaded(withAuthor, preview)
        }
    }
}

/** The visual configurations every card transition is checked under. */
private enum class TransitionConfiguration(
    val widthDp: Int = 360,
    val fontScale: Float = 1f,
    val direction: LayoutDirection = LayoutDirection.Ltr,
    val dark: Boolean = false,
    val amoled: Boolean = false,
) {
    Light,
    Dark(dark = true),
    Amoled(dark = true, amoled = true),
    LargeFont(fontScale = 2f),
    Narrow(widthDp = 240),
    Rtl(direction = LayoutDirection.Rtl),
}

/** A distinct event id per card kind. */
private fun eventId(kind: NostrEventCardKind): String = (kind.ordinal + 1).toString(16).padStart(64, '0')

/** A representative card of [kind] with a title, summary, metadata and reader body. */
private fun card(kind: NostrEventCardKind): NostrEventCardModel =
    NostrEventCardModel(
        kind = kind,
        eventIdHex = eventId(kind),
        authorPubkeyHex = "b".repeat(64),
        createdAt = 1_765_000_000L,
        eventKind =
            when (kind) {
                NostrEventCardKind.Note -> 1
                NostrEventCardKind.Article -> 30_023
                NostrEventCardKind.Video -> 34_235
                NostrEventCardKind.Release -> 30_063
                NostrEventCardKind.File -> 1_063
                NostrEventCardKind.Generic -> 9_001
            },
        title = "A ${kind.name.lowercase()} title that wraps across the reading width".takeIf { kind != NostrEventCardKind.Note },
        summary = "A readable ${kind.name.lowercase()} summary with enough words to fill several preview lines.",
        metadata = if (kind == NostrEventCardKind.Note) emptyList() else listOf("2.4 MB", "1920x1080"),
        readerBody = "The complete ${kind.name.lowercase()} body.",
        mediaUrl = "https://cdn.example/video.mp4".takeIf { kind == NostrEventCardKind.Video },
    )

/** A one-paragraph Markdown document holding [text], standing in for MDK's parser. */
private fun document(text: String): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
        truncated = false,
        blankLinesBefore = byteArrayOf(0),
    )
