package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventCard(
                    state = NostrEventCardState.Loaded(noteCard()),
                    authorDisplayName = { "Alex" },
                    contentColor = Color.Black,
                    onRetry = {},
                    onCopy = { copies++ },
                    onOpen = { opens++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.nostr_event_type_note)).assertIsDisplayed()
        composeRule.onNodeWithText("A short referenced note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.nostr_event_open)).performClick()

        assertEquals(1, copies)
        assertEquals(1, opens)
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
