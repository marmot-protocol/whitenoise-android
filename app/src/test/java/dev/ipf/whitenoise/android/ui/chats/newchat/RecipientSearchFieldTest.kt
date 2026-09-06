package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientPastePolicy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class RecipientSearchFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    @Before
    fun clearClipboardBeforeTest() {
        clipboard.clearPrimaryClip()
    }

    @After
    fun clearClipboardAfterTest() {
        clipboard.clearPrimaryClip()
    }

    @Test
    fun explicitPasteAffordanceInsertsOnlyCanonicalNpub() {
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", "Alice ($ALICE_NPUB)"))
        val state = TextFieldState()
        var rejections = 0
        setField(state = state, onRejected = { rejections += 1 })

        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()

        composeRule.runOnIdle {
            assertEquals(ALICE_NPUB, state.text.toString())
            assertEquals(TextRange(ALICE_NPUB.length), state.selection)
            assertEquals(0, rejections)
        }
    }

    @Test
    fun explicitPasteAffordanceRejectsAmbiguousItemsWithoutPublishingRawText() {
        val clip = ClipData.newPlainText("first", ALICE_NPUB)
        clip.addItem(ClipData.Item(BOB_NPUB))
        clipboard.setPrimaryClip(clip)
        val state = TextFieldState()
        var rejections = 0
        setField(state = state, onRejected = { rejections += 1 })

        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()

        composeRule.runOnIdle {
            assertEquals("", state.text.toString())
            assertEquals(1, rejections)
        }
    }

    @Test
    fun embeddedCrossTypeAmbiguityDoesNotMutateTheField() {
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", "$ALICE_NPUB or bob@example.com"))
        val state = TextFieldState(initialText = "keep", initialSelection = TextRange(1, 3))
        var rejections = 0
        setField(state = state, onRejected = { rejections += 1 })

        composeRule.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.PasteText)

        composeRule.runOnIdle {
            assertEquals("keep", state.text.toString())
            assertEquals(TextRange(1, 3), state.selection)
            assertEquals(1, rejections)
        }
    }

    @Test
    fun unicodeNip05AmbiguityPreservesSelectionAndPresentsAnAccessibleError() {
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", "$ALICE_NPUB or $UNICODE_NIP05"))
        val state = TextFieldState(initialText = "keep", initialSelection = TextRange(1, 3))
        var presentedError: String? = null
        setField(
            state = state,
            onRejected = { presentedError = context.getString(R.string.error_invalid_identity_reference) },
        )

        composeRule.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.PasteText)

        composeRule.runOnIdle {
            assertEquals("keep", state.text.toString())
            assertEquals(TextRange(1, 3), state.selection)
            assertEquals(context.getString(R.string.error_invalid_identity_reference), presentedError)
        }
    }

    @Test
    fun exactNip05StillPastesThroughTheExplicitAffordance() {
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", "alice@example.com"))
        val state = TextFieldState()
        setField(state = state)

        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()

        composeRule.runOnIdle {
            assertEquals("alice@example.com", state.text.toString())
            assertEquals(TextRange("alice@example.com".length), state.selection)
        }
    }

    @Test
    fun accessibilityPasteIsRejectedAtomicallyByRecipientBoundary() {
        val clip = ClipData.newPlainText("first", ALICE_NPUB)
        clip.addItem(ClipData.Item(BOB_NPUB))
        clipboard.setPrimaryClip(clip)
        val state = TextFieldState(initialText = "keep", initialSelection = TextRange(1, 3))
        var rejections = 0
        setField(state = state, onRejected = { rejections += 1 })

        composeRule.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.PasteText)

        composeRule.runOnIdle {
            assertEquals("keep", state.text.toString())
            assertEquals(TextRange(1, 3), state.selection)
            assertEquals(1, rejections)
        }
    }

    @Test
    fun accessibilityPasteNormalizesEmbeddedNpubAtTheCurrentSelection() {
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", "Alice shared ($ALICE_NPUB)."))
        val state = TextFieldState(initialText = "send placeholder later", initialSelection = TextRange(5, 16))
        var rejections = 0
        setField(state = state, onRejected = { rejections += 1 })

        composeRule.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.PasteText)

        composeRule.runOnIdle {
            assertEquals("send $ALICE_NPUB later", state.text.toString())
            assertEquals(TextRange(5 + ALICE_NPUB.length), state.selection)
            assertEquals(0, rejections)
        }
    }

    @Test
    fun ordinaryTypingAndClearKeepTheirExistingBehavior() {
        val state = TextFieldState()
        setField(state = state)

        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("Alice")
        composeRule.onNodeWithContentDescription(context.getString(R.string.clear)).performClick()

        composeRule.runOnIdle { assertEquals("", state.text.toString()) }
    }

    @Test
    fun recipientFieldStateSurvivesSavedInstanceRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var state: TextFieldState
        restorationTester.setContent {
            state = rememberTextFieldState("persisted", TextRange(2, 6))
            WhiteNoiseTheme {
                Surface {
                    RecipientSearchField(
                        state = state,
                        placeholder = "Search people",
                        onPasteRejected = {},
                        modifier = Modifier.testTag(FIELD_TAG),
                    )
                }
            }
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals("persisted", state.text.toString())
            assertEquals(TextRange(2, 6), state.selection)
        }
    }

    @Test
    fun directClipboardExtractionNeverDereferencesProvidersOrAcceptsIntents() {
        val uriClip = ClipData.newRawUri("recipient", Uri.parse("nostr:$ALICE_NPUB"))
        val intentClip = ClipData.newIntent("recipient", Intent("test.private.payload"))

        assertEquals(listOf("nostr:$ALICE_NPUB"), uriClip.directRecipientPasteItems())
        assertNull(intentClip.directRecipientPasteItems())
    }

    @Test
    fun directClipboardExtractionRejectsOversizeAndUnboundedItemCountsBeforeJoining() {
        val oversized = ClipData.newPlainText("recipient", "x".repeat(RecipientPastePolicy.MAX_UTF8_BYTES + 1))
        val tooMany = ClipData.newPlainText("recipient", "")
        repeat(RecipientPastePolicy.MAX_ITEMS) { tooMany.addItem(ClipData.Item("")) }

        assertNull(oversized.directRecipientPasteItems())
        assertNull(tooMany.directRecipientPasteItems())
    }

    private fun setField(
        state: TextFieldState,
        onRejected: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    RecipientSearchField(
                        state = state,
                        placeholder = "Search people",
                        onPasteRejected = onRejected,
                        modifier = Modifier.testTag(FIELD_TAG),
                    )
                }
            }
        }
    }

    private companion object {
        const val FIELD_TAG = "recipient-search"
        const val ALICE_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        const val BOB_NPUB = "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygse4sl3h"
        const val UNICODE_NIP05 = "δοκιμή@παράδειγμα.δοκιμή"
    }
}
