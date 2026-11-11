package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags

internal data class TtsSentenceChoice(
    val revision: String,
    val sentenceId: String,
    val ordinal: Int,
    val excerpt: String,
)

internal class TtsSentenceActions(
    val choices: (String, String) -> List<TtsSentenceChoice>,
    val select: (TtsSentenceChoice) -> Boolean,
) {
    private val leafChoices = mutableStateMapOf<String, List<TtsSentenceChoice>>()

    /** Every published leaf's sentences in reading order, for one paged chooser on a merged message row. */
    val messageChoices: List<TtsSentenceChoice>
        get() =
            leafChoices.values
                .flatten()
                .sortedBy(TtsSentenceChoice::ordinal)
                .distinctBy(TtsSentenceChoice::sentenceId)

    /** Records the sentences one rendered leaf currently offers. */
    fun publish(
        leafId: String,
        choices: List<TtsSentenceChoice>,
    ) {
        leafChoices[leafId] = choices
    }

    /** Forgets a leaf's sentences unless a newer composition already replaced them. */
    fun retract(
        leafId: String,
        choices: List<TtsSentenceChoice>,
    ) {
        if (leafChoices[leafId] === choices) leafChoices.remove(leafId)
    }
}

/** Sentences shown on one page of the chooser, which pages on instead of dropping later sentences. */
private const val SENTENCE_CHOICES_PER_PAGE = 200

/** Only the focused leaf creates actions; its bounded chooser is discarded on revision changes. */
@Composable
internal fun ttsSentenceAccessibilityActions(
    leafId: String,
    originalText: String,
    owner: TtsSentenceActions?,
): List<CustomAccessibilityAction> = sentenceChooserActions(rememberLeafChoices(owner, leafId, originalText), owner)

/** A leaf's own read-aloud actions and those it forwards to a merged message row. */
internal class TtsSentenceLeafActions(
    val own: List<CustomAccessibilityAction>,
    val row: List<CustomAccessibilityAction>,
)

/** Resolves the leaf's sentences once for both its own node and the merged row it belongs to. */
@Composable
internal fun ttsSentenceLeafAndRowActions(
    leafId: String,
    originalText: String,
    owner: TtsSentenceActions?,
): TtsSentenceLeafActions {
    val choices = rememberLeafChoices(owner, leafId, originalText)
    return TtsSentenceLeafActions(sentenceChooserActions(choices, owner), rowChooserActions(leafId, choices, owner))
}

/**
 * The read-aloud actions a merged message row republishes for this leaf. A row is one focus stop and keeps
 * one action per label, so instead of each paragraph offering only its own sentences, the leaf holding the
 * message's first sentence offers a chooser over every paragraph's sentences and the other leaves offer none.
 */
@Composable
private fun rowChooserActions(
    leafId: String,
    choices: List<TtsSentenceChoice>,
    owner: TtsSentenceActions?,
): List<CustomAccessibilityAction> {
    DisposableEffect(owner, leafId, choices) {
        owner?.publish(leafId, choices)
        onDispose { owner?.retract(leafId, choices) }
    }
    val messageChoices = owner?.messageChoices.orEmpty()
    val first = messageChoices.firstOrNull()?.sentenceId
    val hostsRow = choices.any { it.sentenceId == first }
    return sentenceChooserActions(if (hostsRow) messageChoices else emptyList(), owner)
}

/** Resolves one leaf's bounded sentence choices for the current owner and text. */
@Composable
private fun rememberLeafChoices(
    owner: TtsSentenceActions?,
    leafId: String,
    originalText: String,
): List<TtsSentenceChoice> =
    remember(owner, leafId, originalText) {
        owner
            ?.choices
            ?.invoke(leafId, originalText)
            .orEmpty()
    }

/** Offers [choices] as one read action, or as a paged chooser dialog when there are several. */
@Composable
private fun sentenceChooserActions(
    choices: List<TtsSentenceChoice>,
    owner: TtsSentenceActions?,
): List<CustomAccessibilityAction> {
    var choosing by remember(owner, choices) { mutableStateOf(false) }
    var page by remember(owner, choices) { mutableStateOf(0) }
    val pages = remember(choices) { choices.chunked(SENTENCE_CHOICES_PER_PAGE) }
    val readLabel = stringResource(R.string.tts_read_from_sentence)
    val chooseLabel = stringResource(R.string.tts_choose_sentence)
    if (choosing && choices.isNotEmpty()) {
        AlertDialog(
            modifier = Modifier.testTag("tts_sentence_chooser").exposePerformanceTestTags(),
            onDismissRequest = { choosing = false },
            title = { Text(chooseLabel) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
                    pages.getOrNull(page).orEmpty().forEach { choice ->
                        TextButton(onClick = {
                            owner?.select?.invoke(choice)
                            choosing = false
                        }) {
                            Text("${choice.ordinal + 1}. ${choice.excerpt}")
                        }
                    }
                }
            },
            confirmButton = {
                if (page < pages.lastIndex) {
                    TextButton(onClick = { page += 1 }) { Text(stringResource(R.string.next)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    return when (choices.size) {
        0 -> emptyList()
        1 -> listOf(CustomAccessibilityAction(readLabel) { owner?.select?.invoke(choices.single()) == true })
        else ->
            listOf(
                CustomAccessibilityAction(chooseLabel) {
                    page = 0
                    choosing = true
                    true
                },
            )
    }
}
