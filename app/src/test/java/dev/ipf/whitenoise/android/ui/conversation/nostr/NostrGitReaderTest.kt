package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        systemClipboard().setPrimaryClip(ClipData.newPlainText("sentinel", "keep this"))
        setReader(event(kind = 1617, content = patch))

        composeRule.onNodeWithText(patch, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("open me", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("keep this", clipText()) }
    }

    @Test
    fun namedNostrCloneUrlIsCopiedExactly() {
        val clone = "nostr://alice@example.org/relay.example.org/parser"
        setReader(event(kind = 30617, tags = listOf(listOf("d", "parser"), listOf("clone", clone))))

        composeRule.onNodeWithText(string(R.string.nostr_git_copy_url, clone)).performClick()
        composeRule.runOnIdle { assertEquals(clone, clipText()) }
    }

    @Test
    fun httpsRepositoryLinkRequiresConfirmationAndCanBeCancelled() {
        val link = "https://example.org/parser"
        setReader(event(kind = 30617, tags = listOf(listOf("d", "parser"), listOf("web", link))))

        composeRule.onNodeWithText(string(R.string.nostr_git_open_url, link)).performClick()
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText("https://example.org").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()
    }

    private fun systemClipboard(): ClipboardManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getSystemService(ClipboardManager::class.java)
    }

    private fun clipText(): String? =
        systemClipboard()
            .primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()

    private fun setReader(event: NostrEvent) {
        val card = event.toCardModel()
        composeRule.setContent {
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
        vararg args: String,
    ): String = ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
