package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSetupScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.newGroupDetailsEditable
import dev.ipf.whitenoise.android.ui.chats.newchat.submittedNewGroupName
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NewGroupNameEmojiPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun emojiActionIsAccessibleAndOpensSharedPicker() {
        renderScreen()

        val action = composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker))
        action.assertIsDisplayed().assertHasClickAction()
        val bounds = action.getUnclippedBoundsInRoot()
        val fieldBounds = composeRule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertTrue(
            "Emoji action must use the leading half of the field",
            bounds.left + bounds.right < fieldBounds.left + fieldBounds.right,
        )
        assertTrue("Emoji action width must be at least 48dp", bounds.right - bounds.left >= 48.dp)
        assertTrue("Emoji action height must be at least 48dp", bounds.bottom - bounds.top >= 48.dp)
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))

        action.performClick()

        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactViewportAndLargeFontKeepTheNameFieldAndEmojiActionVisible() {
        renderScreen(fontScale = 2f)

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val nameField = composeRule.onNode(hasSetTextAction())
        val emojiAction = composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker))

        listOf(nameField, emojiAction).forEach { node ->
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "$bounds must sit fully inside $rootBounds",
                bounds.left >= rootBounds.left &&
                    bounds.right <= rootBounds.right &&
                    bounds.top >= rootBounds.top &&
                    bounds.bottom <= rootBounds.bottom,
            )
        }

        val actionBounds = emojiAction.getUnclippedBoundsInRoot()
        assertTrue("Emoji action width must be at least 48dp", actionBounds.right - actionBounds.left >= 48.dp)
        assertTrue("Emoji action height must be at least 48dp", actionBounds.bottom - actionBounds.top >= 48.dp)
    }

    @Test
    fun picksReplaceTheSelectionMoveTheCaretAndKeepThePickerOpen() {
        renderScreen()
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextReplacement("hello world")
        field.performTextInputSelection(TextRange(6, 11))
        composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker)).performClick()
        waitForEmoji("😀")

        composeRule.onAllNodesWithText("😀")[0].performClick()

        val firstUpdate = composeRule.onNode(hasSetTextAction() and hasText("hello 😀"))
        assertSelection(firstUpdate, TextRange("hello 😀".length))
        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertIsDisplayed()

        composeRule.onAllNodesWithText("😀")[0].performClick()
        val secondUpdate = composeRule.onNode(hasSetTextAction() and hasText("hello 😀😀"))
        assertSelection(secondUpdate, TextRange("hello 😀😀".length))
    }

    @Test
    fun insertionCoercesSelectionsAroundExtendedGraphemeClusters() {
        val family = "👨‍👩‍👧"

        val collapsed =
            insertEmojiAtSelection(
                TextFieldValue(text = "A${family}B", selection = TextRange(4)),
                "😀",
            )
        val selected =
            insertEmojiAtSelection(
                TextFieldValue(text = "A${family}B", selection = TextRange(2, 5)),
                "😀",
            )

        assertEquals("A$family😀B", collapsed.text)
        assertEquals(TextRange("A$family😀".length), collapsed.selection)
        assertEquals("A😀B", selected.text)
        assertEquals(TextRange("A😀".length), selected.selection)
    }

    @Test
    fun insertionKeepsAValidBoundaryAfterANonEmojiZwj() {
        val source = "A\u200DB"

        val result =
            insertEmojiAtSelection(
                TextFieldValue(text = source, selection = TextRange(2)),
                "😀",
            )

        assertEquals("A\u200D😀B", result.text)
        assertEquals(TextRange("A\u200D😀".length), result.selection)
    }

    @Test
    fun submittedNameUsesTheEmojiEditedText() {
        val edited =
            insertEmojiAtSelection(
                TextFieldValue(text = "  marmots  ", selection = TextRange(2, 9)),
                "😀",
            )

        assertEquals("😀", submittedNewGroupName(edited))
    }

    @Test
    fun picksUseTheSharedRecentEmojiPath() {
        renderScreen()
        composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker)).performClick()
        waitForEmoji("😀")

        composeRule.onAllNodesWithText("😀")[0].performClick()

        val recents = (context.applicationContext as WhiteNoiseApplication).recentEmojiRecentsOwner.recents
        assertEquals("😀", recents.firstOrNull())
    }

    @Test
    fun dismissAndSavedStateRestorationPreserveTheDraftSelectionAndPicker() {
        val restorationTester = StateRestorationTester(composeRule)
        val state = appState()
        restorationTester.setContent {
            WhiteNoiseTheme {
                NewGroupSetupScreen(
                    appState = state,
                    members = emptyList(),
                    onBack = {},
                    onCreateCompletedOpen = { _, _ -> },
                )
            }
        }
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextReplacement("Team 😀 name")
        field.performTextInputSelection(TextRange(5, 7))
        composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        val restored = composeRule.onNode(hasSetTextAction() and hasText("Team 😀 name"))
        assertSelection(restored, TextRange(5, 7))
        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertIsDisplayed()

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertDoesNotExist()
        composeRule.onNode(hasSetTextAction() and hasText("Team 😀 name")).assertExists()
    }

    @Test
    fun lockedRetryStateDisablesTheEmojiActionAndCannotOpenThePicker() {
        renderScreen(initialRetryGroupIdHex = "created-group")

        val action = composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker))
        action.assertIsNotEnabled().performClick()

        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertDoesNotExist()
    }

    @Test
    fun createAndImageOperationsAlsoLockGroupNameEmojiEditing() {
        assertTrue(newGroupDetailsEditable(retryGroupIdHex = null, busy = false, imagePreparing = false))
        assertFalse(newGroupDetailsEditable(retryGroupIdHex = "created", busy = false, imagePreparing = false))
        assertFalse(newGroupDetailsEditable(retryGroupIdHex = null, busy = true, imagePreparing = false))
        assertFalse(newGroupDetailsEditable(retryGroupIdHex = null, busy = false, imagePreparing = true))
    }

    private fun waitForEmoji(emoji: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emoji).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertSelection(
        field: SemanticsNodeInteraction,
        expected: TextRange,
    ) {
        val actual = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(expected, actual)
    }

    private fun renderScreen(
        initialRetryGroupIdHex: String? = null,
        fontScale: Float = 1f,
    ) {
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    NewGroupSetupScreen(
                        appState = state,
                        members = emptyList(),
                        onBack = {},
                        onCreateCompletedOpen = { _, _ -> },
                        initialRetryGroupIdHex = initialRetryGroupIdHex,
                    )
                }
            }
        }
    }

    private fun string(res: Int): String = context.getString(res)

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NewGroupEmojiDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val ACCOUNT_HEX = "alice"
    }
}

private class NewGroupEmojiDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
