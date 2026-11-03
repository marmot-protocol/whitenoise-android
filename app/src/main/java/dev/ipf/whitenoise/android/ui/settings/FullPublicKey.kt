package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.isCanonicalNpub

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

/**
 * The person profile's one public-key presentation (#3565): the whole key in smaller monospace groups beside a copy
 * glyph, which turns into a check while [copied]. Text and glyph are one button that runs [onCopy], so the key has a
 * single accessible Copy public key action and its spoken value is the canonical contiguous [npub]. A reference that
 * is not a complete npub is shown as inert text with no copy action, so an unresolved value is never handed out.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun CopyableFullPublicKey(
    npub: String,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    targetTestTag: String? = null,
    textTestTag: String? = null,
) {
    val copyable = isCanonicalNpub(npub)
    val copyLabel = stringResource(R.string.copy_public_key)
    val state = stringResource(if (copied) R.string.copied else R.string.not_copied)
    Row(
        modifier =
            modifier
                .heightIn(min = COPYABLE_PUBLIC_KEY_MIN_HEIGHT)
                .clip(MaterialTheme.shapes.medium)
                .let { base ->
                    if (copyable) {
                        base.clickable(role = Role.Button, onClickLabel = copyLabel, onClick = onCopy)
                    } else {
                        base
                    }
                }.let { base -> targetTestTag?.let(base::testTag) ?: base }
                .clearAndSetSemantics {
                    text = AnnotatedString(npub)
                    if (copyable) stateDescription = state
                }.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = publicKeyDisplayGroups(npub).joinToString(" "),
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .let { base -> textTestTag?.let(base::testTag) ?: base },
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (copyable) {
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy),
                contentDescription = null,
                modifier = Modifier.size(COPYABLE_PUBLIC_KEY_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val COPYABLE_PUBLIC_KEY_MIN_HEIGHT = 48.dp
private val COPYABLE_PUBLIC_KEY_ICON_SIZE = 20.dp
