package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reader link gestures, clipboard feedback and stale content ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NostrEventReaderLinkInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenLongPressCopiesExactlyOnceWithoutConfirmationOrMediaActivation() {
        val destination = "https://example.com/path?q=value#section"
        val label = "other.example"
        val writes = mutableListOf<String>()
        var mediaActivations = 0
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
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                WhiteNoiseTheme {
                    NostrEventReaderScreen(
                        card = noteCard().copy(mediaUrl = destination),
                        document = MarkdownDocumentFfi(listOf(readerLink(destination, label)), false, byteArrayOf()),
                        parsing = false,
                        authorDisplayName = { "Alex" },
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                        onDismiss = {},
                        onPlayVideo = { mediaActivations++ },
                        onViewImage = { mediaActivations++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText(label).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf(destination), writes)
            assertEquals(0, mediaActivations)
        }
        assertEquals(
            0,
            org.robolectric.shadows.ShadowToast
                .shownToastCount(),
        )
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, mediaActivations) }
        composeRule.onNodeWithText(string(R.string.link_confirm_open)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, mediaActivations)
            assertEquals(listOf(destination), writes)
        }
    }

    @Test
    fun draggingReaderLinkBeforeTimeoutLeavesClipboardUnchanged() {
        val destination = "https://example.com/drag"
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "keep this"))
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card = noteCard(),
                    document =
                        MarkdownDocumentFfi(
                            listOf(
                                paragraph(MarkdownInlineFfi.Text("Context above link ".repeat(10))),
                                readerLink(destination),
                                paragraph(MarkdownInlineFfi.Text("Scrollable article body ".repeat(500))),
                            ),
                            false,
                            byteArrayOf(),
                        ),
                    parsing = false,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(destination).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 3), delayMillis = 32)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(
                "keep this",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun replacingEvictedReaderRowCannotCopyItsOldDestination() {
        val destination = "https://example.com/removed"
        val replacement = "Ordinary replacement text"
        var block by mutableStateOf(readerLink(destination))
        val tail = "End of the scrolling reader"
        val filler = (1..80).map { paragraph(MarkdownInlineFfi.Text("Context paragraph $it ".repeat(20))) }
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card = noteCard(),
                    document =
                        MarkdownDocumentFfi(
                            listOf(block) + filler + paragraph(MarkdownInlineFfi.Text(tail)),
                            false,
                            byteArrayOf(),
                        ),
                    parsing = false,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(destination).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle { assertClipboardText(clipboard, destination) }
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(hasText(tail))
        composeRule.onNodeWithText(tail).assertIsDisplayed()
        composeRule.onNodeWithText(destination).assertDoesNotExist()
        composeRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "keep this"))
            block = paragraph(MarkdownInlineFfi.Text(replacement))
        }
        composeRule.onNodeWithTag(NOSTR_EVENT_READER_BODY_TAG).performScrollToNode(hasText(replacement))
        composeRule.onNodeWithText(replacement).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle { assertClipboardText(clipboard, "keep this") }
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun replacingLinkBlockAndReopeningCannotCopyItsOldDestination() {
        val destination = "https://example.com/removed"
        val replacement = "Ordinary replacement text"
        var block by mutableStateOf(readerLink(destination))
        var visible by mutableStateOf(true)
        val clipboard =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible) {
                    NostrEventReaderBlock(block, mentionDisplayName = { null }, onNostrProfileTap = {})
                }
            }
        }
        // First prove the original geometry is registered and copies successfully.
        composeRule.onNodeWithText(destination).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle {
            assertClipboardText(clipboard, destination)
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "keep this"))
            block = paragraph(MarkdownInlineFfi.Text(replacement))
        }
        composeRule.onNodeWithText(destination).assertDoesNotExist()
        composeRule.onNodeWithText(replacement).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle {
            assertClipboardText(clipboard, "keep this")
            visible = false
        }
        composeRule.onNodeWithText(replacement).assertDoesNotExist()
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithText(replacement).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.runOnIdle {
            assertClipboardText(clipboard, "keep this")
        }
    }

    private fun assertClipboardText(
        clipboard: ClipboardManager,
        expected: String,
    ) {
        assertEquals(
            expected,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
    }

    private fun readerLink(
        destination: String,
        label: String = destination,
    ): MarkdownBlockFfi =
        paragraph(
            MarkdownInlineFfi.Link(
                destination,
                null,
                listOf(MarkdownInlineFfi.Text(label)),
                MarkdownLinkDestinationKindFfi.WEB,
            ),
        )

    @Test
    @Config(sdk = [30])
    fun readerAccessibilityCopiesWwwDestinationWithFeedbackOnOlderAndroid() {
        val typed = "www.example.com/path?q=value#section"
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderBlock(
                    block =
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Autolink(
                                    typed,
                                    dev.ipf.marmotkit.MarkdownAutolinkKindFfi.WWW,
                                    MarkdownLinkDestinationKindFfi.WEB,
                                ),
                            ),
                        ),
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                )
            }
        }
        val actions = composeRule.onNodeWithText(typed).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { assertTrue(actions.single().action()) }
        composeRule.waitForIdle()
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
        composeRule.runOnIdle {
            assertEquals(
                "https://$typed",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
        assertEquals(
            string(R.string.copied),
            org.robolectric.shadows.ShadowToast
                .getTextOfLatestToast(),
        )
    }

    @Test
    fun clipboardFailureGivesFeedbackRatherThanEscapingTheReader() {
        triggerReaderCopyFailure(IllegalStateException("clipboard unavailable"))
        assertEquals(
            string(R.string.not_copied),
            org.robolectric.shadows.ShadowToast
                .getTextOfLatestToast(),
        )
    }

    @Test
    fun clipboardCancellationDoesNotShowFailureFeedback() {
        triggerReaderCopyFailure(kotlinx.coroutines.CancellationException("reader left"))
        assertEquals(
            0,
            org.robolectric.shadows.ShadowToast
                .shownToastCount(),
        )
    }

    private fun triggerReaderCopyFailure(failure: Exception) {
        val clipboard =
            object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null

                override suspend fun setClipEntry(clipEntry: ClipEntry?): Unit = throw failure
            }
        val url = "https://example.com/clipboard"
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                WhiteNoiseTheme {
                    NostrEventReaderBlock(
                        block =
                            MarkdownBlockFfi.Paragraph(
                                listOf(
                                    MarkdownInlineFfi.Autolink(
                                        url,
                                        dev.ipf.marmotkit.MarkdownAutolinkKindFfi.URI,
                                        MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                ),
                            ),
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                    )
                }
            }
        }
        val actions = composeRule.onNodeWithText(url).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { assertTrue(actions.single().action()) }
        composeRule.waitForIdle()
    }

    /** Builds the exact kind-1 event shown by the reader fixture. */
    private fun noteCard() =
        NostrEventCardModel(
            kind = NostrEventCardKind.Note,
            eventIdHex = "a".repeat(64),
            authorPubkeyHex = "b".repeat(64),
            createdAt = 1_765_000_000,
            eventKind = 1,
            title = null,
            summary = "The complete first paragraph is visible.",
            readerBody = "The complete reader body",
        )

    /** Builds one Markdown paragraph from the supplied inline nodes. */
    private fun paragraph(vararg inlines: MarkdownInlineFfi) = MarkdownBlockFfi.Paragraph(inlines.toList())

    /** Resolves one localized string through the Robolectric application context. */
    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
