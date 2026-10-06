package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.conversation.KeptAttachmentKind
import dev.ipf.whitenoise.android.ui.conversation.KeptAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Collapsed cards use the number of hidden attachments and Android's actual locale plural selection. */
@RunWith(RobolectricTestRunner::class)
class KeptAttachmentSummaryTest {
    @get:Rule val composeRule = createComposeRule()

    /** One visible attachment plus one hidden attachment requires the singular English copy. */
    @Test
    @Config(qualifiers = "en")
    fun oneHiddenAttachmentUsesSingular() {
        render(2)
        composeRule.onNodeWithText("+1 more attachment").assertExists()
    }

    /** Multiple hidden attachments retain the plural form and the hidden count. */
    @Test
    @Config(qualifiers = "en")
    fun multipleHiddenAttachmentsUsePlural() {
        render(3)
        composeRule.onNodeWithText("+2 more attachments").assertExists()
    }

    /** Localized singular grammar is selected by resources, independent of the English wording. */
    @Test
    @Config(qualifiers = "es")
    fun spanishSingularIsLocalized() {
        render(2)
        composeRule.onNodeWithText("1 archivo adjunto más").assertExists()
    }

    /** Expansion shows all entries and removes the hidden-count label. */
    @Test
    @Config(qualifiers = "en")
    fun expandedCardHasNoHiddenAttachmentLabel() {
        render(2, expanded = true)
        composeRule.onNodeWithText("+1 more attachment").assertDoesNotExist()
        composeRule.onNodeWithText("Document 2.pdf").assertExists()
    }

    /** Render the production summary with deterministic metadata and no transfer or media acquisition. */
    private fun render(
        count: Int,
        expanded: Boolean = false,
    ) {
        val attachments =
            List(count) { index ->
                KeptAttachmentPresentation(KeptAttachmentKind.File, "Document ${index + 1}.pdf", "File", "Available")
            }
        composeRule.setContent {
            WhiteNoiseTheme {
                Column { KeptAttachmentSummary(attachments, expanded) }
            }
        }
    }
}
