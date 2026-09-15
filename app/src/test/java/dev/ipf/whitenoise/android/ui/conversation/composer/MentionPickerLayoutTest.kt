package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mention box is a plain list of names, capped at five rows, with no heading or secondary line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MentionPickerLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun candidate(index: Int) =
        MentionComposer.Candidate(
            accountIdHex = index.toString().repeat(64).take(64),
            npub = "npub1$index",
            displayName = "Member $index",
            nip05 = "member$index@example.com",
        )

    /** At most five names render, and the nip-05 subtitle is not part of a row. */
    @Test
    fun atMostFiveNamesRenderWithoutSecondaryLines() {
        val candidates = (1..8).map(::candidate)
        composeRule.setContent {
            WhiteNoiseTheme {
                MentionPicker(candidates = candidates, onPick = {})
            }
        }

        assertEquals(5, MENTION_PICKER_MAX_ROWS)
        composeRule.onNodeWithText("Member 5").assertExists()
        composeRule.onNodeWithText("Member 6").assertDoesNotExist()
        composeRule.onAllNodesWithText("member1@example.com").assertCountEquals(0)
    }

    /** Picking a row reports that candidate through the row's accessible label. */
    @Test
    fun pickingARowReportsItsCandidate() {
        val picked = mutableListOf<String>()
        val candidates = (1..2).map(::candidate)
        composeRule.setContent {
            WhiteNoiseTheme {
                MentionPicker(candidates = candidates, onPick = { picked += it.displayName })
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.mention_picker_member, "Member 2"))
            .performClick()
        assertEquals(listOf("Member 2"), picked)
    }
}
