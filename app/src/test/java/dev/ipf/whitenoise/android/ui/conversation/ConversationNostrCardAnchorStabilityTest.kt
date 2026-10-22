package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.NostrEventReferenceOccurrence
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventAuthorMetadata
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardSnapshots
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCards
import dev.ipf.whitenoise.android.ui.conversation.nostr.prepareNostrEventPreview
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Resolving or refreshing an event card never moves the conversation's reading anchor (#2238). The transcript is
 * reversed like the conversation's, so the anchor is the first visible row at the bottom of the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
class ConversationNostrCardAnchorStabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val cachedRefresh = CompletableDeferred<NostrEventCardState?>()
    private val freshRefresh = CompletableDeferred<NostrEventCardState?>()
    private val cached = occurrence(CACHED_ROW)
    private val fresh = occurrence(FRESH_ROW)
    private val resolver =
        NostrEventCardResolver(
            parentScope = scope,
            readCached = { references -> references.map { null } },
            refresh = { authored ->
                val refresh = if (authored == cached.authoredReference) cachedRefresh else freshRefresh
                refresh.await()
            },
            snapshots =
                NostrEventCardSnapshots().apply {
                    put(cached.reference.stableId, loaded(CACHED_ROW, "Cached author"))
                },
        )
    private val listState = LazyListState(firstVisibleItemIndex = ANCHOR_ROW)
    private val viewport = ConversationTimelineViewport(listState)

    /** Releases the resolver and its refreshes. */
    @After
    fun tearDown() {
        resolver.close()
        scope.cancel()
    }

    /**
     * A cached card above the anchor refreshes in place without moving any row, and a never-seen card further up
     * resolves to its fitted size without moving the anchor or the rows between them.
     */
    @Test
    fun resolvingAndRefreshingCardsKeepTheReadingAnchorAndRowsInPlace() {
        composeRule.setContent { Transcript() }
        val initial = rowOffsets()
        val freshSize = rowSize(FRESH_ROW)
        assertTrue("both cards must be laid out", CACHED_ROW in initial && FRESH_ROW in initial)

        composeRule.runOnIdle { cachedRefresh.complete(loaded(CACHED_ROW, "Updated author")) }
        assertEquals("a cached card's refresh moves no row", initial, rowOffsets())

        composeRule.runOnIdle { freshRefresh.complete(loaded(FRESH_ROW, "First author")) }
        val resolved = rowOffsets()
        assertNotEquals("the never-seen card fits its resolved content", freshSize, rowSize(FRESH_ROW))
        assertEquals(ANCHOR_ROW, listState.firstVisibleItemIndex)
        (ANCHOR_ROW until FRESH_ROW).forEach { row ->
            assertEquals("row $row stays put while a card above it resolves", initial[row], resolved[row])
        }
    }

    /** A reversed transcript of short rows with a cached and a never-seen card above the anchor. */
    @Composable
    private fun Transcript() {
        WhiteNoiseTheme {
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.width(360.dp).height(800.dp)) {
                items(ROW_COUNT, key = { "row-$it" }) { row ->
                    when (row) {
                        CACHED_ROW -> CardRow(cached)
                        FRESH_ROW -> CardRow(fresh)
                        else -> Box(Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
    }

    /** A message row holding one event card through the production card list. */
    @Composable
    private fun CardRow(occurrence: NostrEventReferenceOccurrence) {
        NostrEventCards(
            references = listOf(occurrence),
            resolver = resolver,
            authorDisplayName = { "Contact" },
            mentionDisplayName = { null },
            onNostrProfileTap = {},
            parseMarkdown = ::document,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    /** Each visible row's offset from the reading layout, keyed by row index. */
    private fun rowOffsets(): Map<Int, Int> {
        composeRule.waitForIdle()
        return composeRule.runOnIdle {
            viewport.readingLayoutInfo().visibleItemsInfo.associate { it.index to it.offset }
        }
    }

    /** The measured height of [row] in the reading layout. */
    private fun rowSize(row: Int): Int =
        composeRule.runOnIdle {
            viewport
                .readingLayoutInfo()
                .visibleItemsInfo
                .single { it.index == row }
                .size
        }

    /** The card occurrence shown in [row]. */
    private fun occurrence(row: Int): NostrEventReferenceOccurrence =
        NostrEventReferenceOccurrence(
            reference = NostrEventReference.Event(eventId(row)),
            authoredReference = "nostr:row-$row",
        )

    /** A loaded note for [row] by [author], with preview text prepared like the native read. */
    private fun loaded(
        row: Int,
        author: String,
    ): NostrEventCardState.Loaded {
        val card =
            NostrEventCardModel(
                kind = NostrEventCardKind.Note,
                eventIdHex = eventId(row),
                authorPubkeyHex = "b".repeat(64),
                createdAt = 1_765_000_000L,
                eventKind = 1,
                title = null,
                summary = "A referenced note with enough text to wrap onto a second preview line in the card.",
                authorMetadata = NostrEventAuthorMetadata(author, null),
            )
        return NostrEventCardState.Loaded(card, runBlocking { prepareNostrEventPreview(card, ::document) })
    }

    private companion object {
        const val ROW_COUNT = 30
        const val ANCHOR_ROW = 2
        const val CACHED_ROW = 4
        const val FRESH_ROW = 7

        /** A distinct event id for [row]. */
        fun eventId(row: Int): String = (row + 1).toString(16).padStart(64, '0')

        /** A one-paragraph Markdown document holding [text], standing in for MDK's parser. */
        @Suppress("RedundantSuspendModifier")
        suspend fun document(text: String): MarkdownDocumentFfi =
            MarkdownDocumentFfi(
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )
    }
}
