package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/** Characters per display group of a public key (#3321). An npub holds letters as well as digits. */
internal const val PUBLIC_KEY_GROUP_LENGTH = 5

/**
 * The canonical [npub] split into groups of five characters for display, the last group holding whatever remains.
 * Joining the groups gives back [npub] exactly, so grouping never reaches copy, share or QR.
 */
internal fun publicKeyDisplayGroups(npub: String): List<String> = npub.chunked(PUBLIC_KEY_GROUP_LENGTH)

/**
 * The complete public key in monospace groups of five characters that wrap only between groups, so every character
 * can be read, compared or photographed without scrolling, copying or another app (#3321). Characters stay left to
 * right in a right-to-left layout. The spacing is drawn only: assistive technologies and tests read the canonical
 * contiguous [npub]. With [label], a caption names the value as the public key and is read together with it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun FullPublicKey(
    npub: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    textAlign: TextAlign = TextAlign.Center,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .let { base -> testTag?.let(base::testTag) ?: base }
                .clearAndSetSemantics { text = AnnotatedString(npub) },
        ) {
            Text(
                text = publicKeyDisplayGroups(npub).joinToString(" "),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr,
                    ),
                textAlign = textAlign,
            )
        }
    }
}
