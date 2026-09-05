package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListFirstFrameMarkdownPublicationTest {
    @Test
    fun projectedMarkdownIsAttachedBeforeTheFirstVisiblePublication() =
        runTest {
            val tokens = richMarkdown()
            val controller = controller()
            try {
                controller.setChatListVisible(false)
                controller.applyChatListRow(
                    notificationChatListRow().copy(
                        lastMessage =
                            notifiedMessagePreview().copy(
                                plaintext = "**Rendered first**",
                                contentTokens = tokens,
                            ),
                    ),
                )

                controller.setChatListVisible(true)

                assertSame(tokens, controller.items.single().previewTokens)
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun returningToTheListPublishesTheLatestProjectedDocument() =
        runTest {
            val first = markdown("First")
            val latest = markdown("Latest")
            val controller = controller()
            try {
                controller.setChatListVisible(false)
                controller.applyChatListRow(
                    notificationChatListRow().copy(
                        lastMessage = notifiedMessagePreview().copy(plaintext = "*First*", contentTokens = first),
                    ),
                )
                controller.setChatListVisible(true)
                assertSame(first, controller.items.single().previewTokens)

                controller.setChatListVisible(false)
                controller.applyChatListRow(
                    notificationChatListRow().copy(
                        lastMessage =
                            notifiedMessagePreview().copy(
                                messageIdHex = "e3".repeat(32),
                                plaintext = "**Latest**",
                                contentTokens = latest,
                                timelineAt = 3uL,
                            ),
                        activitySortAt = 3uL,
                        updatedAt = 3uL,
                    ),
                )
                controller.setChatListVisible(true)

                assertSame(latest, controller.items.single().previewTokens)
            } finally {
                controller.onCleared()
            }
        }

    private fun controller() =
        ChatsController(
            appState = appState(),
            initialAccountRef = ACCOUNT_REF,
            memberSnapshotLoader = { _, _ -> emptyList() },
        )

    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun markdown(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
            blankLinesBefore = ByteArray(0),
        )

    private fun richMarkdown() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks =
                listOf(
                    MarkdownBlockFfi.Heading(2u, listOf(MarkdownInlineFfi.Text("Release 🚀"))),
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("مهم"))),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Code("v1"),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Link(
                                dest = "https://example.com/release",
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("details")),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.NostrMention(
                                MarkdownNostrEntityFfi(
                                    MarkdownNostrHrpFfi.NPUB,
                                    "npub1" + "q".repeat(58),
                                ),
                            ),
                        ),
                    ),
                ),
            blankLinesBefore = ByteArray(0),
        )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "aa".repeat(32)
    }
}
