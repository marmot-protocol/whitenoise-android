package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.ui.conversation.messages.focusedMessagePreviewText
import dev.ipf.whitenoise.android.ui.group.MEMBER_NPROFILE
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual Markdown composable's first-frame hydration subscription and profile tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileReferenceComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A styled profile in the first block must not add a paragraph beyond the two authored separators. */
    @Test
    fun focusedProfilePreviewDoesNotInsertAnExtraVisualLine() {
        val reference =
            MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE))
        val document =
            MarkdownDocumentFfi(
                listOf(
                    MarkdownBlockFfi.Paragraph(listOf(reference)),
                    MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("second"))),
                ),
                false,
                byteArrayOf(),
            )
        val preview = focusedMessagePreviewText("", document, mentionDisplayName = { "Alice" })
        composeRule.setContent {
            WhiteNoiseTheme { Text(preview, modifier = Modifier.width(300.dp)) }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("@Alice\n\nsecond").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals(3, layouts.single().lineCount)
    }

    /** A received profile URI refreshes its name in place while retaining its canonical destination. */
    @Test
    fun receivedProfileUriRefreshesAndOpensTheSameProfile() {
        var name by mutableStateOf<String?>(null)
        var tapped: String? = null
        val document =
            MarkdownDocumentFfi(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.NostrUri(
                                MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE),
                            ),
                        ),
                    ),
                ),
                false,
                byteArrayOf(),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = document,
                    mentionDisplayName = { name },
                    isGroupMember = { false },
                    onNostrProfileTap = { tapped = it },
                )
            }
        }
        composeRule.onNodeWithText("@" + shortenedBech32(MEMBER_NPROFILE)).assertIsDisplayed()
        composeRule.runOnIdle { name = "Alice" }
        composeRule.onNodeWithText("@Alice").assertIsDisplayed()
        composeRule.onNodeWithText("@Alice").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(MEMBER_NPROFILE, tapped) }
        composeRule.runOnIdle { name = "Alicia" }
        composeRule.onNodeWithText("@Alicia").assertIsDisplayed()
        composeRule.onNodeWithText("@Alice").assertDoesNotExist()
    }
}
