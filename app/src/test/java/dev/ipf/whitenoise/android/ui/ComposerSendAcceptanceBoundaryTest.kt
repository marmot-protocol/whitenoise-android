package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.composerDraftOwnerKey
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class ComposerSendAcceptanceBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Optimistic acceptance clears presentation but leaves persistence to durable acceptance. */
    @Test
    fun optimisticAcceptanceClearsVisibleTextWithoutDeletingThePersistedDraft() {
        val persistedDraftChanges = mutableListOf<String>()
        var afterSendCount = 0
        val sentText = longDraft("survives until MDK accepts")

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, onAccepted -> onAccepted() },
                        initialDraft = TextFieldValue(sentText),
                        onDraftChange = { persistedDraftChanges += it.text },
                        onAfterSend = { afterSendCount += 1 },
                    )
                }
            }
        }

        resizeHandle().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(sentText).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.composer_resize)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.composer_collapse)).assertDoesNotExist()
        assertEquals(emptyList<String>(), persistedDraftChanges)
        assertEquals(1, afterSendCount)
    }

    /** A send that never reaches optimistic acceptance leaves text and geometry untouched. */
    @Test
    fun pendingSendPreservesTheDraftAndExplicitExpansion() {
        val sentText = longDraft("pending")

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue(sentText),
                    )
                }
            }
        }

        resizeHandle().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(sentText).assertExists()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
    }

    /** A delayed acceptance callback cannot clear a draft edited after the send tap. */
    @Test
    fun lateAcceptanceCannotClearANewerDraftOrItsExpansion() {
        val sentText = longDraft("first")
        val newerText = longDraft("newer")
        var accepted: (() -> Unit)? = null
        var value by mutableStateOf(TextFieldValue(sentText))

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, callback -> accepted = callback },
                        initialDraft = value,
                        onDraftChange = { value = it },
                    )
                }
            }
        }

        resizeHandle().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.onNodeWithText(sentText).performTextReplacement(newerText)
        composeRule.runOnIdle { checkNotNull(accepted).invoke() }

        composeRule.onNodeWithText(newerText).assertExists()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
    }

    /** A delayed callback cannot clear text recreated by a later content-edit generation. */
    @Test
    fun lateAcceptanceCannotClearAnABARecreatedDraft() {
        val sentText = longDraft("first")
        val interimText = longDraft("interim")
        var accepted: (() -> Unit)? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, callback -> accepted = callback },
                        initialDraft = TextFieldValue(sentText),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.onNodeWithText(sentText).performTextReplacement(interimText)
        composeRule.onNodeWithText(interimText).performTextReplacement(sentText)
        composeRule.runOnIdle { checkNotNull(accepted).invoke() }

        composeRule.onNodeWithText(sentText).assertExists()
    }

    /** A reader-surface ABA edit invalidates the same token captured by the main composer. */
    @Test
    fun mountedMainAndReaderComposersShareAcceptanceOwnership() {
        val sentText = "Shared draft"
        val interimText = "Reader edit"
        val sharedState = ComposerTextState(TextFieldValue(sentText))
        var accepted: (() -> Unit)? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Column {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, callback -> accepted = callback },
                            modifier = Modifier.testTag("main-composer"),
                            textState = sharedState,
                        )
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            modifier = Modifier.testTag("reader-composer"),
                            textState = sharedState,
                        )
                    }
                }
            }
        }

        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.send)) and
                    hasAnyAncestor(hasTestTag("main-composer")),
            ).performClick()
        composeRule
            .onNode(hasText(sentText) and hasAnyAncestor(hasTestTag("reader-composer")))
            .performTextReplacement(interimText)
        composeRule
            .onNode(hasText(interimText) and hasAnyAncestor(hasTestTag("main-composer")))
            .performTextReplacement(sentText)
        composeRule.runOnIdle { checkNotNull(accepted).invoke() }

        composeRule.onNode(hasText(sentText) and hasAnyAncestor(hasTestTag("main-composer"))).assertExists()
        composeRule.onNode(hasText(sentText) and hasAnyAncestor(hasTestTag("reader-composer"))).assertExists()
    }

    /** Replacing an account owner in place prevents its delayed callback from clearing the successor. */
    @Test
    fun sameGroupAccountReplacementRejectsThePreviousOwnersAcceptance() {
        var accountRef by mutableStateOf("account-a")
        var initialDraft by mutableStateOf(TextFieldValue("Account A draft"))
        var accepted: (() -> Unit)? = null

        composeRule.setContent {
            val ownerKey = composerDraftOwnerKey(accountRef, "shared-group")
            val textState = rememberComposerTextState(ownerKey, initialDraft)
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, callback -> accepted = callback },
                        draftKey = ownerKey,
                        textState = textState,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.runOnIdle {
            initialDraft = TextFieldValue("Account B draft")
            accountRef = "account-b"
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { checkNotNull(accepted).invoke() }

        composeRule.onNodeWithText("Account B draft").assertExists()
    }

    /** Returns the accessible resize action shared by the acceptance scenarios. */
    private fun resizeHandle() =
        context.getString(R.string.composer_resize).let { description ->
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithContentDescription(description)
        }

    /** Verifies the localized tap action exposed by the visible resize handle. */
    private fun assertResizeHandleToggleLabel(labelRes: Int) {
        val label = resizeHandle().fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(context.getString(labelRes), label)
    }

    /** Keeps fixture content long enough to expose the resize affordance. */
    private fun longDraft(owner: String): String {
        val prefix = "Draft $owner "
        return prefix + "keeps enough text to expose the resize handle. ".repeat(12)
    }
}
