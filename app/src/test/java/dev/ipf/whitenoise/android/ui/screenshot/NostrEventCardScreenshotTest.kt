package dev.ipf.whitenoise.android.ui.screenshot

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventReaderScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class NostrEventCardScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noteAndArticleLight() {
        render(darkTheme = false) {
            EventBubble(
                NostrEventCardState.Loaded(
                    card(
                        NostrEventCardKind.Note,
                        1,
                        null,
                        "A concise public note shared in the conversation.",
                    ),
                ),
                mine = false,
            )
            EventBubble(
                NostrEventCardState.Loaded(
                    card(
                        NostrEventCardKind.Article,
                        30_023,
                        "Building calmer group conversations",
                        "A practical overview with enough detail to identify the article without expanding it inline.",
                        readerBody = "Article body",
                    ),
                ),
                mine = true,
            )
        }
        capture("nostr_event_cards_note_article_light")
    }

    @Test
    fun mediaAndGenericDark() {
        render(darkTheme = true) {
            EventBubble(
                NostrEventCardState.Loaded(
                    card(
                        NostrEventCardKind.Video,
                        34_235,
                        "Community update",
                        "A video preview that stays text-first and never autoplays.",
                        listOf("2:05", "1920x1080"),
                        mediaUrl = "https://cdn.example/video.mp4",
                    ),
                ),
                mine = false,
            )
            EventBubble(
                NostrEventCardState.Loaded(
                    card(NostrEventCardKind.Generic, 9_001, null, "Verified public event with an unfamiliar kind."),
                ),
                mine = true,
            )
        }
        capture("nostr_event_cards_media_generic_dark")
    }

    @Test
    fun releaseLoadingAndFailureAmoled() {
        render(darkTheme = true, amoled = true) {
            EventBubble(
                NostrEventCardState.Loaded(
                    card(
                        NostrEventCardKind.Release,
                        30_063,
                        "White Noise 2026.8",
                        "Accessibility and conversation polish.",
                        listOf("2026.8.1"),
                    ),
                ),
                mine = false,
            )
            EventBubble(NostrEventCardState.Loading, mine = true)
            EventBubble(NostrEventCardState.Failed, mine = false)
        }
        capture("nostr_event_cards_states_amoled")
    }

    @Test
    fun fileAndFallbackStatesLargeRtl() {
        render(
            darkTheme = false,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        ) {
            EventBubble(
                NostrEventCardState.Loaded(
                    card(
                        NostrEventCardKind.File,
                        1_063,
                        "خطة المشروع.pdf",
                        "ملف عام مشترك مع تفاصيل كافية للتعرّف عليه.",
                        listOf("2.4 MB", "PDF"),
                    ),
                ),
                mine = false,
            )
            EventBubble(NostrEventCardState.Invalid, mine = true)
            EventBubble(NostrEventCardState.NotFound, mine = false)
        }
        capture("nostr_event_cards_file_states_large_rtl")
    }

    /** Protects the pre-existing article reader while the shared reader gains kind-1 support. */
    @Test
    fun articleReaderLight() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card =
                        card(
                            NostrEventCardKind.Article,
                            30_023,
                            "Building calmer group conversations",
                            "A practical overview of quieter, more intentional group conversations.",
                            readerBody =
                                "Start by making notification choices explicit. Then give every participant " +
                                    "a clear way to focus without losing context.",
                        ),
                    document = null,
                    parsing = false,
                    authorDisplayName = { "Alex Morgan" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onDismiss = {},
                    modifier = Modifier.testTag(TAG),
                )
            }
        }

        capture("nostr_article_reader_light")
    }

    /** Captures the complete kind-1 reader in the default light presentation. */
    @Test
    fun noteReaderLight() {
        renderNoteReader(darkTheme = false)
        capture("nostr_note_reader_light")
    }

    /** Captures note context and actions against the AMOLED surface palette. */
    @Test
    fun noteReaderAmoled() {
        renderNoteReader(darkTheme = true, amoled = true)
        capture("nostr_note_reader_amoled")
    }

    /** Captures wrapping and control layout at the supported large-font scale. */
    @Test
    fun noteReaderLargeFont() {
        renderNoteReader(darkTheme = false, fontScale = 1.5f)
        capture("nostr_note_reader_large_font")
    }

    /** Captures the reader at a narrow width where reference and Markdown wrapping are stressed. */
    @Test
    fun noteReaderNarrow() {
        renderNoteReader(darkTheme = false, width = 280.dp)
        capture("nostr_note_reader_narrow")
    }

    /** Captures mirrored Back and event actions under an RTL layout direction. */
    @Test
    fun noteReaderRtl() {
        renderNoteReader(darkTheme = false, layoutDirection = LayoutDirection.Rtl)
        capture("nostr_note_reader_rtl")
    }

    /** Renders a deterministic note-reader fixture under one requested visual configuration. */
    private fun renderNoteReader(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 360.dp,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    Surface(
                        modifier = Modifier.width(width).height(900.dp).testTag(TAG),
                    ) {
                        NostrEventReaderScreen(
                            card =
                                card(
                                    kind = NostrEventCardKind.Note,
                                    eventKind = 1,
                                    title = null,
                                    summary = "A compact note preview.",
                                    readerBody = NOTE_READER_BODY,
                                ),
                            authoredReference = AUTHORED_REFERENCE,
                            document = noteReaderDocument(),
                            parsing = false,
                            authorDisplayName = { "Alex Morgan" },
                            mentionDisplayName = { null },
                            onNostrProfileTap = {},
                            onCopyReference = {},
                            onOpenExternal = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    /** Builds the parsed Markdown shown in every note-reader baseline. */
    private fun noteReaderDocument() =
        MarkdownDocumentFfi(
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("This is the complete referenced note, including "),
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("important context"))),
                            MarkdownInlineFfi.Text(" that the timeline preview could not show."),
                        ),
                    ),
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("Read the "),
                            MarkdownInlineFfi.Link(
                                dest = "https://example.com/project",
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("project page")),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                            MarkdownInlineFfi.Text(" or inspect "),
                            MarkdownInlineFfi.NostrUri(
                                MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, NESTED_NOTE),
                            ),
                            MarkdownInlineFfi.Text(" without recursively expanding it."),
                        ),
                    ),
                ),
            truncated = false,
            blankLinesBefore = byteArrayOf(),
        )

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    Surface {
                        Column(
                            modifier =
                                Modifier
                                    .width(360.dp)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(16.dp)
                                    .testTag(TAG),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EventBubble(
        state: NostrEventCardState,
        mine: Boolean,
    ) {
        val container =
            if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val content =
            if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                color = container,
                contentColor = content,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.width(310.dp),
            ) {
                NostrEventCard(
                    state = state,
                    authorDisplayName = { "Alex" },
                    referenceLabel = "nevent1qqs8f4…6da8fv0",
                    contentColor = content,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
            }
        }
    }

    private fun card(
        kind: NostrEventCardKind,
        eventKind: Int,
        title: String?,
        summary: String,
        metadata: List<String> = emptyList(),
        readerBody: String? = null,
        mediaUrl: String? = null,
    ) = NostrEventCardModel(
        kind = kind,
        eventIdHex = "a".repeat(64),
        authorPubkeyHex = "b".repeat(64),
        createdAt = 1_765_000_000,
        eventKind = eventKind,
        title = title,
        summary = summary,
        metadata = metadata,
        readerBody = readerBody,
        mediaUrl = mediaUrl,
    )

    private fun capture(name: String) = composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")

    private companion object {
        const val TAG = "nostr-event-cards"
        const val AUTHORED_REFERENCE = "nevent1qqs8f4r0originalreference6da8fv0"
        const val NESTED_NOTE = "note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsn9e8p"
        const val NOTE_READER_BODY = "This is the complete referenced note with a link and a nested Nostr reference."
    }
}
