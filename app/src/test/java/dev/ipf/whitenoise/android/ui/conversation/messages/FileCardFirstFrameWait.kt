package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

private const val FILE_CARD_TAG_PREFIX = "file-attachment-card:"
private const val FILE_CARD_VISIBLE_TIMEOUT_MILLIS = 10_000L

/** Matches every file attachment card by its production test tag. */
private val fileCard =
    SemanticsMatcher("is a file attachment card") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(FILE_CARD_TAG_PREFIX) == true
    }

/**
 * Waits until [expectedCards] file cards are composed, or at least one when it is null, and none is still hidden. A
 * received card stays invisible and hidden from accessibility until its off-main cache refresh answers, which Compose
 * idleness does not await, so a capture taken earlier records blank card slots.
 */
internal fun ComposeTestRule.awaitFileCardsVisible(expectedCards: Int? = null) {
    waitUntil(timeoutMillis = FILE_CARD_VISIBLE_TIMEOUT_MILLIS) {
        val cards = onAllNodes(fileCard, useUnmergedTree = true).fetchSemanticsNodes()
        val composed = if (expectedCards == null) cards.isNotEmpty() else cards.size == expectedCards
        composed && cards.none { SemanticsProperties.HideFromAccessibility in it.config }
    }
}
