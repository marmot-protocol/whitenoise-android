@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.R

/**
 * Text that shows its first [maxLines] lines and offers Show all when more exists, so a long
 * command, output or diff stays readable in full before anyone approves or relies on it.
 * [softWrap] false keeps code lines intact; the caller supplies horizontal scrolling.
 */
@Composable
internal fun AgentExpandableText(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    var expanded by rememberSaveable(text.text) { mutableStateOf(false) }
    var overflows by rememberSaveable(text.text) { mutableStateOf(false) }
    Column {
        SelectionContainer {
            Text(
                text = text,
                modifier = modifier,
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else maxLines,
                // Ellipsis would collapse unwrapped code to one line; clip it at the line limit instead.
                overflow = if (softWrap) TextOverflow.Ellipsis else TextOverflow.Clip,
                softWrap = softWrap,
                onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
            )
        }
        if (overflows) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = stringResource(if (expanded) R.string.show_less else R.string.agent_text_show_all),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
