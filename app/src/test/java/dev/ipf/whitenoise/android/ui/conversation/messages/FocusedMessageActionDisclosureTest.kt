package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The long-press menu's first level and the More actions row that reveals the rest (#4077).
 *
 * Runs on Android 13, where the activity's Back reaches the overlay, so Back is exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class FocusedMessageActionDisclosureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val clicks = mutableListOf<String>()
    private var dismissals = 0

    @Test
    fun firstLevelHidesTheRestUntilMoreActions() {
        render(first = listOf("Reply", "Copy", "Forward"), more = listOf("Select", "Info", "Delete"))

        listOf("Reply", "Copy", "Forward", "More actions").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Select", "Info", "Delete").forEach { rule.onNodeWithText(it).assertDoesNotExist() }

        rule.onNodeWithText("More actions").performClick()
        rule.waitForIdle()

        listOf("Reply", "Copy", "Forward", "Select", "Info", "Delete", "Fewer actions").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        assertEquals("the disclosure keeps the menu open", 0, dismissals)

        rule.onNodeWithText("Info").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("Info"), clicks)
    }

    @Test
    fun backFromTheFullSetReturnsToTheFirstLevelBeforeClosing() {
        render(first = listOf("Reply", "Copy"), more = listOf("Select", "Info", "Delete"))
        rule.onNodeWithText("More actions").performClick()
        rule.waitForIdle()

        pressBack()
        rule.onNodeWithText("More actions").assertExists()
        rule.onNodeWithText("Info").assertDoesNotExist()
        assertEquals("Back first leaves the full set", 0, dismissals)

        pressBack()
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle { assertEquals("a second Back closes the menu", 1, dismissals) }
    }

    @Test
    fun fewerActionsCollapsesTheFullSet() {
        render(first = listOf("Reply"), more = listOf("Select", "Info"))
        rule.onNodeWithText("More actions").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Fewer actions").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Select").assertDoesNotExist()
        rule.onNodeWithText("More actions").assertExists()
        assertEquals(0, dismissals)
    }

    @Test
    fun aSingleHiddenActionStaysOnTheFirstLevel() {
        render(first = listOf("Reply", "Copy"), more = listOf("Delete"))

        rule.onNodeWithText("Delete").assertExists()
        rule.onNodeWithTag(FOCUSED_ACTION_DISCLOSURE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun withoutAFirstLevelEveryActionShows() {
        render(first = emptyList(), more = listOf("Select", "Info", "Delete"))

        listOf("Select", "Info", "Delete").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag(FOCUSED_ACTION_DISCLOSURE_TEST_TAG).assertDoesNotExist()
    }

    /** At 200% text in a short window the full set scrolls, and the disclosure stays reachable at its end. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h480dp-mdpi")
    fun theDisclosureStaysReachableAtLargeTextInAShortWindow() {
        render(
            first = listOf("Keep on screen", "Reply", "Edit", "Copy", "Forward", "Save"),
            more = listOf("Select text", "Share", "Translate", "Read aloud", "Select", "Info", "Delete"),
            fontScale = 2f,
        )
        rule
            .onNodeWithText("More actions")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Delete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fewer actions").performScrollTo().assertIsDisplayed()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun render(
        first: List<String>,
        more: List<String>,
        fontScale: Float = 1f,
    ) {
        val actions =
            first.map { action(it, firstLevel = true) } + more.map { action(it, firstLevel = false) }
        rule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                FocusedMessageActions(
                    sourceBounds = IntRect(0, 260, 360, 340),
                    touchY = 300f,
                    mine = true,
                    actions = actions,
                    quickReactions = emptyList(),
                    canReact = false,
                    selectedReactions = emptySet(),
                    previewDescription = "Lifted message",
                    previewReady = true,
                    preview = { Box(Modifier.size(200.dp, 60.dp)) { Text("The lifted message") } },
                    onReact = {},
                    onMoreReactions = {},
                    onDismiss = { dismissals++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun action(
        label: String,
        firstLevel: Boolean,
    ) = FocusedMessageAction(
        label = label,
        supportingLabel = null,
        enabled = true,
        destructive = label == "Delete",
        icon = {},
        onClick = { clicks += label },
        firstLevel = firstLevel,
    )
}
