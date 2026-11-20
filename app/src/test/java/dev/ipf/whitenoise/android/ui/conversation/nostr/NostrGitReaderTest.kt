package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reader shows Git event content literally and copies advertised clone URLs exactly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NostrGitReaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun patchWithMarkdownLinkSyntaxIsShownAsPlainText() {
        val patch = "Subject: [PATCH] Fix\n\n[open me](https://example.org/x) and **bold** text"
        val writes = mutableListOf<String>()
        setReader(event(kind = 1617, content = patch), writes)

        composeRule.onNodeWithText(patch, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("open me", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), writes) }
    }

    @Test
    fun namedNostrCloneUrlIsCopiedExactly() {
        val clone = "nostr://alice@example.org/relay.example.org/parser"
        val writes = mutableListOf<String>()
        setReader(event(kind = 30617, tags = listOf(listOf("d", "parser"), listOf("clone", clone))), writes)

        composeRule.onNodeWithText(string(R.string.nostr_git_copy_url, clone)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(clone), writes) }
    }

    private fun setReader(
        event: NostrEvent,
        writes: MutableList<String>,
    ) {
        val clipboard =
            object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null

                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    writes +=
                        requireNotNull(clipEntry)
                            .clipData
                            .getItemAt(0)
                            .text
                            .toString()
                }
            }
        val card = event.toCardModel()
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                WhiteNoiseTheme {
                    NostrEventReaderScreen(
                        card = card,
                        document = null,
                        parsing = false,
                        authorDisplayName = { "Alex" },
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun event(
        kind: Int,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = "a".repeat(64),
        pubkey = "c".repeat(64),
        createdAt = 1,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    private fun string(
        resId: Int,
        arg: String,
    ): String = ApplicationProvider.getApplicationContext<Context>().getString(resId, arg)
}
