package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
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

    /** A received profile URI refreshes its name in place while retaining its canonical destination. */
    @Test
    fun receivedProfileUriRefreshesAndOpensTheSameProfile() {
        var name by mutableStateOf<String?>(null)
        var tapped: String? = null
        val document =
            MarkdownDocumentFfi(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE))),
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
