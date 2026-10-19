package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.core.hexString
import dev.ipf.whitenoise.android.core.nip19
import dev.ipf.whitenoise.android.core.nip19Tlv
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message whose card represents an event leaves that event's references out of its prose (#3317): the bracketed
 * reference closes up, a paragraph of only an alias is not drawn, and a reference without a card stays visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1800dp-mdpi")
class NostrReferenceProseScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The prose and its card in the light theme. */
    @Test
    fun proseLeavesOutTheCardsReferenceLight() {
        render(darkTheme = false)
        capture("nostr_reference_prose_light")
    }

    /** The same message in the dark theme, at a large font and right to left. */
    @Test
    fun proseLeavesOutTheCardsReferenceDarkLargeRtl() {
        render(darkTheme = true, fontScale = 1.6f, layoutDirection = LayoutDirection.Rtl)
        capture("nostr_reference_prose_dark_large_rtl")
    }

    /** Renders one incoming bubble with its Markdown body above the loaded card that represents [NOTE]. */
    private fun render(
        darkTheme: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    val presentation = messageBubblePresentation(deleted = false, mine = false)
                    val content = colorFromArgb(presentation.contentArgb)
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                            .testTag(TAG),
                    ) {
                        Surface(
                            color = colorFromArgb(presentation.backgroundArgb),
                            contentColor = content,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.width(310.dp),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MarkdownMessageBody(DOCUMENT, hiddenEventIds = setOf("event:${EVENT_ID.hexString()}"))
                                NostrEventCard(
                                    state = NostrEventCardState.Loaded(CARD),
                                    authorDisplayName = { "Alex" },
                                    contentColor = content,
                                    onRetry = {},
                                    onCopy = {},
                                    onOpen = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Records the tagged bubble as the named baseline. */
    private fun capture(name: String) = composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")

    private companion object {
        /** A parsed `nostr:` URI of [bech32] under [hrp]. */
        fun nostrUri(
            hrp: MarkdownNostrHrpFfi,
            bech32: String,
        ) = MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(hrp, bech32))

        const val TAG = "nostr-reference-prose"
        val EVENT_ID = List(32) { 7 }
        val NOTE = nip19("note", EVENT_ID)
        val NEVENT = nip19("nevent", nip19Tlv(0, EVENT_ID))
        val OTHER_NOTE = nip19("note", List(32) { 8 })
        val DOCUMENT =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Worth reading before the call ("),
                                nostrUri(MarkdownNostrHrpFfi.NOTE, NOTE),
                                MarkdownInlineFfi.Text(") — the summary is solid."),
                            ),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            listOf(nostrUri(MarkdownNostrHrpFfi.NEVENT, NEVENT)),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Background in the "),
                                MarkdownInlineFfi.Link(
                                    "https://example.com/notes",
                                    null,
                                    listOf(MarkdownInlineFfi.Text("meeting notes")),
                                    MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Text(" and "),
                                nostrUri(MarkdownNostrHrpFfi.NOTE, OTHER_NOTE),
                            ),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = ByteArray(3),
            )
        val CARD =
            NostrEventCardModel(
                kind = NostrEventCardKind.Note,
                eventIdHex = EVENT_ID.hexString(),
                authorPubkeyHex = "b".repeat(64),
                createdAt = 1_765_000_000,
                eventKind = 1,
                title = null,
                summary = "Three priorities for calmer group chats this quarter.",
            )
    }
}
