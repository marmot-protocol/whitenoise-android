package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.state.notifiedMessagePreview
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FIRST_FRAME_MARKDOWN_TAG = "first-frame-markdown-row"

/** Visual contract that projected Markdown is already rendered on the first chat-list frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListFirstFrameMarkdownScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the first row only after proving styled text rendered and raw Markdown never did. */
    @Test
    fun projectedMarkdownRendersWithoutAPlaintextIntermediateFrame() {
        val appState = appState()
        val tokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Rendered first"))),
                                MarkdownInlineFfi.Text(" after launch"),
                            ),
                        ),
                    ),
                blankLinesBefore = ByteArray(0),
            )
        val item =
            chatListItemFromProjection(
                notificationChatListRow().copy(
                    lastMessage =
                        notifiedMessagePreview().copy(
                            plaintext = "**Rendered first** after launch",
                            contentTokens = tokens,
                        ),
                ),
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(FIRST_FRAME_MARKDOWN_TAG)) {
                        ChatRow(
                            item = item,
                            appState = appState,
                            interactionsEnabled = true,
                            onClick = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Rendered first after launch").assertIsDisplayed()
        composeRule.onNodeWithText("**Rendered first** after launch").assertDoesNotExist()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule
            .onNodeWithTag(FIRST_FRAME_MARKDOWN_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_markdown_light.png")
    }

    /** Creates the active-account presentation dependencies used by the production chat row. */
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

    private object EmptyDraftPersistence : DraftPersistence {
        /** Starts the screenshot without any persisted composer drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards draft writes because the screenshot covers only projected preview rendering. */
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
