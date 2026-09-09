package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.ipf.whitenoise.android.R

internal data class TtsSentenceChoice(
    val revision: String,
    val sentenceId: String,
    val ordinal: Int,
    val excerpt: String,
)

internal class TtsSentenceActions(
    val choices: (String, String) -> List<TtsSentenceChoice>,
    val select: (TtsSentenceChoice) -> Boolean,
)

/** Only the focused leaf creates actions; its bounded chooser is discarded on revision changes. */
@Composable
internal fun ttsSentenceAccessibilityActions(
    leafId: String,
    originalText: String,
    owner: TtsSentenceActions?,
): List<CustomAccessibilityAction> {
    val choices =
        remember(owner, leafId, originalText) {
            owner
                ?.choices
                ?.invoke(leafId, originalText)
                .orEmpty()
                .take(200)
        }
    var choosing by remember(owner, leafId, originalText) { mutableStateOf(false) }
    val readLabel = stringResource(R.string.tts_read_from_sentence)
    val chooseLabel = stringResource(R.string.tts_choose_sentence)
    if (choosing && choices.isNotEmpty()) {
        AlertDialog(
            modifier = Modifier.testTag("tts_sentence_chooser"),
            onDismissRequest = { choosing = false },
            title = { Text(chooseLabel) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        TextButton(onClick = {
                            owner?.select?.invoke(choice)
                            choosing = false
                        }) {
                            Text("${choice.ordinal + 1}. ${choice.excerpt}")
                        }
                    }
                }
            },
            confirmButton = {},
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
                    choosing = true
                    true
                },
            )
    }
}
