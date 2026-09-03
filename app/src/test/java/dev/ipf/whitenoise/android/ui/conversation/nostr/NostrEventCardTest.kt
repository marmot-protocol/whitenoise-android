package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NostrEventCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadedCardExposesCopyAndOpenActions() {
        var copies = 0
        var opens = 0
        var openedCard: NostrEventCardModel? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state = NostrEventCardState.Loaded(noteCard()),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = { copies++ },
                    onOpen = {
                        opens++
                        openedCard = it
                    },
                )
            }
        }

        composeRule
            .onNodeWithText(string(R.string.nostr_event_type_note), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("A short referenced note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_open)).performClick()

        assertEquals(1, copies)
        assertEquals(1, opens)
        assertEquals(noteCard(), openedCard)
    }

    @Test
    fun failedCardKeepsFallbackActionsAndRetries() {
        var retries = 0
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                NostrEventCard(
                    state = NostrEventCardState.Failed,
                    authorDisplayName = { "" },
                    contentColor = Color.White,
                    onRetry = { retries++ },
                    onCopy = {},
                    onOpen = { opens++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.nostr_event_failed)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.retry)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_open)).performClick()

        assertEquals(1, retries)
        assertEquals(1, opens)
    }

    @Test
    fun articleAndVideoExposeInAppActions() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state =
                        NostrEventCardState.Loaded(
                            noteCard().copy(
                                kind = NostrEventCardKind.Article,
                                eventKind = 30_023,
                                title = "An article",
                                readerBody = "Article body",
                            ),
                        ),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
                NostrEventCard(
                    state =
                        NostrEventCardState.Loaded(
                            noteCard().copy(
                                kind = NostrEventCardKind.Video,
                                eventKind = 34_235,
                                title = "A video",
                                mediaUrl = "https://cdn.example/video.mp4",
                            ),
                        ),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_read_article)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_play_video)).assertIsDisplayed()
    }

    @Test
    fun kindZeroAuthorAndCompactReferenceAreShownInsideTheCard() {
        val reference = "nevent1" + "q".repeat(48) + "ending1"
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state =
                        NostrEventCardState.Loaded(
                            noteCard().copy(
                                authorMetadata =
                                    NostrEventAuthorMetadata(
                                        displayName = "Alice Rivers",
                                        pictureUrl = null,
                                    ),
                            ),
                        ),
                    authorDisplayName = { "Local fallback" },
                    referenceLabel = compactEventReference(reference),
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithText("Alice Rivers").assertIsDisplayed()
        composeRule.onNodeWithText("nevent1qqqqqqq…ending1").assertIsDisplayed()
    }

    /** Verifies every point in the ellipsized note preview invokes only the in-app reader action. */
    @Test
    fun longNotePreviewIsOneFullWidthLocalizedActionIndependentOfHeaderControls() {
        val card =
            noteCard().copy(
                summary = "A long referenced note ".repeat(40),
                readerBody = "Complete note body",
            )
        val readCards = mutableListOf<NostrEventCardModel>()
        var copies = 0
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state = NostrEventCardState.Loaded(card),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = { copies++ },
                    onOpen = { opens++ },
                    onReadNote = { readCards += it },
                )
            }
        }

        val preview = composeRule.onNodeWithTag(NOSTR_NOTE_PREVIEW_ACTION_TAG)
        preview.assertIsDisplayed().assertHasClickAction()
        val semantics = preview.fetchSemanticsNode().config
        assertEquals(Role.Button, semantics[SemanticsProperties.Role])
        assertEquals(string(R.string.nostr_event_read_note), semantics[SemanticsActions.OnClick].label)

        preview.performTouchInput {
            click(Offset(x = 1f, y = centerY))
            click(center)
            click(Offset(x = width - 1f, y = centerY))
        }
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_open)).performClick()

        assertEquals(listOf(card, card, card), readCards)
        assertEquals(1, copies)
        assertEquals(1, opens)
    }

    /** Verifies a readable one-line note still reserves the Material minimum touch-target height. */
    @Test
    fun shortNotePreviewHasMinimumInteractiveHeight() {
        val card = noteCard().copy(readerBody = "Complete note body")
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state = NostrEventCardState.Loaded(card),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = {},
                    onOpen = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(NOSTR_NOTE_PREVIEW_ACTION_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    private fun string(resId: Int): String =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(resId)

    private fun noteCard() =
        NostrEventCardModel(
            kind = NostrEventCardKind.Note,
            eventIdHex = "a".repeat(64),
            authorPubkeyHex = "b".repeat(64),
            createdAt = 1_765_000_000,
            eventKind = 1,
            title = null,
            summary = "A short referenced note",
        )
}
